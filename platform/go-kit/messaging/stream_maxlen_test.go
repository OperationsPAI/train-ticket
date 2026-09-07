package messaging

import (
	"context"
	"strings"
	"testing"

	miniredis "github.com/alicebob/miniredis/v2"
	redis "github.com/redis/go-redis/v9"
)

// capturingHook records the real arguments of every command the client issues, so the
// assertions below check the wire-level XADD rather than trusting the code path.
type capturingHook struct {
	cmds [][]any
}

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

func (h *capturingHook) xaddArgs(t *testing.T) []any {
	t.Helper()
	for _, args := range h.cmds {
		if len(args) > 0 && strings.EqualFold(toString(args[0]), "xadd") {
			return args
		}
	}
	t.Fatalf("no XADD command was issued; captured %v", h.cmds)
	return nil
}

func toString(v any) string {
	s, _ := v.(string)
	return s
}

// assertApproxMaxLen requires the captured XADD to carry MAXLEN ~ <want>.
func assertApproxMaxLen(t *testing.T, args []any, want int64) {
	t.Helper()
	var maxLenAt = -1
	for i, a := range args {
		if strings.EqualFold(toString(a), "maxlen") {
			maxLenAt = i
			break
		}
	}
	if maxLenAt < 0 {
		t.Fatalf("XADD did not carry MAXLEN: %v", args)
	}
	if toString(args[maxLenAt+1]) != "~" {
		t.Fatalf("XADD must use approximate trimming (MAXLEN ~), got %v", args)
	}
	got, ok := args[maxLenAt+2].(int64)
	if !ok {
		t.Fatalf("MAXLEN threshold was not an int64: %#v in %v", args[maxLenAt+2], args)
	}
	if got != want {
		t.Fatalf("XADD MAXLEN ~ %d, want %d (args: %v)", got, want, args)
	}
}

func newCapturingBus(t *testing.T) (*RedisEventBus, *capturingHook) {
	t.Helper()
	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr()})
	hook := &capturingHook{}
	client.AddHook(hook)
	bus := NewRedisEventBusWithClient(client, RedisConfig{URL: server.Addr()})
	t.Cleanup(func() { _ = bus.Close() })
	return bus, hook
}

func TestPublishXAddCarriesApproximateMaxLenAtConfiguredCap(t *testing.T) {
	bus, hook := newCapturingBus(t)
	envelope, err := NewEventEnvelope("ThingHappened", "test-producer", "0194f2e0-7b3e-7610-0284-5c26e8b0c123", map[string]string{"thingId": "thing-1"}, EnvelopeOptions{})
	if err != nil {
		t.Fatal(err)
	}

	if err := bus.Publish(context.Background(), envelope); err != nil {
		t.Fatal(err)
	}

	assertApproxMaxLen(t, hook.xaddArgs(t), MaxLen)
}

func TestDLQXAddCarriesApproximateMaxLenAtConfiguredCap(t *testing.T) {
	bus, hook := newCapturingBus(t)
	ctx := context.Background()
	stream := StreamName("test-producer")
	if err := bus.ensureGroup(ctx, stream, "test-group"); err != nil {
		t.Fatal(err)
	}

	bus.moveToDLQAndAck(ctx, stream, "test-group", "consumer-1", "1-0", `{"eventId":"evt-1"}`, "MaxDeliveryAttempts", 5)

	args := hook.xaddArgs(t)
	if toString(args[1]) != stream+DeadLetterSuffix {
		t.Fatalf("DLQ XADD targeted %v, want %s", args[1], stream+DeadLetterSuffix)
	}
	assertApproxMaxLen(t, args, MaxLen)
}

func TestStreamMaxLenHonoursConfiguredOverride(t *testing.T) {
	if got := StreamMaxLen("2500"); got != 2500 {
		t.Fatalf("StreamMaxLen(\"2500\") = %d, want 2500", got)
	}
	if got := StreamMaxLen(" 750 "); got != 750 {
		t.Fatalf("StreamMaxLen(\" 750 \") = %d, want 750", got)
	}
}

func TestStreamMaxLenFallsBackToDefaultWhenUnsetOrInvalid(t *testing.T) {
	if DefaultStreamMaxLen != 10000 {
		t.Fatalf("DefaultStreamMaxLen = %d, want 10000 to match java-kit", DefaultStreamMaxLen)
	}
	if StreamMaxLenEnv != "EVENT_STREAM_MAXLEN" {
		t.Fatalf("StreamMaxLenEnv = %q, want EVENT_STREAM_MAXLEN", StreamMaxLenEnv)
	}
	for _, configured := range []string{"", "   ", "not-a-number", "0", "-5"} {
		if got := StreamMaxLen(configured); got != DefaultStreamMaxLen {
			t.Fatalf("StreamMaxLen(%q) = %d, want the default %d", configured, got, DefaultStreamMaxLen)
		}
	}
}
