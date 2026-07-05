package messaging

import (
	"os"
	"time"
)

const (
	EnvRedisURL     = "REDIS_URL"
	DefaultRedisURL = "redis://localhost:6379"

	StreamPlaceNetwork    = "events:place-network"
	StreamPlaceNetworkDLQ = "events:place-network:dlq"
	ConsumerGroup         = "place-network"
	MaxLen                = 100000
	MaxDeliveryAttempts   = 5
	ClaimMinIdle          = 60 * time.Second
)

func RedisURL() string {
	if value := os.Getenv(EnvRedisURL); value != "" {
		return value
	}
	return DefaultRedisURL
}
