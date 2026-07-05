package main

import (
	"context"
	"log"
	"os"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/provider-integration/internal/adapters/messaging"
	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
	"github.com/trainticket/greenfield/services/provider-integration/internal/config"
	apphttp "github.com/trainticket/greenfield/services/provider-integration/internal/http"
)

func main() {
	cfg := config.FromEnv()
	ctx := context.Background()
	publisher, err := messaging.NewRedisPublisher(cfg.RedisURL)
	if err != nil {
		log.Fatal(err)
	}
	defer publisher.Close()

	subscriber, err := messaging.NewRedisSubscriber(cfg.RedisURL)
	if err != nil {
		log.Fatal(err)
	}
	defer subscriber.Close()
	service := application.NewInMemoryReservationService(publisher)
	consumedEvents := application.NewInMemoryConsumedEventLog()
	consumerName := "provider-integration-" + hostname()
	if err := subscriber.Subscribe(ctx, nil, messaging.ProviderIntegrationGroup, consumerName, application.DeduplicatingHandler(consumedEvents, application.NewInboundEventHandler(service))); err != nil {
		log.Fatal(err)
	}

	server := goruntime.NewHTTPServer(goruntime.ServerConfig{
		Address: ":" + cfg.HTTPPort,
		Handler: apphttp.RouterWithDependencies(service, application.NewIdempotencyStore()),
	})
	if err := server.ListenAndServe(); err != nil {
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
