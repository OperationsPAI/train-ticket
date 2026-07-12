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
	"github.com/trainticket/greenfield/services/provider-integration/internal/adapters/messaging"
	adapterpg "github.com/trainticket/greenfield/services/provider-integration/internal/adapters/postgres"
	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
	"github.com/trainticket/greenfield/services/provider-integration/internal/config"
	"github.com/trainticket/greenfield/services/provider-integration/internal/domain"
)

func main() {
	shutdownOTel, err := goruntime.InitOTelSDKFromEnv(context.Background(), "provider-integration")
	if err != nil {
		log.Fatalf("failed to initialize OpenTelemetry: %v", err)
	}
	defer func() { _ = shutdownOTel(context.Background()) }()
	cfg := config.FromEnv()
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
	migrationsList, err := storage.LoadMigrations(os.DirFS(migrationsDir), ".")
	if err != nil {
		log.Fatal(err)
	}
	runner := storage.NewMigrationRunner(pool)
	if err := runner.Run(ctx, migrationsList); err != nil {
		log.Fatal(err)
	}
	redisClient, err := kitmessaging.NewRedisClient(cfg.RedisURL)
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
	publisher := adapterpg.NewOutboxPublisherWithProvider(transactor)
	subscriber, err := messaging.NewRedisSubscriber(cfg.RedisURL)
	if err != nil {
		log.Fatal(err)
	}
	defer subscriber.Close()
	service := adapterpg.NewReservationServiceWithProvider(transactor, publisher)
	consumedEvents := adapterpg.NewProcessedEventsWithProvider(transactor)
	consumerName := "provider-integration-" + hostname()
	if err := subscriber.Subscribe(ctx, nil, messaging.ProviderIntegrationGroup, consumerName, adapterpg.DeduplicatingHandler(consumedEvents, application.NewInboundEventHandler(service))); err != nil {
		log.Fatal(err)
	}
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), ReadyCheck: storage.ReadyCheck(pool, runner.Ready), Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	server := goruntime.NewHTTPServer(goruntime.ServerConfig{Address: ":" + cfg.HTTPPort, Handler: router})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}

func hostname() string {
	name, err := os.Hostname()
	if err != nil || name == "" {
		return "instance"
	}
	return name
}
