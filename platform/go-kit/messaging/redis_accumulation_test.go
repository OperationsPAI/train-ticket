package messaging

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	redis "github.com/redis/go-redis/v9"
)

// commandRecorder observes the commands a client really sends, so a test can
// assert on the shape of the traffic -- "one XACK carrying three ids", "the
// top-up read carries no BLOCK" -- rather than on the code path that produced it.
type commandRecorder struct {
	mu       sync.Mutex
	commands []string
	args     [][]interface{}
}

func (r *commandRecorder) DialHook(next redis.DialHook) redis.DialHook { return next }

func (r *commandRecorder) ProcessHook(next redis.ProcessHook) redis.ProcessHook {
	return func(ctx context.Context, cmd redis.Cmder) error {
		r.mu.Lock()
		r.commands = append(r.commands, cmd.Name())
		r.args = append(r.args, cmd.Args())
		r.mu.Unlock()
		return next(ctx, cmd)
	}
}

func (r *commandRecorder) ProcessPipelineHook(next redis.ProcessPipelineHook) redis.ProcessPipelineHook {
	return next
}

func (r *commandRecorder) recorded(name string) [][]interface{} {
	r.mu.Lock()
	defer r.mu.Unlock()
	var matches [][]interface{}
	for i, command := range r.commands {
		if command == name {
			matches = append(matches, r.args[i])
		}
	}
	return matches
}

func (r *commandRecorder) reset() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.commands = nil
	r.args = nil
}

func hasArgument(args []interface{}, want string) bool {
	for _, arg := range args {
		if commandArgument(arg) == want {
			return true
		}
	}
	return false
}

func commandArgument(arg interface{}) string {
	switch value := arg.(type) {
	case string:
		return value
	case []byte:
		return string(value)
	default:
		return ""
	}
}

// appendEnvelope writes one entry to the stream and returns its id.
func (h *metricsHarness) appendEnvelope(t *testing.T, ctx context.Context, stream, body string) string {
	t.Helper()
	added := h.client.XAdd(ctx, &redis.XAddArgs{Stream: stream, Values: map[string]any{EnvelopeField: body}})
	if err := added.Err(); err != nil {
		t.Fatalf("append entry: %v", err)
	}
	return added.Val()
}

// The problem this implements against: XREADGROUP ... BLOCK hands back as soon as
// one entry is available, so a reader that keeps up gets one to three entries per
// round, and the one XACK that retires the round costs the same as one XACK per
// entry. One accumulation tick has to gather the rest of the burst into the round.
func TestAccumulationTickGathersABurstIntoOneRound(t *testing.T) {
	harness := newMetricsHarness(t)
	ctx := context.Background()
	stream, group, consumer := "events:payment", "journey-order", "journey-order-1"
	if err := harness.bus.ensureGroup(ctx, stream, group); err != nil {
		t.Fatalf("ensure group: %v", err)
	}
	harness.appendEnvelope(t, ctx, stream, harness.body(t, harness.envelope(t, "PaymentAuthorised")))

	// The blocking read catches the first entry of the burst, exactly as a
	// keeping-up reader does.
	first, err := harness.bus.readStreams(ctx, []string{stream}, []string{">"}, group, consumer, -1)
	if err != nil {
		t.Fatalf("first read: %v", err)
	}
	if got := countMessages(first); got != 1 {
		t.Fatalf("the first read must return the single entry, got %d", got)
	}

	// The rest of the burst lands inside the accumulation tick.
	harness.appendEnvelope(t, ctx, stream, harness.body(t, harness.envelope(t, "PaymentAuthorised")))
	harness.appendEnvelope(t, ctx, stream, harness.body(t, harness.envelope(t, "PaymentAuthorised")))

	merged := harness.bus.accumulateRead(ctx, []string{stream}, []string{">"}, group, consumer, first)

	if got := countMessages(merged); got != 3 {
		t.Fatalf("the tick must fold the whole burst into one round, got %d entries", got)
	}
	if len(merged) != 1 {
		t.Fatalf("the round stays one stream, got %d", len(merged))
	}
}

