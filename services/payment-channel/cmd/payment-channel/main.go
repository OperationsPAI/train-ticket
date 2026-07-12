package main

import (
	"context"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	kitmessaging "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	adapterpg "github.com/trainticket/greenfield/services/payment-channel/internal/adapters/postgres"
	"github.com/trainticket/greenfield/services/payment-channel/internal/application"
	"github.com/trainticket/greenfield/services/payment-channel/internal/domain"
	apphttp "github.com/trainticket/greenfield/services/payment-channel/internal/http"
	"log"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	shutdown, err := goruntime.InitOTelSDKFromEnv(context.Background(), "payment-channel")
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
	svc := application.New(adapterpg.NewRepository(tx), adapterpg.NewOutboxPublisher(tx), tx.Within)
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), ReadyCheck: storage.ReadyCheck(pool, runner.Ready), Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	var store idempotency.Store = storage.NewIdempotencyStore(pool)
	apphttp.New(svc, store).RegisterRoutes(router)
	server := goruntime.NewHTTPServer(goruntime.ServerConfig{Address: ":" + port, Handler: router})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}
