package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	kitstorage "github.com/trainticket/greenfield/platform/go-kit/storage"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/adapters/api"
	seatmsg "github.com/trainticket/greenfield/services/seat-assignment/internal/adapters/messaging"
	seatpg "github.com/trainticket/greenfield/services/seat-assignment/internal/adapters/storage"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/application"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

func main() {
	shutdown, err := goruntime.InitOTelSDKFromEnv(context.Background(), "seat-assignment")
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
	pool, err := kitstorage.NewPool(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		log.Fatal(err)
	}
	defer pool.Close()
	dir := os.Getenv("MIGRATIONS_DIR")
	if dir == "" {
		dir = "/app/migrations"
	}
	migs, err := kitstorage.LoadMigrations(os.DirFS(dir), ".")
	if err != nil {
		log.Fatal(err)
	}
	runner := kitstorage.NewMigrationRunner(pool)
	if err := runner.Run(ctx, migs); err != nil {
		log.Fatal(err)
	}
	redis, err := kitmsg.NewRedisClient(os.Getenv("REDIS_URL"))
	if err != nil {
		log.Fatal(err)
	}
	if err := redis.Ping(ctx).Err(); err != nil {
		_ = redis.Close()
		log.Fatal(err)
	}
	defer func() { _ = redis.Close() }()
	go kitstorage.NewOutboxRelay(pool, redis).Run(ctx)
	tx := seatpg.NewTransactor(pool)
	svc := application.New(seatpg.NewRepository(tx), seatpg.NewOutboxPublisher(tx), tx.Within)
	if sub, err := seatmsg.NewRedisSubscriber(os.Getenv("REDIS_URL")); err == nil {
		defer func() { _ = sub.Close() }()
		consumer := os.Getenv("HOSTNAME")
		if consumer == "" {
			consumer = "seat-assignment-local"
		}
		if err := sub.Subscribe(ctx, consumer, func(eventCtx context.Context, env kitmsg.EventEnvelope) error {
			return tx.Within(eventCtx, func(txCtx context.Context) error { return svc.HandleSubscribedEvent(txCtx, env) })
		}); err != nil {
			log.Fatal(err)
		}
	} else {
		log.Fatal(err)
	}
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), ReadyCheck: kitstorage.ReadyCheck(pool, runner.Ready), Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	var store idempotency.Store = kitstorage.NewIdempotencyStore(pool)
	api.NewHandler(svc, store).RegisterRoutes(router)
	server := goruntime.NewHTTPServer(goruntime.ServerConfig{Address: ":" + port, Handler: router})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}
