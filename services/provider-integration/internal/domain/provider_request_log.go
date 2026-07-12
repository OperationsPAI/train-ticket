package domain

import (
	"fmt"
	"strings"
	"time"
)

// RequestOutcome represents the final mapped outcome of a provider request.
type RequestOutcome string

const (
	OutcomeSuccess   RequestOutcome = "SUCCESS"
	OutcomeRejected  RequestOutcome = "REJECTED"
	OutcomeFailed    RequestOutcome = "FAILED"
	OutcomeAmbiguous RequestOutcome = "AMBIGUOUS"
	OutcomeConflict  RequestOutcome = "CONFLICT"
)

// ProviderRequestLogStatus tracks the lifecycle of a provider request.
type ProviderRequestLogStatus string

const (
	RequestCreated   ProviderRequestLogStatus = "CREATED"
	RequestSent      ProviderRequestLogStatus = "SENT"
	RequestSucceeded ProviderRequestLogStatus = "SUCCEEDED"
	RequestRejected  ProviderRequestLogStatus = "REJECTED"
	RequestFailed    ProviderRequestLogStatus = "FAILED"
	RequestRetrying  ProviderRequestLogStatus = "RETRYING"
	RequestTimedOut  ProviderRequestLogStatus = "TIMED_OUT"
	RequestAmbiguous ProviderRequestLogStatus = "AMBIGUOUS"
	RequestProbing   ProviderRequestLogStatus = "PROBING"
	RequestConflict  ProviderRequestLogStatus = "CONFLICT"
)

// ProviderRef holds the external provider's reference identifiers (struct version).
// This is distinct from acl.go's ProviderReference which is a string type.
type ProviderRef struct {
	ConfirmationCode string
	PNR              string
	TicketNumber     string
	ExternalID       string
}

// RawArchive preserves raw request/response data for audit and supplier disputes.
type RawArchive struct {
	RawRequest      string
	RawResponse     string
	RawError        string
	RawStatus       string
	SignatureDigest string
	MessageVersion  string
}

// RetryAttempt records a single retry of a provider request.
type RetryAttempt struct {
	AttemptNo   int
	RequestedAt time.Time
	Outcome     *RequestOutcome
}

// ProviderRequestLog is the aggregate root for recording an external provider call.
// It is the authoritative interaction log containing idempotency keys,
// correlation IDs, raw data, retry chain, and the final mapped outcome.
type ProviderRequestLog struct {
	LogID          string
	ProviderID     ProviderID
	Operation      Operation
	IdempotencyKey string
	CorrelationID  string
	BusinessRef    string
	Status         ProviderRequestLogStatus
	Outcome        *RequestOutcome
	ProviderRef    *ProviderRef
	ErrorType      *ErrorClassification
	ErrorMessage   string
	NextAction     string
	RawArchive     *RawArchive
	RetryAttempts  []RetryAttempt
	CreatedAt      time.Time
	SentAt         *time.Time
	CompletedAt    *time.Time
}

// NewProviderRequestLog creates a validated ProviderRequestLog aggregate.
func NewProviderRequestLog(logID string, providerID ProviderID, operation Operation, idempotencyKey string, correlationID string, businessRef string) (ProviderRequestLog, error) {
	log := ProviderRequestLog{
		LogID:          strings.TrimSpace(logID),
		ProviderID:     ProviderID(strings.TrimSpace(string(providerID))),
		Operation:      operation,
		IdempotencyKey: strings.TrimSpace(idempotencyKey),
		CorrelationID:  strings.TrimSpace(correlationID),
		BusinessRef:    strings.TrimSpace(businessRef),
		Status:         RequestCreated,
		RetryAttempts:  nil,
		CreatedAt:      timeNow().UTC(),
	}
	if err := log.Validate(); err != nil {
		return ProviderRequestLog{}, err
	}
	return log, nil
}

func (l ProviderRequestLog) Validate() error {
	if strings.TrimSpace(l.LogID) == "" {
		return fmt.Errorf("log id is required")
	}
	if strings.TrimSpace(string(l.ProviderID)) == "" {
		return fmt.Errorf("provider id is required")
	}
	if !validRequestLogOperation(l.Operation) {
		return fmt.Errorf("unsupported operation: %q", l.Operation)
	}
	if strings.TrimSpace(l.IdempotencyKey) == "" {
		return fmt.Errorf("idempotency key is required")
	}
	if !validRequestLogStatus(l.Status) {
		return fmt.Errorf("unsupported request log status: %q", l.Status)
	}
	return nil
}

