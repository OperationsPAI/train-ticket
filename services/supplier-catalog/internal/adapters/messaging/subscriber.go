package messaging

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/trainticket/greenfield/services/supplier-catalog/internal/application"
)

const (
	envelopeField = "envelope"
	readCount     = 10
	blockFor      = 2 * time.Second
	minIdle       = time.Minute
	maxAttempts   = 5
)

type streamClient interface {
	XGroupCreateMkStream(ctx context.Context, stream, group, start string) *redis.StatusCmd
	XReadGroup(ctx context.Context, a *redis.XReadGroupArgs) *redis.XStreamSliceCmd
	XAutoClaim(ctx context.Context, a *redis.XAutoClaimArgs) *redis.XAutoClaimCmd
	XPendingExt(ctx context.Context, a *redis.XPendingExtArgs) *redis.XPendingExtCmd
	XAdd(ctx context.Context, a *redis.XAddArgs) *redis.StringCmd
	XAck(ctx context.Context, stream, group string, ids ...string) *redis.IntCmd
}

type RedisSubscriber struct {
	client streamClient
	dedup  *DedupLog
}

func NewRedisSubscriber(client streamClient, dedup *DedupLog) *RedisSubscriber {
	if dedup == nil {
		dedup = NewDedupLog()
	}
	return &RedisSubscriber{client: client, dedup: dedup}
}

func (s *RedisSubscriber) Subscribe(ctx context.Context, streams []string, group string, consumerName string, handler application.EventHandler) error {
	if len(streams) == 0 || strings.TrimSpace(group) == "" || strings.TrimSpace(consumerName) == "" || handler == nil {
		return fmt.Errorf("subscribe requires streams, group, consumer name, and handler")
	}
	for _, stream := range streams {
		if err := s.client.XGroupCreateMkStream(ctx, stream, group, "$").Err(); err != nil && !strings.Contains(strings.ToUpper(err.Error()), "BUSYGROUP") {
			return fmt.Errorf("create group for %s: %w", stream, err)
		}
	}
	go s.poll(ctx, streams, group, consumerName, handler)
	go s.recoverPending(ctx, streams, group, consumerName, handler)
	return nil
}

func (s *RedisSubscriber) poll(ctx context.Context, streams []string, group, consumer string, handler application.EventHandler) {
	readStreams := append([]string{}, streams...)
	for range streams {
		readStreams = append(readStreams, ">")
	}
	for ctx.Err() == nil {
		result, err := s.client.XReadGroup(ctx, &redis.XReadGroupArgs{Group: group, Consumer: consumer, Streams: readStreams, Count: readCount, Block: blockFor}).Result()
		if err != nil {
			if errors.Is(err, redis.Nil) || errors.Is(err, context.Canceled) {
				continue
			}
			continue
		}
		s.handleStreams(ctx, result, group, handler, nil)
	}
}

func (s *RedisSubscriber) recoverPending(ctx context.Context, streams []string, group, consumer string, handler application.EventHandler) {
	ticker := time.NewTicker(minIdle)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			for _, stream := range streams {
				claimed, _, err := s.client.XAutoClaim(ctx, &redis.XAutoClaimArgs{Stream: stream, Group: group, Consumer: consumer, MinIdle: minIdle, Start: "0-0", Count: 100}).Result()
				if err != nil {
					continue
				}
				counts := s.deliveryCounts(ctx, stream, group)
				s.handleStreams(ctx, []redis.XStream{{Stream: stream, Messages: claimed}}, group, handler, counts)
			}
		}
	}
}

func (s *RedisSubscriber) handleStreams(ctx context.Context, streams []redis.XStream, group string, handler application.EventHandler, deliveryCounts map[string]int64) {
	for _, stream := range streams {
		for _, message := range stream.Messages {
			raw, ok := message.Values[envelopeField].(string)
			if !ok {
				s.moveToDLQAndAck(ctx, stream.Stream, group, message.ID, "")
				continue
			}
			if deliveryCounts != nil && deliveryCounts[message.ID] >= maxAttempts {
				s.moveToDLQAndAck(ctx, stream.Stream, group, message.ID, raw)
				continue
			}
			var envelope application.EventEnvelope
			if err := json.Unmarshal([]byte(raw), &envelope); err != nil {
				s.moveToDLQAndAck(ctx, stream.Stream, group, message.ID, raw)
				continue
			}
			if !s.dedup.TryStart(envelope.EventID) {
				s.client.XAck(ctx, stream.Stream, group, message.ID)
				continue
			}
			err := handler(ctx, envelope)
			if err == nil {
				s.dedup.MarkProcessed(envelope.EventID)
				s.client.XAck(ctx, stream.Stream, group, message.ID)
				continue
			}
			s.dedup.Forget(envelope.EventID)
			var handlerErr application.HandlerError
			if errors.As(err, &handlerErr) && handlerErr.Kind == application.HandlerErrorFatal {
				s.moveToDLQAndAck(ctx, stream.Stream, group, message.ID, raw)
			}
		}
	}
}

func (s *RedisSubscriber) deliveryCounts(ctx context.Context, stream, group string) map[string]int64 {
	counts := make(map[string]int64)
	pending, err := s.client.XPendingExt(ctx, &redis.XPendingExtArgs{Stream: stream, Group: group, Start: "-", End: "+", Count: 1000}).Result()
	if err != nil {
		return counts
	}
	for _, item := range pending {
		counts[item.ID] = item.RetryCount
	}
	return counts
}

func (s *RedisSubscriber) moveToDLQAndAck(ctx context.Context, stream, group, id, raw string) {
	values := map[string]interface{}{envelopeField: raw}
	s.client.XAdd(ctx, &redis.XAddArgs{Stream: stream + ":dlq", MaxLen: maxLen, Approx: true, Values: values})
	s.client.XAck(ctx, stream, group, id)
}

type DedupLog struct {
	mu        sync.Mutex
	processed map[string]struct{}
	inflight  map[string]struct{}
}

func NewDedupLog() *DedupLog {
	return &DedupLog{processed: make(map[string]struct{}), inflight: make(map[string]struct{})}
}

func (d *DedupLog) TryStart(eventID string) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	if _, ok := d.processed[eventID]; ok {
		return false
	}
	if _, ok := d.inflight[eventID]; ok {
		return false
	}
	d.inflight[eventID] = struct{}{}
	return true
}

func (d *DedupLog) MarkProcessed(eventID string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	delete(d.inflight, eventID)
	d.processed[eventID] = struct{}{}
}

func (d *DedupLog) Forget(eventID string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	delete(d.inflight, eventID)
}
