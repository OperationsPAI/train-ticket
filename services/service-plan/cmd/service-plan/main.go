package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	messaging "github.com/trainticket/greenfield/services/service-plan/internal/adapters/messaging"
	"github.com/trainticket/greenfield/services/service-plan/internal/application"
	apphttp "github.com/trainticket/greenfield/services/service-plan/internal/http"
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	eventBus, err := messaging.NewRedisEventBus(os.Getenv("REDIS_URL"))
	if err != nil {
		log.Fatal(err)
	}
	defer eventBus.Close()
	service := application.NewService(eventBus)
	server := goruntime.NewHTTPServer(goruntime.ServerConfig{
		Address: ":" + port,
		Handler: apphttp.RouterWithService(service),
	})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}
