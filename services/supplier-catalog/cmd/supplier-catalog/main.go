package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	kitmessaging "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/supplier-catalog/internal/adapters/messaging"
	adapterpg "github.com/trainticket/greenfield/services/supplier-catalog/internal/adapters/postgres"
	"github.com/trainticket/greenfield/services/supplier-catalog/internal/application"
	"github.com/trainticket/greenfield/services/supplier-catalog/internal/domain"
	apphttp "github.com/trainticket/greenfield/services/supplier-catalog/internal/http"
)

func main() {
	shutdownOTel, err := goruntime.InitTelemetryFromEnv(context.Background(), "supplier-catalog")
	if err != nil {
		log.Fatalf("failed to initialize OpenTelemetry: %v", err)
	}
	defer func() { _ = shutdownOTel(context.Background()) }()
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
	migrationsDir := os.Getenv("MIGRATIONS_DIR")
	if migrationsDir == "" {
		migrationsDir = "/app/migrations"
	}
	migrationsList, err := storage.LoadMigrations(os.DirFS(migrationsDir), ".")
	if err != nil {
		log.Fatal(err)
	}
	runner := storage.NewMigrationRunner(pool)
	if err := runner.Run(ctx, migrationsList); err != nil {
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
	transactor := adapterpg.NewTransactor(pool)
	service := application.NewService(adapterpg.NewOutboxPublisherWithProvider(transactor)).WithRepository(adapterpg.NewRepositoryWithProvider(transactor)).WithUnitOfWork(transactor.Within)
	eventBus, err := messaging.NewRedisRuntimeFromEnv(ctx)
	if err != nil {
		log.Fatal(err)
	}
	defer eventBus.Close()
	if err := eventBus.StartSupplierCatalogSubscriptions(ctx, consumerName(), nil); err != nil {
		log.Fatal(err)
	}
	profile := domain.Profile()
	httpMetrics, err := goruntime.HTTPMetricsFromEnv(profile.ServiceID)
	if err != nil {
		log.Fatalf("failed to build HTTP server metrics: %v", err)
	}
	if _, err := poolMetrics.RegisterPoolMetrics(goruntime.MeterFromEnv(profile.ServiceID)); err != nil {
		log.Fatalf("failed to register pool metrics: %v", err)
	}
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), ReadyCheck: storage.ReadyCheck(pool, runner.Ready), Observer: goruntime.ObserverFromEnv(profile.ServiceID), HTTPMetrics: httpMetrics})
	apphttp.RegisterRoutes(router, service, storage.NewIdempotencyStore(pool))
	server := goruntime.NewHTTPServer(goruntime.ServerConfig{Address: ":" + port, Handler: router})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}

func consumerName() string {
	if hostname, err := os.Hostname(); err == nil && hostname != "" {
		return "supplier-catalog-" + hostname
	}
	return "supplier-catalog-local"
}
