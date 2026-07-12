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
	appmsg "github.com/trainticket/greenfield/services/travel-insurance/adapters/messaging"
	adapterpg "github.com/trainticket/greenfield/services/travel-insurance/adapters/postgres"
	"github.com/trainticket/greenfield/services/travel-insurance/api"
	"github.com/trainticket/greenfield/services/travel-insurance/application"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

func main() {
	shutdownOTel, err := goruntime.InitOTelSDKFromEnv(context.Background(), domain.ServiceID)
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
	service := application.NewInsuranceService(application.ServiceConfig{Repository: adapterpg.NewRepositoryWithProvider(transactor), Publisher: adapterpg.NewOutboxPublisherWithProvider(transactor), UnitOfWork: transactor.Within})
	bus := kitmessaging.NewRedisEventBusWithClient(redisClient, kitmessaging.RedisConfig{})
	consumer := os.Getenv("CONSUMER_NAME")
	if consumer == "" {
		consumer = domain.ServiceID + "-1"
	}
	if err := bus.Subscribe(ctx, kitmessaging.Subscription{Streams: []string{"journey-order", "payment"}, Group: domain.ServiceID, ConsumerName: consumer}, appmsg.NewInboundHandler(service).Handle); err != nil {
		log.Fatal(err)
	}
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), ReadyCheck: storage.ReadyCheck(pool, runner.Ready), Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	api.RegisterRoutes(router, service, storage.NewIdempotencyStore(pool))
	server := goruntime.NewHTTPServer(goruntime.ServerConfig{Address: ":" + port, Handler: router})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}
