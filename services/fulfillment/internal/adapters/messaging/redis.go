package messaging

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/trainticket/greenfield/services/fulfillment/internal/application"
)

const (
	envelopeField   = "envelope"
	streamPrefix    = "events:"
	consumerGroup   = "fulfillment"
	maxLen          = 100000
	maxAttempts     = 5
	readBlock       = 2 * time.Second
	recoveryMinIdle = 60 * time.Second
	recoveryEvery   = 60 * time.Second
)

type RedisPublisher struct{ client *redis.Client }

func NewRedisPublisher(client *redis.Client) *RedisPublisher { return &RedisPublisher{client: client} }

func (p *RedisPublisher) Publish(ctx context.Context, envelope application.EventEnvelope) error {
	if p == nil || p.client == nil {
		return fmt.Errorf("%w: redis client is required", application.ErrPublishFailed)
	}
	stream := streamForProducer(envelope.Producer)
	bytes, err := json.Marshal(envelope)
	if err != nil {
		return err
	}
	var last error
	for attempt := 0; attempt < 3; attempt++ {
		if attempt > 0 {
			time.Sleep(time.Duration(attempt*attempt) * 50 * time.Millisecond)
		}
		last = p.client.XAdd(ctx, &redis.XAddArgs{Stream: stream, MaxLen: maxLen, Approx: true, Values: map[string]any{envelopeField: string(bytes)}}).Err()
		if last == nil {
			return nil
		}
	}
	return fmt.Errorf("%w: %v", application.ErrPublishFailed, last)
}

type RedisSubscriber struct{ client *redis.Client }

func NewRedisSubscriber(client *redis.Client) *RedisSubscriber {
	return &RedisSubscriber{client: client}
}

func (s *RedisSubscriber) Subscribe(ctx context.Context, streams []string, group string, consumerName string, handler application.EventHandler) error {
	if s == nil || s.client == nil {
		return fmt.Errorf("%w: redis client is required", application.ErrSubscribeFailed)
	}
	if strings.TrimSpace(group) == "" {
		group = consumerGroup
	}
	if strings.TrimSpace(consumerName) == "" {
		consumerName = group + "-instance"
	}
	if handler == nil {
		return fmt.Errorf("%w: handler is required", application.ErrSubscribeFailed)
	}
	for _, stream := range streams {
		if err := s.createGroup(ctx, stream, group); err != nil {
			return err
		}
	}
	go s.recoverLoop(ctx, streams, group, consumerName, handler)
	go s.readLoop(ctx, streams, group, consumerName, handler)
	return nil
}

func (s *RedisSubscriber) createGroup(ctx context.Context, stream, group string) error {
	err := s.client.XGroupCreateMkStream(ctx, stream, group, "$").Err()
	if err != nil && !strings.Contains(strings.ToLower(err.Error()), "busygroup") {
		return fmt.Errorf("%w: %v", application.ErrSubscribeFailed, err)
	}
	return nil
}

func (s *RedisSubscriber) readLoop(ctx context.Context, streams []string, group, consumer string, handler application.EventHandler) {
	for ctx.Err() == nil {
		args := &redis.XReadGroupArgs{Group: group, Consumer: consumer, Streams: append(append([]string{}, streams...), repeat(">", len(streams))...), Count: 10, Block: readBlock}
		messages, err := s.client.XReadGroup(ctx, args).Result()
		if err != nil {
			if errors.Is(err, redis.Nil) {
				continue
			}
			continue
		}
		s.processStreams(ctx, messages, group, handler)
	}
}

func (s *RedisSubscriber) recoverLoop(ctx context.Context, streams []string, group, consumer string, handler application.EventHandler) {
	ticker := time.NewTicker(recoveryEvery)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		for _, stream := range streams {
			claimed, _, err := s.client.XAutoClaim(ctx, &redis.XAutoClaimArgs{Stream: stream, Group: group, Consumer: consumer, MinIdle: recoveryMinIdle, Start: "0-0", Count: 100}).Result()
			if err == nil {
				s.processStreams(ctx, []redis.XStream{{Stream: stream, Messages: claimed}}, group, handler)
			}
		}
	}
}

func (s *RedisSubscriber) processStreams(ctx context.Context, streams []redis.XStream, group string, handler application.EventHandler) {
	for _, stream := range streams {
		for _, message := range stream.Messages {
			payload, _ := message.Values[envelopeField].(string)
			if payload == "" {
				s.toDLQAndAck(ctx, stream.Stream, group, message.ID, payload)
				continue
			}
			attempts := s.deliveryCount(ctx, stream.Stream, group, message.ID)
			if attempts >= maxAttempts {
				s.toDLQAndAck(ctx, stream.Stream, group, message.ID, payload)
				continue
			}
			var envelope application.EventEnvelope
			if err := json.Unmarshal([]byte(payload), &envelope); err != nil {
				s.toDLQAndAck(ctx, stream.Stream, group, message.ID, payload)
				continue
			}
			err := handler(ctx, envelope)
			switch {
			case err == nil:
				_ = s.client.XAck(ctx, stream.Stream, group, message.ID).Err()
			case errors.Is(err, application.ErrFatalHandler):
				s.toDLQAndAck(ctx, stream.Stream, group, message.ID, payload)
			default:
				// transient: leave pending for XAUTOCLAIM
			}
		}
	}
}

func (s *RedisSubscriber) deliveryCount(ctx context.Context, stream, group, id string) int64 {
	entries, err := s.client.XPendingExt(ctx, &redis.XPendingExtArgs{Stream: stream, Group: group, Start: id, End: id, Count: 1}).Result()
	if err != nil || len(entries) == 0 {
		return 1
	}
	return entries[0].RetryCount
}

func (s *RedisSubscriber) toDLQAndAck(ctx context.Context, stream, group, id, envelope string) {
	_ = s.client.XAdd(ctx, &redis.XAddArgs{Stream: stream + ":dlq", MaxLen: maxLen, Approx: true, Values: map[string]any{envelopeField: envelope}}).Err()
	_ = s.client.XAck(ctx, stream, group, id).Err()
}

func streamForProducer(producer string) string { return streamPrefix + producer }
func FulfillmentSubscriptions() []string       { return []string{streamPrefix + "entitlement-ticketing"} }
func FulfillmentGroup() string                 { return consumerGroup }

func repeat(value string, count int) []string {
	out := make([]string, count)
	for i := range out {
		out[i] = value
	}
	return out
}

func ParseRedisOptions(redisURL string) (*redis.Options, error) {
	if strings.TrimSpace(redisURL) == "" {
		redisURL = "redis://localhost:6379"
	}
	return redis.ParseURL(redisURL)
}

func NewPublisherFromURL(redisURL string) (application.EventPublisher, error) {
	options, err := ParseRedisOptions(redisURL)
	if err != nil {
		return nil, err
	}
	return NewRedisPublisher(redis.NewClient(options)), nil
}

func NewSubscriberFromURL(redisURL string) (application.EventSubscriber, error) {
	options, err := ParseRedisOptions(redisURL)
	if err != nil {
		return nil, err
	}
	return NewRedisSubscriber(redis.NewClient(options)), nil
}

func SubscribeFulfillment(ctx context.Context, subscriber application.EventSubscriber, consumerName string, handler application.EventHandler) error {
	return subscriber.Subscribe(ctx, FulfillmentSubscriptions(), FulfillmentGroup(), consumerName, handler)
}
