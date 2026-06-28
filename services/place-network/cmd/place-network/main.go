package main

import (
	"log"
	"os"

	apphttp "github.com/trainticket/greenfield/services/place-network/internal/http"
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	if err := apphttp.Router().Run(":" + port); err != nil {
		log.Fatal(err)
	}
}
