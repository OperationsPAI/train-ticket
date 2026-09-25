package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	kitmessaging "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	entitlementadapter "github.com/trainticket/greenfield/services/fulfillment/internal/adapters/entitlement"
	"github.com/trainticket/greenfield/services/fulfillment/internal/adapters/messaging"
	"github.com/trainticket/greenfield/services/fulfillment/internal/adapters/observability"
	adapterpg "github.com/trainticket/greenfield/services/fulfillment/internal/adapters/postgres"
	"github.com/trainticket/greenfield/services/fulfillment/internal/application"
	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
	apphttp "github.com/trainticket/greenfield/services/fulfillment/internal/http"
)

func main() {
	shutdownOTel, err := goruntime.InitTelemetryFromEnv(context.Background(), "fulfillment")
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
	repo := adapterpg.NewFulfillmentRepositoryWithProvider(transactor)
	consumed := adapterpg.NewProcessedEventsWithProvider(transactor)
	entitlementURL := os.Getenv("ENTITLEMENT_TICKETING_URL")
	if entitlementURL == "" {
		entitlementURL = "http://entitlement-ticketing:8080"
	}
	entitlementReader := entitlementadapter.NewClient(entitlementURL, &http.Client{Timeout: 5 * time.Second})
	// train_ticket.ticketing.transitions: the validation stage of the ticket
	// funnel. A recorder failure is fatal at startup, like the pool metrics: a
	// missing series must be visible in a crash, not silently absent.
	ticketingMetrics, err := observability.NewOpenTelemetryRecorder(goruntime.MeterFromEnv(domain.Profile().ServiceID))
	if err != nil {
		log.Fatalf("failed to build ticketing transition metrics: %v", err)
	}
	service := application.NewService(repo, adapterpg.NewOutboxPublisherWithProvider(transactor), consumed, nil, nil).
		WithSegmentStatusRepository(repo).
		WithEntitlementReader(entitlementReader).
		WithTicketingMetrics(ticketingMetrics).
		WithUnitOfWork(transactor.Within)

	if subscriber, err := messaging.NewSubscriberFromURL(os.Getenv("REDIS_URL")); err == nil {
		consumer := os.Getenv("FULFILLMENT_CONSUMER_NAME")
		if consumer == "" {
			consumer = "fulfillment-local"
		}
		if err := messaging.SubscribeFulfillment(ctx, subscriber, consumer, func(eventCtx context.Context, envelope application.EventEnvelope) error {
			if !application.HandlesSubscribedEvent(envelope.EventType) {
				return nil
			}
			return transactor.Within(eventCtx, func(txCtx context.Context) error { return service.HandleSubscribedEvent(txCtx, envelope) })
		}); err != nil {
			log.Fatal(err)
		}
	} else {
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
