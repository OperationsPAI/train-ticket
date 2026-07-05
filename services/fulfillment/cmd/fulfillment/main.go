package main

import (
	"context"
	"log"
	"os"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/fulfillment/internal/adapters/messaging"
	"github.com/trainticket/greenfield/services/fulfillment/internal/application"
	apphttp "github.com/trainticket/greenfield/services/fulfillment/internal/http"
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	redisURL := os.Getenv("REDIS_URL")
	publisher, err := messaging.NewPublisherFromURL(redisURL)
	if err != nil {
		log.Fatal(err)
	}
	repo := application.NewInMemoryRepository()
	consumed := application.NewInMemoryConsumedEventLog()
	service := application.NewService(repo, publisher, consumed, nil, nil)
	if subscriber, err := messaging.NewSubscriberFromURL(redisURL); err == nil {
		consumer := os.Getenv("FULFILLMENT_CONSUMER_NAME")
		if consumer == "" {
			consumer = "fulfillment-local"
		}
		if err := messaging.SubscribeFulfillment(context.Background(), subscriber, consumer, service.HandleSubscribedEvent); err != nil {
			log.Fatal(err)
		}
	} else {
		log.Fatal(err)
	}
	server := goruntime.NewHTTPServer(goruntime.ServerConfig{
		Address: ":" + port,
		Handler: apphttp.RouterWithService(service),
	})
	if err := server.ListenAndServe(); err != nil {
		log.Fatal(err)
	}
}
