package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	kitmessaging "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	adapterpg "github.com/trainticket/greenfield/services/dispatch/internal/adapters/postgres"
	"github.com/trainticket/greenfield/services/dispatch/internal/application"
	"github.com/trainticket/greenfield/services/dispatch/internal/domain"
	apphttp "github.com/trainticket/greenfield/services/dispatch/internal/http"
)

func main() {
	shutdown, err := goruntime.InitOTelSDKFromEnv(context.Background(), "dispatch")
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
	pool, err := storage.NewPool(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		log.Fatal(err)
	}
	defer pool.Close()
	migrationsDir := os.Getenv("MIGRATIONS_DIR")
	if migrationsDir == "" {
		migrationsDir = "/app/migrations"
	}
	migrations, err := storage.LoadMigrations(os.DirFS(migrationsDir), ".")
	if err != nil {
		log.Fatal(err)
	}
	runner := storage.NewMigrationRunner(pool)
	if err := runner.Run(ctx, migrations); err != nil {
		log.Fatal(err)
	}
	redisClient, err := kitmessaging.NewRedisClient(os.Getenv("REDIS_URL"))
	if err != nil {
		log.Fatal(err)
	}
	if err := redisClient.Ping(ctx).Err(); err != nil {
		_ = redisClient.Close()
		log.Fatal(err)
	}
	defer func() { _ = redisClient.Close() }()
	go storage.NewOutboxRelay(pool, redisClient).Run(ctx)
	tx := adapterpg.NewTransactor(pool)
	service := application.NewService(application.ServiceConfig{Rides: adapterpg.NewRideRequestRepositoryWithProvider(tx), Publisher: adapterpg.NewOutboxPublisherWithProvider(tx), Clock: domain.RealClock{}, UnitOfWork: tx.Within, RequestTimeout: durationFromEnv("DISPATCH_REQUEST_TIMEOUT", 10*time.Minute), MatchingTimeout: durationFromEnv("DISPATCH_MATCHING_TIMEOUT", 10*time.Minute)})
	go runTimeoutScanner(ctx, service, durationFromEnv("DISPATCH_TIMEOUT_SCAN_INTERVAL", time.Minute), intFromEnv("DISPATCH_TIMEOUT_SCAN_LIMIT", 100))
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), ReadyCheck: storage.ReadyCheck(pool, runner.Ready), Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	apphttp.NewHandler(service, storage.NewIdempotencyStore(pool)).RegisterRoutes(router)
	server := goruntime.NewHTTPServer(goruntime.ServerConfig{Address: ":" + port, Handler: router})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}

func runTimeoutScanner(ctx context.Context, service *application.Service, interval time.Duration, limit int) {
	if interval <= 0 {
		return
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			processed, err := service.ScanTimedOut(ctx, limit)
			if err != nil {
				log.Printf("dispatch timeout scan failed: %v", err)
				continue
			}
			if processed > 0 {
				log.Printf("dispatch timeout scan failed %d ride requests", processed)
			}
		}
	}
}

func durationFromEnv(name string, def time.Duration) time.Duration {
	raw := os.Getenv(name)
	if raw == "" {
		return def
	}
	value, err := time.ParseDuration(raw)
	if err != nil {
		log.Printf("invalid %s duration, using default: %v", name, err)
		return def
	}
	return value
}

func intFromEnv(name string, def int) int {
	raw := os.Getenv(name)
	if raw == "" {
		return def
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		log.Printf("invalid %s integer, using default: %v", name, err)
		return def
	}
	return value
}

var _ idempotency.Store
