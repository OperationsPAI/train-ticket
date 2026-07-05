package messaging

import (
	"context"
	"strings"
	"testing"

	"github.com/redis/go-redis/v9"

	"github.com/trainticket/greenfield/services/supplier-catalog/internal/application"
)

type fakeStreamClient struct {
	messages []redis.XMessage
	acks     []string
	dlqAdds  int
}

func (f *fakeStreamClient) XGroupCreateMkStream(context.Context, string, string, string) *redis.StatusCmd {
	return redis.NewStatusResult("OK", nil)
}

func (f *fakeStreamClient) XReadGroup(context.Context, *redis.XReadGroupArgs) *redis.XStreamSliceCmd {
	return redis.NewXStreamSliceCmdResult(nil, redis.Nil)
}

func (f *fakeStreamClient) XAutoClaim(ctx context.Context, _ *redis.XAutoClaimArgs) *redis.XAutoClaimCmd {
	cmd := redis.NewXAutoClaimCmd(ctx)
	cmd.SetVal(nil, "0-0")
	return cmd
}

func (f *fakeStreamClient) XPendingExt(ctx context.Context, _ *redis.XPendingExtArgs) *redis.XPendingExtCmd {
	cmd := redis.NewXPendingExtCmd(ctx)
	cmd.SetVal(nil)
	return cmd
}

func (f *fakeStreamClient) XAdd(_ context.Context, args *redis.XAddArgs) *redis.StringCmd {
	if strings.HasSuffix(args.Stream, ":dlq") {
		f.dlqAdds++
	}
	return redis.NewStringResult("1-0", nil)
}

func (f *fakeStreamClient) XAck(_ context.Context, _ string, _ string, ids ...string) *redis.IntCmd {
	f.acks = append(f.acks, ids...)
	return redis.NewIntResult(int64(len(ids)), nil)
}

func TestSubscriberDedupsDuplicateEventIDBeforeHandling(t *testing.T) {
	raw := `{"eventId":"evt-1","eventType":"SupplierRegistered","occurredAt":"2026-07-05T00:00:00.000Z","correlationId":"corr-1","causationId":"cmd-1","producer":"supplier-catalog","schemaVersion":1,"payload":{"supplierId":"sup-1"}}`
	client := &fakeStreamClient{}
	subscriber := NewRedisSubscriber(client, NewDedupLog())
	stream := redis.XStream{Stream: "events:upstream", Messages: []redis.XMessage{
		{ID: "1-0", Values: map[string]interface{}{"envelope": raw}},
		{ID: "2-0", Values: map[string]interface{}{"envelope": raw}},
	}}
	calls := 0
	subscriber.handleStreams(context.Background(), []redis.XStream{stream}, "supplier-catalog", func(context.Context, application.EventEnvelope) error {
		calls++
		return nil
	}, nil)
	if calls != 1 {
		t.Fatalf("expected one handler call, got %d", calls)
	}
	if len(client.acks) != 2 {
		t.Fatalf("expected both messages acked, got %v", client.acks)
	}
}

func TestSubscriberMovesPoisonMessageToDLQByDeliveryCount(t *testing.T) {
	raw := `{"eventId":"evt-poison","eventType":"SupplierRegistered","occurredAt":"2026-07-05T00:00:00.000Z","correlationId":"corr-1","causationId":"cmd-1","producer":"supplier-catalog","schemaVersion":1,"payload":{"supplierId":"sup-1"}}`
	client := &fakeStreamClient{}
	subscriber := NewRedisSubscriber(client, NewDedupLog())
	stream := redis.XStream{Stream: "events:upstream", Messages: []redis.XMessage{{ID: "1-0", Values: map[string]interface{}{"envelope": raw}}}}
	called := false
	subscriber.handleStreams(context.Background(), []redis.XStream{stream}, "supplier-catalog", func(context.Context, application.EventEnvelope) error {
		called = true
		return nil
	}, map[string]int64{"1-0": maxAttempts})
	if called {
		t.Fatal("poison message should not call handler")
	}
	if client.dlqAdds != 1 || len(client.acks) != 1 {
		t.Fatalf("expected dlq and ack, got dlq=%d acks=%v", client.dlqAdds, client.acks)
	}
}
