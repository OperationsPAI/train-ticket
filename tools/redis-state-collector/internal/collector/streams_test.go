package collector

import (
	"testing"
	"time"
)

func TestClassifyStreamSeparatesBusinessAndDeadLetterKeys(t *testing.T) {
	cases := []struct {
		key    string
		stream string
		kind   StreamKind
		ok     bool
	}{
		{"events:journey-order", "events:journey-order", BusinessStream, true},
		{"events:journey-order:dlq", "events:journey-order", DeadLetterStream, true},
		{"events:capacity-availability:dlq", "events:capacity-availability", DeadLetterStream, true},
		{"events:", "", BusinessStream, false},
		{"events::dlq", "", BusinessStream, false},
		{"some-other-key", "", BusinessStream, false},
		{"", "", BusinessStream, false},
	}
	for _, testCase := range cases {
		stream, kind, ok := ClassifyStream(testCase.key)
		if ok != testCase.ok || stream != testCase.stream || (ok && kind != testCase.kind) {
			t.Fatalf("ClassifyStream(%q) = (%q, %v, %v), want (%q, %v, %v)",
				testCase.key, stream, kind, ok, testCase.stream, testCase.kind, testCase.ok)
		}
	}
}

func TestEntryAgeUsesTheTimestampRedisEncodesInTheID(t *testing.T) {
	now := time.UnixMilli(1_700_000_060_000)
	age, ok := EntryAge("1700000000000-3", now)
	if !ok {
		t.Fatal("a well-formed entry id must produce an age")
	}
	if age != time.Minute {
		t.Fatalf("age = %v, want 1m", age)
	}
}

func TestEntryAgeRejectsMalformedIDsAndFutureEntries(t *testing.T) {
	now := time.UnixMilli(1_700_000_000_000)
	for _, id := range []string{"", "no-dash", "-1-0", "0-0", "abc-1"} {
		if _, ok := EntryAge(id, now); ok {
			t.Fatalf("EntryAge(%q) must not be trusted", id)
		}
	}
	age, ok := EntryAge("1700000060000-0", now)
	if !ok || age != 0 {
		t.Fatalf("a future entry must clamp to zero, got (%v, %v)", age, ok)
	}
}

func TestNextEntryAfterUsesAnExclusiveLowerBound(t *testing.T) {
	if got := nextEntryAfter("1699999999999-5"); got != "(1699999999999-5" {
		t.Fatalf("nextEntryAfter = %q", got)
	}
	// A group that has never been handed an entry reports 0-0, and the exclusive
	// form of that still asks for the first entry rather than an empty range.
	if got := nextEntryAfter(""); got != "(0-0" {
		t.Fatalf("nextEntryAfter(empty) = %q", got)
	}
}

func TestDeadLetterLabelCollapsesUnknownValues(t *testing.T) {
	state := deadLetterLabel("events:payment", map[string]any{
		"consumerGroup": "notification",
		"envelope":      `{"eventType":"PaymentCaptured","orderId":"order-1"}`,
	})
	if state.ConsumerGroup != "notification" || state.EventType != "PaymentCaptured" {
		t.Fatalf("unexpected label: %+v", state)
	}
	if state.Stream != "events:payment" {
		t.Fatalf("stream must come from the parsed key, got %q", state.Stream)
	}

	// Two different unreadable entries must produce the same label set, so a
	// malformed payload cannot become a label of its own.
	first := deadLetterLabel("events:payment", map[string]any{"envelope": "not json"})
	second := deadLetterLabel("events:payment", map[string]any{"consumerGroup": "", "envelope": ""})
	if first != second {
		t.Fatalf("unreadable entries must collapse: %+v vs %+v", first, second)
	}
	if first.ConsumerGroup != UnknownLabel || first.EventType != UnknownLabel {
		t.Fatalf("unreadable entries must use the unknown literal: %+v", first)
	}
}

func TestEarlierComparesEntryIDsByTimestamp(t *testing.T) {
	if !earlier("1699999999999-9", "1700000000000-0") {
		t.Fatal("a lower millisecond must be earlier regardless of sequence")
	}
	if earlier("1700000000001-0", "1700000000000-9") {
		t.Fatal("a higher millisecond must not be earlier")
	}
}
