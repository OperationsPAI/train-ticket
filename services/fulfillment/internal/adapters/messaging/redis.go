package messaging

import (
	"context"
	"strings"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/fulfillment/internal/application"
)

const (
	streamPrefix  = kitmsg.StreamPrefix
	consumerGroup = "fulfillment"
)

type RedisPublisher = kitmsg.RedisEventBus

type RedisSubscriber struct{ bus *kitmsg.RedisEventBus }

func NewSubscriberFromURL(redisURL string) (application.EventSubscriber, error) {
	bus, err := kitmsg.NewRedisEventBus(redisURL)
	if err != nil {
		return nil, err
	}
	return &RedisSubscriber{bus: bus}, nil
}

func NewPublisherFromURL(redisURL string) (application.EventPublisher, error) {
	return kitmsg.NewRedisEventBus(redisURL)
}

func (s *RedisSubscriber) Subscribe(ctx context.Context, streams []string, group string, consumerName string, handler application.EventHandler) error {
	if strings.TrimSpace(group) == "" {
		group = consumerGroup
	}
	if strings.TrimSpace(consumerName) == "" {
		consumerName = group + "-instance"
	}
	return s.bus.Subscribe(ctx, kitmsg.Subscription{Streams: streams, Group: group, ConsumerName: consumerName}, kitmsg.Handler(handler))
}

func FulfillmentSubscriptions() []string {
	return []string{streamPrefix + "entitlement-ticketing", streamPrefix + "booking-orchestration"}
}
func FulfillmentGroup() string { return consumerGroup }

func SubscribeFulfillment(ctx context.Context, subscriber application.EventSubscriber, consumerName string, handler application.EventHandler) error {
	return subscriber.Subscribe(ctx, FulfillmentSubscriptions(), FulfillmentGroup(), consumerName, handler)
}
