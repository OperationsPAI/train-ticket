package storage

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	miniredis "github.com/alicebob/miniredis/v2"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	redis "github.com/redis/go-redis/v9"
	"github.com/trainticket/greenfield/platform/go-kit/messaging"
)

// oneRowDB returns a single pending outbox row, then reports the batch update as applied.
type oneRowDB struct{}

func (oneRowDB) Exec(context.Context, string, ...any) (pgconn.CommandTag, error) {
	return pgconn.NewCommandTag("UPDATE 1"), nil
}

func (oneRowDB) Query(context.Context, string, ...any) (pgx.Rows, error) {
	return &oneRow{}, nil
}

func (oneRowDB) QueryRow(context.Context, string, ...any) pgx.Row { return nil }

type oneRow struct{ done bool }

func (r *oneRow) Close()                                       {}
func (r *oneRow) Err() error                                   { return nil }
func (r *oneRow) CommandTag() pgconn.CommandTag                { return pgconn.NewCommandTag("SELECT 1") }
func (r *oneRow) FieldDescriptions() []pgconn.FieldDescription { return nil }
func (r *oneRow) Values() ([]any, error)                       { return nil, nil }
func (r *oneRow) RawValues() [][]byte                          { return nil }
func (r *oneRow) Conn() *pgx.Conn                              { return nil }

func (r *oneRow) Next() bool {
	if r.done {
		return false
	}
	r.done = true
	return true
}

func (r *oneRow) Scan(dest ...any) error {
	*(dest[0].(*int64)) = 1
	*(dest[1].(*string)) = "events:place-network"
	*(dest[2].(*json.RawMessage)) = json.RawMessage(`{"eventId":"evt-1"}`)
	return nil
}

// capturingHook records the real arguments of every command issued, including the ones
// sent inside a pipeline, so the assertion checks the wire-level XADD.
type capturingHook struct{ cmds [][]any }

func (h *capturingHook) DialHook(next redis.DialHook) redis.DialHook { return next }

func (h *capturingHook) ProcessHook(next redis.ProcessHook) redis.ProcessHook {
	return func(ctx context.Context, cmd redis.Cmder) error {
		h.cmds = append(h.cmds, cmd.Args())
		return next(ctx, cmd)
	}
}

func (h *capturingHook) ProcessPipelineHook(next redis.ProcessPipelineHook) redis.ProcessPipelineHook {
	return func(ctx context.Context, cmds []redis.Cmder) error {
		for _, cmd := range cmds {
			h.cmds = append(h.cmds, cmd.Args())
		}
		return next(ctx, cmds)
	}
}

func TestOutboxRelayXAddCarriesApproximateMaxLenAtConfiguredCap(t *testing.T) {
	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr()})
	t.Cleanup(func() { _ = client.Close() })
	hook := &capturingHook{}
	client.AddHook(hook)

	if err := NewOutboxRelay(oneRowDB{}, client).PublishBatch(context.Background()); err != nil {
		t.Fatalf("PublishBatch: %v", err)
	}

	var args []any
	for _, candidate := range hook.cmds {
		if len(candidate) > 0 && strings.EqualFold(asString(candidate[0]), "xadd") {
			args = candidate
			break
		}
	}
	if args == nil {
		t.Fatalf("relay issued no XADD; captured %v", hook.cmds)
	}

	maxLenAt := -1
	for i, a := range args {
		if strings.EqualFold(asString(a), "maxlen") {
			maxLenAt = i
			break
		}
	}
	if maxLenAt < 0 {
		t.Fatalf("relay XADD did not carry MAXLEN: %v", args)
	}
	if asString(args[maxLenAt+1]) != "~" {
		t.Fatalf("relay XADD must use approximate trimming (MAXLEN ~): %v", args)
	}
	got, ok := args[maxLenAt+2].(int64)
	if !ok {
		t.Fatalf("MAXLEN threshold was not an int64: %#v in %v", args[maxLenAt+2], args)
	}
	if got != messaging.MaxLen {
		t.Fatalf("relay XADD MAXLEN ~ %d, want %d", got, messaging.MaxLen)
	}
}

func asString(v any) string {
	s, _ := v.(string)
	return s
}
