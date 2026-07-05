package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/place-network/internal/adapters/messaging"
	"github.com/trainticket/greenfield/services/place-network/internal/application"
	"github.com/trainticket/greenfield/services/place-network/internal/domain"
	"github.com/trainticket/greenfield/services/place-network/internal/domain/ports"
	apphttp "github.com/trainticket/greenfield/services/place-network/internal/http"
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	eventBus, err := messaging.NewRedisRuntimeFromEnv(ctx)
	if err != nil {
		log.Fatal(err)
	}
	defer eventBus.Close()

	idempotency := ports.NewInMemoryIdempotencyStore()
	service := application.NewService(application.ServiceConfig{
		Places:    ports.NewInMemoryPlaceRepository(),
		Nodes:     ports.NewInMemoryTransportNodeRepository(),
		Publisher: eventBus.Publisher(),
		Clock:     domain.RealClock{},
	})

	// place-network is a phase-1 event producer only; messaging.md assigns no
	// Redis Streams subscriptions to the place-network consumer group.

	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	apphttp.NewHandler(service, idempotency).RegisterRoutes(router)

	server := goruntime.NewHTTPServer(goruntime.ServerConfig{Address: ":" + port, Handler: router})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}
