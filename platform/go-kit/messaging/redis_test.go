package messaging

import (
	"bytes"
	"log/slog"
	"strings"
	"testing"
	"time"
)

func TestDeadLetterFieldsCarryAttributionMetadata(t *testing.T) {
	fields := deadLetterFields(`{"eventId":"evt-1"}`, "journey-order", "consumer-1", "fatal: poison", 7)
	if fields[EnvelopeField] != `{"eventId":"evt-1"}` {
		t.Fatalf("missing envelope field: %#v", fields)
	}
	if fields["consumerGroup"] != "journey-order" || fields["consumerName"] != "consumer-1" {
		t.Fatalf("missing consumer attribution: %#v", fields)
	}
	if fields["failureReason"] != "fatal: poison" || fields["attempts"] != "7" {
		t.Fatalf("missing failure metadata: %#v", fields)
	}
	if _, err := time.Parse(time.RFC3339Nano, fields["deadLetteredAt"].(string)); err != nil {
		t.Fatalf("deadLetteredAt must be RFC3339 UTC: %v", err)
	}
}

func TestFailureReasonIsTruncated(t *testing.T) {
	longReason := ""
	for len(longReason) <= 510 {
		longReason += "x"
	}
	if got := truncateFailureReason(longReason); len(got) != 500 {
		t.Fatalf("expected 500 characters, got %d", len(got))
	}
}

func TestWarnDeadLetterIncludesAttribution(t *testing.T) {
	var output bytes.Buffer
	slog.SetDefault(slog.New(slog.NewTextHandler(&output, &slog.HandlerOptions{Level: slog.LevelWarn})))
	fields := deadLetterFields(`{"eventId":"evt-1"}`, "journey-order", "consumer-1", "fatal", 1)
	warnDeadLetter("journey-order", "events:payment", `{"eventId":"evt-1"}`, fields)
	logLine := output.String()
	for _, expected := range []string{`level=WARN`, `service=journey-order`, `stream=events:payment`, `eventId=evt-1`, `reason=fatal`} {
		if !strings.Contains(logLine, expected) {
			t.Fatalf("expected %q in warn log %q", expected, logLine)
		}
	}
}