func (l *ProviderRequestLog) MarkSent() error {
	if l.Status != RequestCreated && l.Status != RequestRetrying {
		return fmt.Errorf("cannot mark sent from status %q", l.Status)
	}
	now := timeNow().UTC()
	l.Status = RequestSent
	l.SentAt = &now
	return nil
}

func (l *ProviderRequestLog) MarkSucceeded(ref *ProviderRef) error {
	if l.Status != RequestSent && l.Status != RequestProbing {
		return fmt.Errorf("cannot mark succeeded from status %q", l.Status)
	}
	now := timeNow().UTC()
	l.Status = RequestSucceeded
	outcome := OutcomeSuccess
	l.Outcome = &outcome
	l.ProviderRef = ref
	l.CompletedAt = &now
	return nil
}

func (l *ProviderRequestLog) MarkRejected(errorType ErrorClassification, message string) error {
	if l.Status != RequestSent && l.Status != RequestProbing {
		return fmt.Errorf("cannot mark rejected from status %q", l.Status)
	}
	now := timeNow().UTC()
	l.Status = RequestRejected
	outcome := OutcomeRejected
	l.Outcome = &outcome
	l.ErrorType = &errorType
	l.ErrorMessage = message
	l.CompletedAt = &now
	return nil
}

func (l *ProviderRequestLog) MarkFailed(errorType ErrorClassification, message string) error {
	if l.Status != RequestSent {
		return fmt.Errorf("cannot mark failed from status %q", l.Status)
	}
	now := timeNow().UTC()
	l.Status = RequestFailed
	outcome := OutcomeFailed
	l.Outcome = &outcome
	l.ErrorType = &errorType
	l.ErrorMessage = message
	l.CompletedAt = &now
	return nil
}

func (l *ProviderRequestLog) MarkRetrying(attemptNo int) error {
	if l.Status != RequestSent && l.Status != RequestRetrying {
		return fmt.Errorf("cannot mark retrying from status %q", l.Status)
	}
	l.Status = RequestRetrying
	l.RetryAttempts = append(l.RetryAttempts, RetryAttempt{
		AttemptNo:   attemptNo,
		RequestedAt: timeNow().UTC(),
	})
	return nil
}

func (l *ProviderRequestLog) MarkTimedOut() error {
	if l.Status != RequestSent && l.Status != RequestRetrying {
		return fmt.Errorf("cannot mark timed out from status %q", l.Status)
	}
	l.Status = RequestTimedOut
	return nil
}

func (l *ProviderRequestLog) MarkAmbiguous() error {
	if l.Status != RequestTimedOut {
		return fmt.Errorf("cannot mark ambiguous from status %q", l.Status)
	}
	l.Status = RequestAmbiguous
	outcome := OutcomeAmbiguous
	l.Outcome = &outcome
	return nil
}

func (l *ProviderRequestLog) MarkProbing() error {
	if l.Status != RequestAmbiguous {
		return fmt.Errorf("cannot mark probing from status %q", l.Status)
	}
	l.Status = RequestProbing
	return nil
}

func (l *ProviderRequestLog) MarkConflict(message string) error {
	if l.Status != RequestProbing && l.Status != RequestAmbiguous {
		return fmt.Errorf("cannot mark conflict from status %q", l.Status)
	}
	now := timeNow().UTC()
	l.Status = RequestConflict
	outcome := OutcomeConflict
	l.Outcome = &outcome
	errType := ErrorProviderStateConflict
	l.ErrorType = &errType
	l.ErrorMessage = message
	l.CompletedAt = &now
	return nil
}

func (l *ProviderRequestLog) AttachRawArchive(archive RawArchive) {
	l.RawArchive = &archive
}

func validRequestLogOperation(value Operation) bool {
	switch value {
	case OperationReserveReservation, OperationConfirmReservation, OperationIssueTicket,
		OperationCapturePayment, OperationSettleRefund, OperationAcceptBoarding, OperationQueryStatus:
		return true
	default:
		return false
	}
}

func validRequestLogStatus(value ProviderRequestLogStatus) bool {
	switch value {
	case RequestCreated, RequestSent, RequestSucceeded, RequestRejected, RequestFailed,
		RequestRetrying, RequestTimedOut, RequestAmbiguous, RequestProbing, RequestConflict:
		return true
	default:
		return false
	}
}
