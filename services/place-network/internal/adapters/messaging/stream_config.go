package messaging

import "os"

const (
	EnvRedisURL     = "REDIS_URL"
	DefaultRedisURL = "redis://localhost:6379"

	StreamPlaceNetwork = "events:place-network"
	MaxLen             = 100000
)

func RedisURL() string {
	if value := os.Getenv(EnvRedisURL); value != "" {
		return value
	}
	return DefaultRedisURL
}
