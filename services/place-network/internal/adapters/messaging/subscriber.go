package messaging

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/trainticket/greenfield/services/place-network/internal/application"
	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type RedisSubscriber struct {
	client   *redis.Client
	consumed map[string]struct{}
	mu       sync.RWMutex
	stopCh   chan struct{}
	wg       sync.WaitGroup
	once     sync.Once
}

func NewRedisSubscriber(client *redis.Client) *RedisSubscriber {
	return &RedisSubscriber{client: client, consumed: make(map[string]struct{}), stopCh: make(chan struct{})}
}

func (s *RedisSubscriber) Subscribe(streams []string, group string, consumerName string, handler application.EventHandler) error {
	ctx := context.Background()
	for _, stream := range streams {
		err := s.client.XGroupCreateMkStream(ctx, stream, group, "$").Err()
		if err != nil && !strings.Contains(err.Error(), "BUSYGROUP") {
			return fmt.Errorf("subscribe failed: %w", err)
		}
	}
	s.wg.Add(2)
	go func() {
		defer s.wg.Done()
		s.pollLoop(streams, group, consumerName, handler)
	}()
	go func() {
		defer s.wg.Done()
		s.recoveryLoop(streams, group, consumerName, handler)
	}()
	return nil
}

func (s *RedisSubscriber) Stop() {
	s.once.Do(func() { close(s.stopCh) })
	s.wg.Wait()
}

func (s *RedisSubscriber) pollLoop(streams []string, group, consumerName string, handler application.EventHandler) {
	for {
		select {
		case <-s.stopCh:
			return
		default:
		}
		streamArgs := append(append([]string{}, streams...), repeated(">", len(streams))...)
		results, err := s.client.XReadGroup(context.Background(), &redis.XReadGroupArgs{Group: group, Consumer: consumerName, Streams: streamArgs, Count: 10, Block: 2 * time.Second}).Result()
		if err != nil {
			if errors.Is(err, redis.Nil) {
				continue
			}
			log.Printf("place-network subscriber read error: %v", err)
			time.Sleep(500 * time.Millisecond)
			continue
		}
		for _, stream := range results {
			for _, msg := range stream.Messages {
				s.processMessage(stream.Stream, group, msg, handler)
			}
		}
	}
}

func (s *RedisSubscriber) recoveryLoop(streams []string, group, consumerName string, handler application.EventHandler) {
	ticker := time.NewTicker(ClaimMinIdle)
	defer ticker.Stop()
	for {
		select {
		case <-s.stopCh:
			return
		case <-ticker.C:
			for _, stream := range streams {
				msgs, _, err := s.client.XAutoClaim(context.Background(), &redis.XAutoClaimArgs{Stream: stream, Group: group, Consumer: consumerName, MinIdle: ClaimMinIdle, Start: "0", Count: 100}).Result()
				if err != nil {
					log.Printf("place-network subscriber recovery error: %v", err)
					continue
				}
				for _, msg := range msgs {
					s.processMessage(stream, group, msg, handler)
				}
			}
		}
	}
}

func (s *RedisSubscriber) processMessage(stream, group string, msg redis.XMessage, handler application.EventHandler) {
	ctx := context.Background()
	envelopeJSON, ok := msg.Values["envelope"].(string)
	if !ok {
		_, _ = s.client.XAck(ctx, stream, group, msg.ID).Result()
		return
	}
	if s.deliveryAttempts(ctx, stream, group, msg.ID) >= MaxDeliveryAttempts {
		s.moveToDLQ(ctx, stream, envelopeJSON)
		_, _ = s.client.XAck(ctx, stream, group, msg.ID).Result()
		return
	}
	var envelope domain.EventEnvelope
	if err := json.Unmarshal([]byte(envelopeJSON), &envelope); err != nil {
		s.moveToDLQ(ctx, stream, envelopeJSON)
		_, _ = s.client.XAck(ctx, stream, group, msg.ID).Result()
		return
	}
	if s.isConsumed(envelope.EventID) {
		_, _ = s.client.XAck(ctx, stream, group, msg.ID).Result()
		return
	}
	switch handler(envelope) {
	case application.HandlerSuccess:
		s.markConsumed(envelope.EventID)
		_, _ = s.client.XAck(ctx, stream, group, msg.ID).Result()
	case application.HandlerFatalError:
		s.moveToDLQ(ctx, stream, envelopeJSON)
		_, _ = s.client.XAck(ctx, stream, group, msg.ID).Result()
	}
}

func (s *RedisSubscriber) deliveryAttempts(ctx context.Context, stream, group, id string) int64 {
	entries, err := s.client.XPendingExt(ctx, &redis.XPendingExtArgs{Stream: stream, Group: group, Start: id, End: id, Count: 1}).Result()
	return pendingRetryCount(entries, err)
}

func pendingRetryCount(entries []redis.XPendingExt, err error) int64 {
	if err != nil || len(entries) == 0 {
		return 0
	}
	return entries[0].RetryCount
}

func (s *RedisSubscriber) moveToDLQ(ctx context.Context, stream, envelopeJSON string) {
	_, err := s.client.XAdd(ctx, &redis.XAddArgs{Stream: stream + ":dlq", MaxLen: MaxLen, Approx: true, Values: map[string]any{"envelope": envelopeJSON}}).Result()
	if err != nil {
		log.Printf("place-network subscriber dlq error: %v", err)
	}
}

func (s *RedisSubscriber) isConsumed(eventID string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	_, exists := s.consumed[eventID]
	return exists
}

func (s *RedisSubscriber) markConsumed(eventID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.consumed[eventID] = struct{}{}
}

func repeated(value string, count int) []string {
	values := make([]string, count)
	for i := range values {
		values[i] = value
	}
	return values
}
