package messaging

import (
	"errors"
	"fmt"
	"os"
	"strings"

	"github.com/redis/go-redis/v9"
)

const DefaultRedisURL = "redis://localhost:6379"

func RedisURLFromEnv() string {
	url := strings.TrimSpace(os.Getenv("REDIS_URL"))
	if url == "" {
		return DefaultRedisURL
	}
	return url
}

func RedisOptionsFromEnv() (*redis.Options, error) {
	url := RedisURLFromEnv()
	options, err := redis.ParseURL(url)
	if err != nil {
		return nil, fmt.Errorf("invalid REDIS_URL (credentials redacted): %w", errors.New(sanitizeRedisParseError(err)))
	}
	return options, nil
}

func NewRedisClientFromEnv() (*redis.Client, error) {
	options, err := RedisOptionsFromEnv()
	if err != nil {
		return nil, err
	}
	return redis.NewClient(options), nil
}

func sanitizeRedisParseError(error) string {
	return "parse failed"
}
