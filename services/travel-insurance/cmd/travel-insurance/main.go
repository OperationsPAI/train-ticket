package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	"github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	adapterpg "github.com/trainticket/greenfield/services/travel-insurance/adapters/postgres"
	"github.com/trainticket/greenfield/services/travel-insurance/api"
	"github.com/trainticket/greenfield/services/travel-insurance/application"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

func main() {
	shutdownOTel, err := goruntime.InitOTelSDKFromEnv(context.Background(), "travel-insurance")
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

	var svc *application.InsuranceService
	var ready goruntime.Check
	var idempotencyStore idempotency.Store
	if strings.TrimSpace(os.Getenv("DATABASE_URL")) == "" {
		repo := application.NewInMemoryRepository()
		svc = application.NewInsuranceService(repo, application.NoopPublisher{}, nil, nil)
		if err := svc.EnsureDefaultCatalog(ctx); err != nil {
			log.Fatal(err)
		}
		idempotencyStore = nil
	} else {
		pool, err := storage.NewPool(ctx, os.Getenv("DATABASE_URL"))
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

		redisClient, err := messaging.NewRedisClient(os.Getenv("REDIS_URL"))
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
		repo := adapterpg.NewRepositoryWithProvider(transactor)
		svc = application.NewInsuranceService(repo, adapterpg.NewOutboxPublisherWithProvider(transactor), nil, nil).WithUnitOfWork(transactor.Within)
		if err := svc.EnsureDefaultCatalog(ctx); err != nil {
			log.Fatal(err)
		}

		bus := messaging.NewRedisEventBusWithClient(redisClient, messaging.RedisConfig{})
		if err := application.Subscribe(ctx, bus, svc); err != nil {
			log.Fatal(err)
		}
		defer func() { _ = bus.Close() }()
		ready = storage.ReadyCheck(pool, runner.Ready)
		idempotencyStore = storage.NewIdempotencyStore(pool)
	}

	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), ReadyCheck: ready, Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	api.RegisterRoutes(router, svc, idempotencyStore)
	server := goruntime.NewHTTPServer(goruntime.ServerConfig{Address: ":" + port, Handler: router})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}
