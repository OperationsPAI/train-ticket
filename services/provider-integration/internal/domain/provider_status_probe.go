package domain

import (
	"fmt"
	"strings"
	"time"
)

// ProbePurpose describes why a status probe was scheduled.
type ProbePurpose string

const (
	ProbeAmbiguousResult    ProbePurpose = "AMBIGUOUS_RESULT"
	ProbeTimeoutRecovery    ProbePurpose = "TIMEOUT_RECOVERY"
	ProbeReconciliationDiff ProbePurpose = "RECONCILIATION_DIFF"
	ProbeManualReview       ProbePurpose = "MANUAL_REVIEW"
)

// ProviderStatusProbeStatus tracks the lifecycle of a status probe.
type ProviderStatusProbeStatus string

const (
	ProbeScheduled         ProviderStatusProbeStatus = "SCHEDULED"
	ProbeRunning           ProviderStatusProbeStatus = "RUNNING"
	ProbeResolvedSuccess   ProviderStatusProbeStatus = "RESOLVED_SUCCESS"
	ProbeResolvedRejection ProviderStatusProbeStatus = "RESOLVED_REJECTION"
	ProbeStillProcessing   ProviderStatusProbeStatus = "STILL_PROCESSING"
	ProbeExhausted         ProviderStatusProbeStatus = "EXHAUSTED"
	ProbeEscalated         ProviderStatusProbeStatus = "ESCALATED"
)

// ProviderStatusProbe is the aggregate root for a scheduled status query.
type ProviderStatusProbe struct {
	ProbeID         string
	SourceLogID     string
	ProviderID      ProviderID
	Operation       Operation
	ProviderRef     *ProviderRef
	IdempotencyKey  string
	Purpose         ProbePurpose
	Status          ProviderStatusProbeStatus
	AttemptNo       int
	MaxAttempts     int
	IntervalSeconds int
	Outcome         *RequestOutcome
	ErrorMessage    string
	ScheduledAt     time.Time
	LastExecutedAt  *time.Time
	CompletedAt     *time.Time
}

// NewProviderStatusProbe creates a validated ProviderStatusProbe aggregate.
func NewProviderStatusProbe(probeID string, sourceLogID string, providerID ProviderID, purpose ProbePurpose, providerRef *ProviderRef, idempotencyKey string, maxAttempts int, intervalSeconds int) (ProviderStatusProbe, error) {
	if maxAttempts <= 0 {
		maxAttempts = 5
	}
	if intervalSeconds <= 0 {
		intervalSeconds = 30
	}
	probe := ProviderStatusProbe{
		ProbeID:         strings.TrimSpace(probeID),
		SourceLogID:     strings.TrimSpace(sourceLogID),
		ProviderID:      ProviderID(strings.TrimSpace(string(providerID))),
		Purpose:         purpose,
		ProviderRef:     providerRef,
		IdempotencyKey:  strings.TrimSpace(idempotencyKey),
		Status:          ProbeScheduled,
		AttemptNo:       0,
		MaxAttempts:     maxAttempts,
		IntervalSeconds: intervalSeconds,
		ScheduledAt:     timeNow().UTC(),
	}
	if err := probe.Validate(); err != nil {
		return ProviderStatusProbe{}, err
	}
	return probe, nil
}

func (p ProviderStatusProbe) Validate() error {
	if strings.TrimSpace(p.ProbeID) == "" {
		return fmt.Errorf("probe id is required")
	}
	if strings.TrimSpace(p.SourceLogID) == "" {
		return fmt.Errorf("source log id is required")
	}
	if strings.TrimSpace(string(p.ProviderID)) == "" {
		return fmt.Errorf("provider id is required")
	}
	if !validProbePurpose(p.Purpose) {
		return fmt.Errorf("unsupported probe purpose: %q", p.Purpose)
	}
	if !validProbeStatus(p.Status) {
		return fmt.Errorf("unsupported probe status: %q", p.Status)
	}
	if p.ProviderRef == nil && strings.TrimSpace(p.IdempotencyKey) == "" {
		return fmt.Errorf("probe requires either a provider reference or an idempotency key")
	}
	if p.MaxAttempts <= 0 {
		return fmt.Errorf("max attempts must be positive")
	}
	if p.IntervalSeconds <= 0 {
		return fmt.Errorf("interval seconds must be positive")
	}
	return nil
}

func (p *ProviderStatusProbe) Execute() error {
	if p.Status != ProbeScheduled && p.Status != ProbeStillProcessing {
		return fmt.Errorf("cannot execute probe from status %q", p.Status)
	}
	now := timeNow().UTC()
	p.Status = ProbeRunning
	p.AttemptNo++
	p.LastExecutedAt = &now
	return nil
}

func (p *ProviderStatusProbe) CompleteSuccess() error {
	if p.Status != ProbeRunning {
		return fmt.Errorf("cannot complete probe from status %q", p.Status)
	}
	now := timeNow().UTC()
	p.Status = ProbeResolvedSuccess
	outcome := OutcomeSuccess
	p.Outcome = &outcome
	p.CompletedAt = &now
	return nil
}

func (p *ProviderStatusProbe) CompleteRejection() error {
	if p.Status != ProbeRunning {
		return fmt.Errorf("cannot complete probe from status %q", p.Status)
	}
	now := timeNow().UTC()
	p.Status = ProbeResolvedRejection
	outcome := OutcomeRejected
	p.Outcome = &outcome
	p.CompletedAt = &now
	return nil
}

func (p *ProviderStatusProbe) CompleteStillProcessing() error {
	if p.Status != ProbeRunning {
		return fmt.Errorf("cannot mark still processing from status %q", p.Status)
	}
	if p.AttemptNo >= p.MaxAttempts {
		p.Status = ProbeExhausted
		return nil
	}
	p.Status = ProbeStillProcessing
	return nil
}

func (p *ProviderStatusProbe) Escalate(message string) error {
	if p.Status != ProbeExhausted && p.Status != ProbeRunning {
		return fmt.Errorf("cannot escalate probe from status %q", p.Status)
	}
	now := timeNow().UTC()
	p.Status = ProbeEscalated
	p.ErrorMessage = message
	p.CompletedAt = &now
	return nil
}

func validProbePurpose(value ProbePurpose) bool {
	switch value {
	case ProbeAmbiguousResult, ProbeTimeoutRecovery, ProbeReconciliationDiff, ProbeManualReview:
		return true
	default:
		return false
	}
}

func validProbeStatus(value ProviderStatusProbeStatus) bool {
	switch value {
	case ProbeScheduled, ProbeRunning, ProbeResolvedSuccess, ProbeResolvedRejection,
		ProbeStillProcessing, ProbeExhausted, ProbeEscalated:
		return true
	default:
		return false
	}
}
