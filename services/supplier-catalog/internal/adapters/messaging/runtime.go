package messaging

import (
	"context"
	"fmt"

	"github.com/redis/go-redis/v9"

	"github.com/trainticket/greenfield/services/supplier-catalog/internal/application"
)

// RedisRuntime owns the Redis-backed messaging adapters used by production
// bootstrap. Stream names and consumer-group wiring remain inside this adapter
// package so application and runtime code stay broker-neutral.
type RedisRuntime struct {
	client     *redis.Client
	publisher  *RedisPublisher
	subscriber *RedisSubscriber
}

func NewRedisRuntimeFromEnv(ctx context.Context) (*RedisRuntime, error) {
	client, err := NewRedisClientFromEnv()
	if err != nil {
		return nil, err
	}
	if err := client.Ping(ctx).Err(); err != nil {
		_ = client.Close()
		return nil, fmt.Errorf("redis connection failed: %w", err)
	}
	return &RedisRuntime{client: client, publisher: NewRedisPublisher(client), subscriber: NewRedisSubscriber(client, nil)}, nil
}

func (r *RedisRuntime) Publisher() application.EventPublisher { return r.publisher }

func (r *RedisRuntime) StartSupplierCatalogSubscriptions(ctx context.Context, consumerName string, handler application.EventHandler) error {
	if len(supplierCatalogSubscriptions) == 0 {
		return nil
	}
	return r.subscriber.Subscribe(ctx, supplierCatalogSubscriptions, supplierCatalogConsumerGroup, consumerName, handler)
}

func (r *RedisRuntime) Close() error {
	return r.client.Close()
}

const supplierCatalogConsumerGroup = "supplier-catalog"

// messaging.md currently assigns no upstream streams to the supplier-catalog
// consumer group. Keep the subscription table mapping here, in the Redis
// adapter, so future contract additions do not leak stream keys elsewhere.
var supplierCatalogSubscriptions = []string{}
