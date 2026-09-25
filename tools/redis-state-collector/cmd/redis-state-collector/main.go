// Command redis-state-collector publishes Redis Streams transport state as
// OpenTelemetry metrics.
//
// It is the single reporter for consumer lag, pending entries, the age of the
// oldest owed entry and the dead-letter population. Running it once instead of
// once per subscriber is what makes the transport rules in the chart answer
// consistently for every stream, including the streams whose owning service is
// not a Java consumer.
package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"

	runtimekit "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/tools/redis-state-collector/internal/collector"
)

const (
	defaultPollIntervalSeconds = 15
	defaultServiceName         = "redis-state-collector"
	shutdownGrace              = 5 * time.Second
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "redis-state-collector: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	serviceName := env("OTEL_SERVICE_NAME", defaultServiceName)
	shutdownTelemetry, err := runtimekit.InitTelemetryFromEnv(ctx, serviceName)
	if err != nil {
		// Telemetry is the product here, but a collector that cannot reach the
		// gateway still has to start: it will be scraped once the gateway is
		// back, and CrashLooping would also lose the health endpoint the probes
		// use to report that.
		fmt.Fprintf(os.Stderr, "redis-state-collector: telemetry init failed: %v\n", err)
	}
	defer func() {
		shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownGrace)
		defer cancel()
		_ = shutdownTelemetry(shutdownCtx)
	}()

	client, err := redisClient()
	if err != nil {
		return err
	}
	defer func() { _ = client.Close() }()

	store := &collector.Store{}
	if _, err := collector.RegisterMetrics(runtimekit.MeterFromEnv(serviceName), store); err != nil {
		return fmt.Errorf("register transport metrics: %w", err)
	}

	var lastPollSucceeded atomic.Bool
	var stopped atomic.Bool
	go poll(ctx, client, store, &lastPollSucceeded, &stopped)

	return serve(ctx, &lastPollSucceeded)
}

// poll refreshes the snapshot on a fixed interval until the context is done.
func poll(ctx context.Context, client redis.UniversalClient, store *collector.Store, health *atomic.Bool, stopped *atomic.Bool) {
	defer stopped.Store(true)
	interval := pollInterval()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		refresh(ctx, client, store, health)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func refresh(ctx context.Context, client redis.UniversalClient, store *collector.Store, health *atomic.Bool) {
	pollCtx, cancel := context.WithTimeout(ctx, pollInterval())
	defer cancel()
	snapshot, err := collector.Collect(pollCtx, client, collector.DefaultOptions())
	if err != nil {
		// Partial reads are still published: one stream that failed must not
		// hide the other thirty-nine. The error is logged either way so the
		// failure is visible rather than inferred from a gap.
		fmt.Fprintf(os.Stderr, "redis-state-collector: poll incomplete: %v\n", err)
	}
	if len(snapshot.Groups) == 0 && len(snapshot.DeadLetters) == 0 && err != nil {
		// Nothing could be read at all. Keep the previous snapshot rather than
		// replacing it with an empty one, so "Redis is unreachable" and "the
		// transport is empty" do not produce the same series.
		health.Store(false)
		return
	}
	store.Set(snapshot)
	health.Store(err == nil)
}

// serve keeps the process alive for the probes. Liveness answers as soon as the
// process is running; readiness answers only once one poll has completed, so a
// collector that cannot reach Redis is reported as not ready instead of
// silently exporting nothing.
func serve(ctx context.Context, ready *atomic.Bool) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusOK)
		_, _ = writer.Write([]byte("ok"))
	})
	mux.HandleFunc("/readyz", func(writer http.ResponseWriter, _ *http.Request) {
		if !ready.Load() {
			writer.WriteHeader(http.StatusServiceUnavailable)
			_, _ = writer.Write([]byte("no completed poll"))
			return
		}
		writer.WriteHeader(http.StatusOK)
		_, _ = writer.Write([]byte("ready"))
	})
	server := &http.Server{
		Addr:              ":" + env("PORT", "8080"),
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}
	errs := make(chan error, 1)
	go func() {
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errs <- err
		}
	}()
	select {
	case err := <-errs:
		return err
	case <-ctx.Done():
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownGrace)
	defer cancel()
	return server.Shutdown(shutdownCtx)
}

func redisClient() (*redis.Client, error) {
	url := env("REDIS_URL", "redis://localhost:6379")
	options, err := redis.ParseURL(url)
	if err != nil {
		return nil, fmt.Errorf("parse REDIS_URL: %w", err)
	}
	// The collector reads a bounded number of small commands per poll, so it
	// needs almost no concurrency; a short timeout keeps one slow Redis from
	// stalling the poll for the whole interval.
	options.DialTimeout = 5 * time.Second
	options.ReadTimeout = 5 * time.Second
	options.WriteTimeout = 5 * time.Second
	return redis.NewClient(options), nil
}

func pollInterval() time.Duration {
	seconds, err := strconv.Atoi(strings.TrimSpace(os.Getenv("TRANSPORT_POLL_INTERVAL_SECONDS")))
	if err != nil || seconds < 1 {
		return defaultPollIntervalSeconds * time.Second
	}
	return time.Duration(seconds) * time.Second
}

func env(key, fallback string) string {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	return value
}
