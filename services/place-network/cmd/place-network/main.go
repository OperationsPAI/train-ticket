package main

import (
	"log"
	"os"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	apphttp "github.com/trainticket/greenfield/services/place-network/internal/http"
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	server := goruntime.NewHTTPServer(goruntime.ServerConfig{
		Address: ":" + port,
		Handler: apphttp.Router(),
	})
	if err := server.ListenAndServe(); err != nil {
		log.Fatal(err)
	}
}
