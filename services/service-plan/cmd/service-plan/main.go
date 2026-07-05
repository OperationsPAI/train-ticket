package main

import (
	"log"
	"os"

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
	eventBus, err := messaging.NewRedisEventBus(os.Getenv("REDIS_URL"))
	if err != nil {
		log.Fatal(err)
	}
	defer eventBus.Close()
	server := goruntime.NewHTTPServer(goruntime.ServerConfig{
		Address: ":" + port,
		Handler: apphttp.RouterWithService(application.NewService(eventBus)),
	})
	if err := server.ListenAndServe(); err != nil {
		log.Fatal(err)
	}
}
