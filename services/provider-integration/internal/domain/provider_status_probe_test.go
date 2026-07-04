package domain

import (
	"strings"
	"testing"
)

func TestNewProviderStatusProbeValidates(t *testing.T) {
	ref := &ProviderRef{ConfirmationCode: "CNF-001"}
	probe, err := NewProviderStatusProbe(
		"probe-001",
		"log-001",
		"cr-rail",
		ProbeAmbiguousResult,
		ref,
		"",
		5,
		30,
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if probe.Status != ProbeScheduled {
		t.Fatalf("expected SCHEDULED, got %s", probe.Status)
	}
	if probe.MaxAttempts != 5 {
		t.Fatalf("expected 5 max attempts, got %d", probe.MaxAttempts)
	}
	if probe.IntervalSeconds != 30 {
		t.Fatalf("expected 30 interval seconds, got %d", probe.IntervalSeconds)
	}
}

func TestNewProviderStatusProbeDefaults(t *testing.T) {
	ref := &ProviderRef{PNR: "PNR-001"}
	probe, err := NewProviderStatusProbe("probe-002", "log-002", "cr", ProbeTimeoutRecovery, ref, "", 0, 0)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if probe.MaxAttempts != 5 {
		t.Fatalf("expected default 5 max attempts, got %d", probe.MaxAttempts)
	}
	if probe.IntervalSeconds != 30 {
		t.Fatalf("expected default 30 interval seconds, got %d", probe.IntervalSeconds)
	}
}

func TestNewProviderStatusProbeRejectsMissingFields(t *testing.T) {
	ref := &ProviderRef{ConfirmationCode: "CNF-001"}

	_, err := NewProviderStatusProbe("", "log-001", "cr", ProbeAmbiguousResult, ref, "", 5, 30)
	if err == nil || !strings.Contains(err.Error(), "probe id is required") {
		t.Fatalf("expected probe id error, got %v", err)
	}

	_, err = NewProviderStatusProbe("probe-1", "", "cr", ProbeAmbiguousResult, ref, "", 5, 30)
	if err == nil || !strings.Contains(err.Error(), "source log id is required") {
		t.Fatalf("expected source log id error, got %v", err)
	}

	_, err = NewProviderStatusProbe("probe-1", "log-1", "cr", ProbeAmbiguousResult, nil, "", 5, 30)
	if err == nil || !strings.Contains(err.Error(), "provider reference") {
		t.Fatalf("expected provider reference error, got %v", err)
	}
}

func TestProviderStatusProbeLifecycle(t *testing.T) {
	ref := &ProviderRef{ConfirmationCode: "CNF-001"}
	probe, _ := NewProviderStatusProbe("probe-003", "log-003", "cr", ProbeAmbiguousResult, ref, "", 3, 30)

	if err := probe.Execute(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if probe.Status != ProbeRunning {
		t.Fatalf("expected RUNNING, got %s", probe.Status)
	}
	if probe.AttemptNo != 1 {
		t.Fatalf("expected attempt 1, got %d", probe.AttemptNo)
	}

	if err := probe.CompleteSuccess(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if probe.Status != ProbeResolvedSuccess {
		t.Fatalf("expected RESOLVED_SUCCESS, got %s", probe.Status)
	}
	if probe.Outcome == nil || *probe.Outcome != OutcomeSuccess {
		t.Fatalf("expected SUCCESS outcome")
	}
}

func TestProviderStatusProbeStillProcessingExhausts(t *testing.T) {
	ref := &ProviderRef{ConfirmationCode: "CNF-001"}
	probe, _ := NewProviderStatusProbe("probe-004", "log-004", "cr", ProbeAmbiguousResult, ref, "", 2, 10)

	probe.Execute()
	probe.CompleteStillProcessing()
	if probe.Status != ProbeStillProcessing {
		t.Fatalf("expected STILL_PROCESSING, got %s", probe.Status)
	}

	probe.Execute()
	probe.CompleteStillProcessing()
	if probe.Status != ProbeExhausted {
		t.Fatalf("expected EXHAUSTED, got %s", probe.Status)
	}
}

func TestProviderStatusProbeEscalate(t *testing.T) {
	ref := &ProviderRef{ConfirmationCode: "CNF-001"}
	probe, _ := NewProviderStatusProbe("probe-005", "log-005", "cr", ProbeAmbiguousResult, ref, "", 1, 10)

	probe.Execute()
	probe.CompleteStillProcessing()
	if probe.Status != ProbeExhausted {
		t.Fatalf("expected EXHAUSTED, got %s", probe.Status)
	}

	if err := probe.Escalate("manual review required"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if probe.Status != ProbeEscalated {
		t.Fatalf("expected ESCALATED, got %s", probe.Status)
	}
}

func TestProviderStatusProbeRejectsInvalidTransitions(t *testing.T) {
	ref := &ProviderRef{ConfirmationCode: "CNF-001"}
	probe, _ := NewProviderStatusProbe("probe-006", "log-006", "cr", ProbeAmbiguousResult, ref, "", 3, 30)

	if err := probe.CompleteSuccess(); err == nil {
		t.Fatal("expected error completing from SCHEDULED")
	}

	if err := probe.Escalate("test"); err == nil {
		t.Fatal("expected error escalating from SCHEDULED")
	}
}
