package messaging

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
)

const (
	ProviderIntegrationGroup   = "provider-integration"
	streamBookingOrchestration = "events:booking-orchestration"
	streamSupplierCatalog      = "events:supplier-catalog"
	minIdle                    = time.Minute
	maxAttempts                = 5
)

var subscribedStreams = []string{streamBookingOrchestration, streamSupplierCatalog}

type RedisSubscriber struct {
	client  *redis.Client
	streams []string
}

func NewRedisSubscriber(redisURL string) (*RedisSubscriber, error) {
	options, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, fmt.Errorf("parse redis url: %w", err)
	}
	return &RedisSubscriber{client: redis.NewClient(options), streams: append([]string(nil), subscribedStreams...)}, nil
}

func (s *RedisSubscriber) Close() error {
	if s == nil || s.client == nil {
		return nil
	}
	return s.client.Close()
}

func (s *RedisSubscriber) Subscribe(ctx context.Context, streams []string, group string, consumerName string, handler application.EventHandler) error {
	if s == nil || s.client == nil {
		return fmt.Errorf("%w: redis client is not configured", application.ErrSubscribeFailed)
	}
	if len(streams) > 0 {
		s.streams = append([]string(nil), streams...)
	}
	if strings.TrimSpace(group) == "" {
		group = ProviderIntegrationGroup
	}
	if strings.TrimSpace(consumerName) == "" {
		consumerName = ProviderIntegrationGroup + "-instance"
	}
	for _, stream := range s.streams {
		if err := s.client.XGroupCreateMkStream(ctx, stream, group, "$").Err(); err != nil && !strings.Contains(strings.ToUpper(err.Error()), "BUSYGROUP") {
			return fmt.Errorf("%w: create consumer group: %v", application.ErrSubscribeFailed, err)
		}
	}
	go s.poll(ctx, group, consumerName, handler)
	go s.recover(ctx, group, consumerName, handler)
	return nil
}

func (s *RedisSubscriber) poll(ctx context.Context, group, consumerName string, handler application.EventHandler) {
	for ctx.Err() == nil {
		streams := append([]string{}, s.streams...)
		for range s.streams {
			streams = append(streams, ">")
		}
		result, err := s.client.XReadGroup(ctx, &redis.XReadGroupArgs{Group: group, Consumer: consumerName, Streams: streams, Count: 10, Block: 2 * time.Second}).Result()
		if err != nil {
			if errors.Is(err, redis.Nil) || ctx.Err() != nil {
				continue
			}
			continue
		}
		for _, stream := range result {
			for _, message := range stream.Messages {
				s.handleMessage(ctx, stream.Stream, group, message, handler, 0)
			}
		}
	}
}

func (s *RedisSubscriber) recover(ctx context.Context, group, consumerName string, handler application.EventHandler) {
	ticker := time.NewTicker(minIdle)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			for _, stream := range s.streams {
				start := "0"
				for ctx.Err() == nil {
					claimed, next, err := s.client.XAutoClaim(ctx, &redis.XAutoClaimArgs{Stream: stream, Group: group, Consumer: consumerName, MinIdle: minIdle, Start: start, Count: 100}).Result()
					if err != nil || len(claimed) == 0 {
						break
					}
					for _, message := range claimed {
						s.handleMessage(ctx, stream, group, message, handler, s.deliveryCount(ctx, stream, group, message.ID))
					}
					start = next
				}
			}
		}
	}
}

func (s *RedisSubscriber) handleMessage(ctx context.Context, stream string, group string, message redis.XMessage, handler application.EventHandler, attempts int64) {
	raw, _ := message.Values[envelopeField].(string)
	if raw == "" {
		s.moveToDLQAndAck(ctx, stream, group, message.ID, raw)
		return
	}
	if attempts >= maxAttempts {
		s.moveToDLQAndAck(ctx, stream, group, message.ID, raw)
		return
	}
	var envelope application.EventEnvelope
	if err := json.Unmarshal([]byte(raw), &envelope); err != nil {
		s.moveToDLQAndAck(ctx, stream, group, message.ID, raw)
		return
	}
	if err := handler(ctx, envelope); err != nil {
		var handlerErr application.HandlerError
		if errors.As(err, &handlerErr) && handlerErr.Kind == application.HandlerErrorFatal {
			s.moveToDLQAndAck(ctx, stream, group, message.ID, raw)
		}
		return
	}
	_ = s.client.XAck(ctx, stream, group, message.ID).Err()
}

func (s *RedisSubscriber) moveToDLQAndAck(ctx context.Context, stream, group, messageID, raw string) {
	_ = s.client.XAdd(ctx, &redis.XAddArgs{Stream: stream + ":dlq", MaxLen: maxLen, Approx: true, Values: map[string]any{envelopeField: raw}}).Err()
	_ = s.client.XAck(ctx, stream, group, messageID).Err()
}

func (s *RedisSubscriber) deliveryCount(ctx context.Context, stream, group, messageID string) int64 {
	pending, err := s.client.XPendingExt(ctx, &redis.XPendingExtArgs{Stream: stream, Group: group, Start: messageID, End: messageID, Count: 1}).Result()
	if err != nil || len(pending) == 0 {
		return 0
	}
	return pending[0].RetryCount
}
