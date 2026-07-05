package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/supplier-catalog/internal/adapters/messaging"
	"github.com/trainticket/greenfield/services/supplier-catalog/internal/application"
	apphttp "github.com/trainticket/greenfield/services/supplier-catalog/internal/http"
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

	service := application.NewService(eventBus.Publisher())
	if err := eventBus.StartSupplierCatalogSubscriptions(ctx, consumerName(), nil); err != nil {
		log.Fatal(err)
	}

	server := goruntime.NewHTTPServer(goruntime.ServerConfig{
		Address: ":" + port,
		Handler: apphttp.RouterWithService(service),
	})
	if err := goruntime.RunHTTPServer(ctx, server); err != nil {
		log.Fatal(err)
	}
}

func consumerName() string {
	if hostname, err := os.Hostname(); err == nil && hostname != "" {
		return "supplier-catalog-" + hostname
	}
	return "supplier-catalog-local"
}
