package messaging

import (
	"context"
	"fmt"

	"github.com/redis/go-redis/v9"
)

type RedisRuntime struct {
	client    *redis.Client
	publisher *RedisPublisher
}

func NewRedisRuntimeFromEnv(ctx context.Context) (*RedisRuntime, error) {
	options, err := redis.ParseURL(RedisURL())
	if err != nil {
		return nil, fmt.Errorf("invalid REDIS_URL: %w", err)
	}
	client := redis.NewClient(options)
	if err := client.Ping(ctx).Err(); err != nil {
		_ = client.Close()
		return nil, fmt.Errorf("redis connection failed: %w", err)
	}
	return &RedisRuntime{client: client, publisher: NewRedisPublisher(client)}, nil
}

func (r *RedisRuntime) Publisher() *RedisPublisher { return r.publisher }

func (r *RedisRuntime) Close() error {
	return r.client.Close()
}
