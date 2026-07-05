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

	subscriber := eventBus.Subscriber()
	consumerName := "place-network-" + goruntime.GenerateRequestID()
	dedupHandler := ports.NewDeduplicatingEventHandler(func(envelope domain.EventEnvelope) ports.HandlerResult {
		log.Printf("place-network consumed event type=%s eventId=%s producer=%s", envelope.EventType, envelope.EventID, envelope.Producer)
		return ports.HandlerSuccess
	})
	if err := subscriber.Subscribe([]string{messaging.StreamPlaceNetwork}, messaging.ConsumerGroup, consumerName, dedupHandler.Handle); err != nil {
		log.Fatalf("subscriber start failed: %v", err)
	}
	defer subscriber.Stop()

	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	apphttp.NewHandler(service, idempotency).RegisterRoutes(router)

	server := goruntime.NewHTTPServer(goruntime.ServerConfig{Address: ":" + port, Handler: router})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}
