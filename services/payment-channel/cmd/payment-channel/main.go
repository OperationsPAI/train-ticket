package main

import (
	"context"
	"fmt"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	kitmessaging "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	adapterpg "github.com/trainticket/greenfield/services/payment-channel/internal/adapters/postgres"
	"github.com/trainticket/greenfield/services/payment-channel/internal/application"
	"github.com/trainticket/greenfield/services/payment-channel/internal/domain"
	apphttp "github.com/trainticket/greenfield/services/payment-channel/internal/http"
	paymentmetrics "github.com/trainticket/greenfield/services/payment-channel/internal/metrics"
	"log"
	"os"
	"os/signal"
	"strconv"
	"syscall"
)

func main() {
	shutdown, err := goruntime.InitTelemetryFromEnv(context.Background(), "payment-channel")
	if err != nil {
		log.Fatalf("failed to initialize OpenTelemetry: %v", err)
	}
	defer func() { _ = shutdown(context.Background()) }()
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	// The metrics variant installs pool acquisition tracing, which pgxpool reads
	// at construction and so cannot be attached afterwards.
	pool, poolMetrics, err := storage.NewPoolWithMetrics(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		log.Fatal(err)
	}
	defer pool.Close()
	dir := os.Getenv("MIGRATIONS_DIR")
	if dir == "" {
		dir = "/app/migrations"
	}
	mig, err := storage.LoadMigrations(os.DirFS(dir), ".")
	if err != nil {
		log.Fatal(err)
	}
	runner := storage.NewMigrationRunner(pool)
	if err := runner.Run(ctx, mig); err != nil {
		log.Fatal(err)
	}
	if len(os.Args) > 1 && os.Args[1] == "backfill-statement-lines" {
		if err := backfillStatementLines(ctx, adapterpg.NewRepository(adapterpg.NewTransactor(pool))); err != nil {
			log.Fatal(err)
		}
		return
	}
	redis, err := kitmessaging.NewRedisClient(os.Getenv("REDIS_URL"))
	if err != nil {
		log.Fatal(err)
	}
	if err := redis.Ping(ctx).Err(); err != nil {
		_ = redis.Close()
		log.Fatal(err)
	}
	defer func() { _ = redis.Close() }()
	go storage.NewOutboxRelay(pool, redis).Run(ctx)
	tx := adapterpg.NewTransactor(pool)
	profile := domain.Profile()
	businessMetrics, err := paymentmetrics.New(goruntime.MeterFromEnv(profile.ServiceID))
	if err != nil {
		log.Fatalf("failed to build payment business metrics: %v", err)
	}
	svc := application.NewWithMetrics(adapterpg.NewRepository(tx), adapterpg.NewOutboxPublisher(tx), tx.Within, businessMetrics)
	httpMetrics, err := goruntime.HTTPMetricsFromEnv(profile.ServiceID)
	if err != nil {
		log.Fatalf("failed to build HTTP server metrics: %v", err)
	}
	if _, err := poolMetrics.RegisterPoolMetrics(goruntime.MeterFromEnv(profile.ServiceID)); err != nil {
		log.Fatalf("failed to register pool metrics: %v", err)
	}
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), ReadyCheck: storage.ReadyCheck(pool, runner.Ready), Observer: goruntime.ObserverFromEnv(profile.ServiceID), HTTPMetrics: httpMetrics})
	var store idempotency.Store = storage.NewIdempotencyStore(pool)
	apphttp.New(svc, store).RegisterRoutes(router)
	server := goruntime.NewHTTPServer(goruntime.ServerConfig{Address: ":" + port, Handler: router})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}

func backfillStatementLines(ctx context.Context, repository *adapterpg.Repository) error {
	batchSize := 10
	if raw := os.Getenv("STATEMENT_BACKFILL_BATCH_SIZE"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed <= 0 || parsed > 100 {
			return fmt.Errorf("STATEMENT_BACKFILL_BATCH_SIZE must be between 1 and 100")
		}
		batchSize = parsed
	}
	var totalStatements int
	var totalLines int64
	for {
		result, err := repository.BackfillLegacyStatementLines(ctx, batchSize)
		if err != nil {
			return err
		}
		totalStatements += result.Statements
		totalLines += result.Lines
		log.Printf("statement line backfill progress: statements=%d lines=%d", totalStatements, totalLines)
		if result.Complete {
			log.Printf("statement line backfill complete: statements=%d lines=%d", totalStatements, totalLines)
			return nil
		}
	}
}