// The whole round is retired by a single command: XACK takes any number of ids
// for the same stream and group, and one XACK per delivered event was the largest
// command class a single-threaded Redis served.
func TestAnAccumulatedRoundIsAcknowledgedByOneXack(t *testing.T) {
	harness := newMetricsHarness(t)
	recorder := &commandRecorder{}
	harness.client.AddHook(recorder)
	ctx := context.Background()
	stream, group, consumer := "events:payment", "journey-order", "journey-order-1"
	if err := harness.bus.ensureGroup(ctx, stream, group); err != nil {
		t.Fatalf("ensure group: %v", err)
	}

	for range 3 {
		harness.appendEnvelope(t, ctx, stream, harness.body(t, harness.envelope(t, "PaymentAuthorised")))
	}
	round, err := harness.bus.readStreams(ctx, []string{stream}, []string{">"}, group, consumer, -1)
	if err != nil {
		t.Fatalf("read the round: %v", err)
	}
	if len(round) != 1 || len(round[0].Messages) != 3 {
		t.Fatalf("expected one stream carrying three entries, got %#v", round)
	}
	messages := round[0].Messages

	recorder.reset()
	harness.bus.processBatch(ctx, stream, group, consumer, messages, func(context.Context, EventEnvelope) error {
		return nil
	}, nil)

	acks := recorder.recorded("xack")
	if len(acks) != 1 {
		t.Fatalf("a three-entry round must cost one XACK, got %d", len(acks))
	}
	for _, message := range messages {
		if !hasArgument(acks[0], message.ID) {
			t.Fatalf("the single XACK must carry every id, missing %s in %v", message.ID, acks[0])
		}
	}
}

// The accumulation must not touch the idle path. An empty read has nothing to
// fold, so it must neither sleep the tick nor spend a second XREADGROUP: the
// loop goes straight back to the blocking read that keeps an empty stream from
// spinning.
func TestAnEmptyReadDoesNotPayTheAccumulationTick(t *testing.T) {
	harness := newMetricsHarness(t)
	recorder := &commandRecorder{}
	harness.client.AddHook(recorder)
	ctx := context.Background()
	stream, group, consumer := "events:payment", "journey-order", "journey-order-1"
	if err := harness.bus.ensureGroup(ctx, stream, group); err != nil {
		t.Fatalf("ensure group: %v", err)
	}

	recorder.reset()
	started := time.Now()
	merged := harness.bus.accumulateRead(ctx, []string{stream}, []string{">"}, group, consumer, nil)

	if got := countMessages(merged); got != 0 {
		t.Fatalf("an empty read stays empty, got %d", got)
	}
	if reads := recorder.recorded("xreadgroup"); len(reads) != 0 {
		t.Fatalf("an empty read must not issue a top-up read, got %d", len(reads))
	}
	if elapsed := time.Since(started); elapsed >= AccumulateTick {
		t.Fatalf("an empty read must not sleep the tick, waited %v", elapsed)
	}
}

// The idle read blocks inside Redis; only the top-up read is non-blocking. A zero
// BLOCK would be worse than useless here -- Redis reads `BLOCK 0` as "block
// forever" -- so the non-blocking form omits the argument instead.
func TestTheIdleReadBlocksAndTheTopUpDoesNot(t *testing.T) {
	harness := newMetricsHarness(t)
	recorder := &commandRecorder{}
	harness.client.AddHook(recorder)
	ctx := context.Background()
	stream, group, consumer := "events:payment", "journey-order", "journey-order-1"
	if err := harness.bus.ensureGroup(ctx, stream, group); err != nil {
		t.Fatalf("ensure group: %v", err)
	}

	// An empty stream is not an error: Redis answers with a null array, which
	// go-redis reports as redis.Nil and the reader treats as "nothing yet".
	if _, err := harness.bus.readStreams(ctx, []string{stream}, []string{">"}, group, consumer, harness.bus.cfg.ReadBlock); err != nil && !errors.Is(err, redis.Nil) {
		t.Fatalf("idle read: %v", err)
	}
	if _, err := harness.bus.readStreams(ctx, []string{stream}, []string{">"}, group, consumer, -1); err != nil && !errors.Is(err, redis.Nil) {
		t.Fatalf("top-up read: %v", err)
	}

	reads := recorder.recorded("xreadgroup")
	if len(reads) != 2 {
		t.Fatalf("expected the idle read and the top-up read, got %d", len(reads))
	}
	if !hasArgument(reads[0], "block") {
		t.Fatalf("the idle read must block in Redis, got %v", reads[0])
	}
	if hasArgument(reads[1], "block") {
		t.Fatalf("the top-up read must not block, got %v", reads[1])
	}
}

func TestAccumulateTickDefaultsAndHonoursAnOverride(t *testing.T) {
	if DefaultAccumulateTick != 50*time.Millisecond {
		t.Fatalf("default accumulation tick drifted: %v", DefaultAccumulateTick)
	}
	if got := accumulateTick(""); got != DefaultAccumulateTick {
		t.Fatalf("blank must fall back, got %v", got)
	}
	if got := accumulateTick("not-a-number"); got != DefaultAccumulateTick {
		t.Fatalf("non-numeric must fall back, got %v", got)
	}
	if got := accumulateTick("-5"); got != DefaultAccumulateTick {
		t.Fatalf("a negative tick must fall back, got %v", got)
	}
	if got := accumulateTick(" 250 "); got != 250*time.Millisecond {
		t.Fatalf("an override must be honoured, got %v", got)
	}
	// Zero is the documented way to restore the pre-accumulation read.
	if got := accumulateTick("0"); got != 0 {
		t.Fatalf("zero must disable the accumulation, got %v", got)
	}
}
