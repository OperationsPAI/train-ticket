package config

import (
	"os"
	"strings"
)

type Config struct {
	HTTPPort string
	RedisURL string
}

func FromEnv() Config {
	port := strings.TrimSpace(os.Getenv("PORT"))
	if port == "" {
		port = "8080"
	}
	redisURL := strings.TrimSpace(os.Getenv("REDIS_URL"))
	if redisURL == "" {
		redisURL = "redis://localhost:6379"
	}
	return Config{HTTPPort: port, RedisURL: redisURL}
}
