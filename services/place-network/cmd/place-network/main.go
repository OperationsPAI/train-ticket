package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	kitmessaging "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	adapterpg "github.com/trainticket/greenfield/services/place-network/internal/adapters/postgres"
	"github.com/trainticket/greenfield/services/place-network/internal/application"
	"github.com/trainticket/greenfield/services/place-network/internal/domain"
	apphttp "github.com/trainticket/greenfield/services/place-network/internal/http"
)

func main() {
	shutdownOTel, err := goruntime.InitOTelSDKFromEnv(context.Background(), "place-network")
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

	transactor := adapterpg.NewTransactor(pool)
	service := application.NewService(application.ServiceConfig{
		Places:     adapterpg.NewPlaceRepositoryWithProvider(transactor),
		Nodes:      adapterpg.NewTransportNodeRepositoryWithProvider(transactor),
		Publisher:  adapterpg.NewOutboxPublisherWithProvider(transactor),
		Clock:      domain.RealClock{},
		UnitOfWork: transactor.Within,
	})
	var idempotencyStore idempotency.Store = storage.NewIdempotencyStore(pool)

	// place-network is a phase-1 event producer only; messaging.md assigns no
	// Redis Streams subscriptions to the place-network consumer group.

	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), ReadyCheck: storage.ReadyCheck(pool, runner.Ready), Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	apphttp.NewHandler(service, idempotencyStore).RegisterRoutes(router)

	server := goruntime.NewHTTPServer(goruntime.ServerConfig{Address: ":" + port, Handler: router})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}
