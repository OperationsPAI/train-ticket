package domain

import (
	"fmt"
	"strings"
	"time"
)

type ProviderID string
type ProviderRequestID string
type ProviderCallbackID string
type Operation string
type IdempotencyKey string
type CorrelationID string
type BusinessRef string
type RawArchiveID string
type RawArchiveURI string
type ProviderReference string
type MappingVersion string

const (
	OperationReserveReservation Operation = "RESERVE_PROVIDER_RESERVATION"
	OperationConfirmReservation Operation = "CONFIRM_PROVIDER_RESERVATION"
	OperationIssueTicket        Operation = "ISSUE_SUPPLIER_TICKET"
	OperationCapturePayment     Operation = "CAPTURE_CHANNEL_PAYMENT"
	OperationSettleRefund       Operation = "SETTLE_CHANNEL_REFUND"
	OperationAcceptBoarding     Operation = "ACCEPT_PROVIDER_BOARDING"
	OperationQueryStatus        Operation = "QUERY_PROVIDER_STATUS"
)

// ProviderRequestIdentity is the stable identity of one external provider
// intent. It contains cross-context references only; Provider Integration uses
// them for idempotency and traceability but does not own the referenced Booking,
// Payment, Entitlement, Order, or Capacity aggregate.
type ProviderRequestIdentity struct {
	ProviderID     ProviderID        `json:"providerId"`
	RequestID      ProviderRequestID `json:"requestId"`
	Operation      Operation         `json:"operation"`
	IdempotencyKey IdempotencyKey    `json:"idempotencyKey"`
	CorrelationID  CorrelationID     `json:"correlationId"`
	BusinessRef    BusinessRef       `json:"businessRef"`
}

func NewProviderRequestIdentity(providerID ProviderID, requestID ProviderRequestID, operation Operation, idempotencyKey IdempotencyKey, correlationID CorrelationID, businessRef BusinessRef) (ProviderRequestIdentity, error) {
	identity := ProviderRequestIdentity{
		ProviderID:     ProviderID(trim(providerID)),
		RequestID:      ProviderRequestID(trim(requestID)),
		Operation:      operation,
		IdempotencyKey: IdempotencyKey(trim(idempotencyKey)),
		CorrelationID:  CorrelationID(trim(correlationID)),
		BusinessRef:    BusinessRef(trim(businessRef)),
	}
	if err := identity.Validate(); err != nil {
		return ProviderRequestIdentity{}, err
	}
	return identity, nil
}

func (i ProviderRequestIdentity) Validate() error {
	if trim(i.ProviderID) == "" {
		return fmt.Errorf("provider id is required")
	}
	if trim(i.RequestID) == "" {
		return fmt.Errorf("provider request id is required")
	}
	if !validOperation(i.Operation) {
		return fmt.Errorf("unsupported provider operation: %q", i.Operation)
	}
	if trim(i.IdempotencyKey) == "" {
		return fmt.Errorf("idempotency key is required")
	}
	if trim(i.CorrelationID) == "" {
		return fmt.Errorf("correlation id is required")
	}
	if trim(i.BusinessRef) == "" {
		return fmt.Errorf("business reference is required")
	}
	return nil
}

// IdempotencyScope is the anti-corruption key that prevents multiple external
// intents for the same provider operation. The business reference remains only a
// reference; this service must not mutate the referenced aggregate.
func (i ProviderRequestIdentity) IdempotencyScope() string {
	return strings.Join([]string{string(i.ProviderID), string(i.Operation), string(i.IdempotencyKey)}, ":")
}

// ProviderCallbackIdentity records a webhook/callback before any downstream
// event can be emitted. SignatureScope is part of the idempotency boundary so
// provider retries through different credential scopes cannot collide silently.
type ProviderCallbackIdentity struct {
	ProviderID     ProviderID         `json:"providerId"`
	CallbackID     ProviderCallbackID `json:"callbackId"`
	SignatureScope string             `json:"signatureScope"`
	IdempotencyKey IdempotencyKey     `json:"idempotencyKey"`
	CorrelationID  CorrelationID      `json:"correlationId"`
}

func NewProviderCallbackIdentity(providerID ProviderID, callbackID ProviderCallbackID, signatureScope string, idempotencyKey IdempotencyKey, correlationID CorrelationID) (ProviderCallbackIdentity, error) {
	identity := ProviderCallbackIdentity{
		ProviderID:     ProviderID(trim(providerID)),
		CallbackID:     ProviderCallbackID(trim(callbackID)),
		SignatureScope: strings.TrimSpace(signatureScope),
		IdempotencyKey: IdempotencyKey(trim(idempotencyKey)),
		CorrelationID:  CorrelationID(trim(correlationID)),
	}
	if err := identity.Validate(); err != nil {
		return ProviderCallbackIdentity{}, err
	}
	return identity, nil
}

func (i ProviderCallbackIdentity) Validate() error {
	if trim(i.ProviderID) == "" {
		return fmt.Errorf("provider id is required")
	}
	if trim(i.CallbackID) == "" {
		return fmt.Errorf("callback id is required")
	}
	if i.SignatureScope == "" {
		return fmt.Errorf("callback signature scope is required")
	}
	if trim(i.IdempotencyKey) == "" {
		return fmt.Errorf("callback idempotency key is required")
	}
	if trim(i.CorrelationID) == "" {
		return fmt.Errorf("correlation id is required")
	}
	return nil
}

func (i ProviderCallbackIdentity) InboxKey() string {
	return strings.Join([]string{string(i.ProviderID), string(i.SignatureScope), string(i.CallbackID)}, ":")
}

type RawArchiveKind string

const (
	RawArchiveRequest  RawArchiveKind = "RAW_REQUEST"
	RawArchiveResponse RawArchiveKind = "RAW_RESPONSE"
	RawArchiveWebhook  RawArchiveKind = "RAW_WEBHOOK"
	RawArchiveStatus   RawArchiveKind = "RAW_STATUS"
	RawArchiveError    RawArchiveKind = "RAW_ERROR"
)

// RawArchiveReference points to immutable raw provider material stored for
// audit, replay, and supplier disputes. It deliberately carries references and
// digests rather than raw payloads so raw provider language does not leak into
// core domain events.
type RawArchiveReference struct {
	ArchiveID     RawArchiveID   `json:"archiveId"`
	Kind          RawArchiveKind `json:"kind"`
	URI           RawArchiveURI  `json:"uri"`
	SHA256        string         `json:"sha256"`
	SchemaVersion string         `json:"schemaVersion"`
}

func NewRawArchiveReference(archiveID RawArchiveID, kind RawArchiveKind, uri RawArchiveURI, sha256, schemaVersion string) (RawArchiveReference, error) {
	ref := RawArchiveReference{
		ArchiveID:     RawArchiveID(trim(archiveID)),
		Kind:          kind,
		URI:           RawArchiveURI(trim(uri)),
		SHA256:        strings.TrimSpace(sha256),
		SchemaVersion: strings.TrimSpace(schemaVersion),
	}
	if err := ref.Validate(); err != nil {
		return RawArchiveReference{}, err
	}
	return ref, nil
}

func (r RawArchiveReference) Validate() error {
	if trim(r.ArchiveID) == "" {
		return fmt.Errorf("raw archive id is required")
	}
	if !validRawArchiveKind(r.Kind) {
		return fmt.Errorf("unsupported raw archive kind: %q", r.Kind)
	}
	if trim(r.URI) == "" {
		return fmt.Errorf("raw archive uri is required")
	}
	if r.SHA256 == "" {
		return fmt.Errorf("raw archive sha256 digest is required")
	}
	if r.SchemaVersion == "" {
		return fmt.Errorf("raw archive schema version is required")
	}
	return nil
}

type ErrorClassification string

const (
	ErrorBusinessRejected       ErrorClassification = "BUSINESS_REJECTED"
	ErrorRetryableTechnical     ErrorClassification = "RETRYABLE_TECHNICAL_ERROR"
	ErrorNonRetryableTechnical  ErrorClassification = "NON_RETRYABLE_TECHNICAL_ERROR"
	ErrorAmbiguousResult        ErrorClassification = "AMBIGUOUS_RESULT"
	ErrorProviderRuleChanged    ErrorClassification = "PROVIDER_RULE_CHANGED"
	ErrorProviderStateConflict  ErrorClassification = "PROVIDER_STATE_CONFLICT"
	ErrorUnmappedProviderStatus ErrorClassification = "UNMAPPED_PROVIDER_STATUS"
)

type MappingConfidenceLevel string

const (
	MappingConfidenceExact       MappingConfidenceLevel = "EXACT"
	MappingConfidenceHigh        MappingConfidenceLevel = "HIGH"
	MappingConfidenceMedium      MappingConfidenceLevel = "MEDIUM"
	MappingConfidenceLow         MappingConfidenceLevel = "LOW"
	MappingConfidenceQuarantined MappingConfidenceLevel = "QUARANTINED"
)

type MappingConfidence struct {
	Level  MappingConfidenceLevel `json:"level"`
	Score  float64                `json:"score"`
	Reason string                 `json:"reason,omitempty"`
}

func NewMappingConfidence(level MappingConfidenceLevel, score float64, reason string) (MappingConfidence, error) {
	confidence := MappingConfidence{Level: level, Score: score, Reason: strings.TrimSpace(reason)}
	if err := confidence.Validate(); err != nil {
		return MappingConfidence{}, err
	}
	return confidence, nil
}

func (c MappingConfidence) Validate() error {
	if !validMappingConfidenceLevel(c.Level) {
		return fmt.Errorf("unsupported mapping confidence level: %q", c.Level)
	}
	if c.Score < 0 || c.Score > 1 {
		return fmt.Errorf("mapping confidence score must be between 0 and 1")
	}
	if (c.Level == MappingConfidenceLow || c.Level == MappingConfidenceQuarantined) && strings.TrimSpace(c.Reason) == "" {
		return fmt.Errorf("low confidence and quarantined mappings require a reason")
	}
	if c.Level == MappingConfidenceQuarantined && c.Score != 0 {
		return fmt.Errorf("quarantined mappings must have zero confidence score")
	}
	return nil
}

type ExternalProviderStatus struct {
	Identity          ProviderRequestIdentity `json:"identity"`
	RawStatusCode     string                  `json:"rawStatusCode"`
	RawStatusText     string                  `json:"rawStatusText,omitempty"`
	ProviderReference ProviderReference       `json:"providerReference,omitempty"`
	ObservedAt        time.Time               `json:"observedAt"`
	Archive           RawArchiveReference     `json:"archive"`
}

func NewExternalProviderStatus(identity ProviderRequestIdentity, rawStatusCode, rawStatusText string, providerReference ProviderReference, observedAt time.Time, archive RawArchiveReference) (ExternalProviderStatus, error) {
	status := ExternalProviderStatus{
		Identity:          identity,
		RawStatusCode:     strings.TrimSpace(rawStatusCode),
		RawStatusText:     strings.TrimSpace(rawStatusText),
		ProviderReference: ProviderReference(trim(providerReference)),
		ObservedAt:        observedAt,
		Archive:           archive,
	}
	if err := status.Validate(); err != nil {
		return ExternalProviderStatus{}, err
	}
	return status, nil
}

func (s ExternalProviderStatus) Validate() error {
	if err := s.Identity.Validate(); err != nil {
		return fmt.Errorf("invalid provider status identity: %w", err)
	}
	if s.RawStatusCode == "" {
		return fmt.Errorf("raw provider status code is required")
	}
	if s.ObservedAt.IsZero() {
		return fmt.Errorf("observed at is required")
	}
	if err := s.Archive.Validate(); err != nil {
		return fmt.Errorf("invalid raw archive reference: %w", err)
	}
	return nil
}

type InternalFactKind string

const (
	FactProviderReservationConfirmed InternalFactKind = "PROVIDER_RESERVATION_CONFIRMED"
	FactProviderReservationFailed    InternalFactKind = "PROVIDER_RESERVATION_FAILED"
	FactChannelPaymentCaptured       InternalFactKind = "CHANNEL_PAYMENT_CAPTURED"
	FactChannelRefundSettled         InternalFactKind = "CHANNEL_REFUND_SETTLED"
	FactSupplierTicketIssued         InternalFactKind = "SUPPLIER_TICKET_ISSUED"
	FactProviderBoardingAccepted     InternalFactKind = "PROVIDER_BOARDING_ACCEPTED"
)

// MappedInternalFact is the only object emitted to core domains. It contains a
// normalized fact kind, provider references, and audit pointers, never raw
// provider status codes as business state.
type MappedInternalFact struct {
	Kind              InternalFactKind    `json:"kind"`
	ProviderID        ProviderID          `json:"providerId"`
	Operation         Operation           `json:"operation"`
	BusinessRef       BusinessRef         `json:"businessRef"`
	ProviderReference ProviderReference   `json:"providerReference,omitempty"`
	OccurredAt        time.Time           `json:"occurredAt"`
	CorrelationID     CorrelationID       `json:"correlationId"`
	SourceRequestID   ProviderRequestID   `json:"sourceRequestId"`
	Archive           RawArchiveReference `json:"archive"`
}

func NewMappedInternalFact(kind InternalFactKind, status ExternalProviderStatus) (MappedInternalFact, error) {
	fact := MappedInternalFact{
		Kind:              kind,
		ProviderID:        status.Identity.ProviderID,
		Operation:         status.Identity.Operation,
		BusinessRef:       status.Identity.BusinessRef,
		ProviderReference: status.ProviderReference,
		OccurredAt:        status.ObservedAt,
		CorrelationID:     status.Identity.CorrelationID,
		SourceRequestID:   status.Identity.RequestID,
		Archive:           status.Archive,
	}
	if err := fact.Validate(); err != nil {
		return MappedInternalFact{}, err
	}
	return fact, nil
}

func (f MappedInternalFact) Validate() error {
	if !validInternalFactKind(f.Kind) {
		return fmt.Errorf("unsupported internal fact kind: %q", f.Kind)
	}
	if trim(f.ProviderID) == "" {
		return fmt.Errorf("mapped fact provider id is required")
	}
	if !validOperation(f.Operation) {
		return fmt.Errorf("mapped fact operation is invalid")
	}
	if trim(f.BusinessRef) == "" {
		return fmt.Errorf("mapped fact business reference is required")
	}
	if f.Kind != FactProviderReservationFailed && trim(f.ProviderReference) == "" {
		return fmt.Errorf("mapped fact provider reference is required for %s", f.Kind)
	}
	if f.OccurredAt.IsZero() {
		return fmt.Errorf("mapped fact occurred at is required")
	}
	if trim(f.CorrelationID) == "" {
		return fmt.Errorf("mapped fact correlation id is required")
	}
	if trim(f.SourceRequestID) == "" {
		return fmt.Errorf("mapped fact source request id is required")
	}
	if err := f.Archive.Validate(); err != nil {
		return fmt.Errorf("mapped fact archive is invalid: %w", err)
	}
	return nil
}

type NextAction string

const (
	NextActionRetry        NextAction = "RETRY"
	NextActionQueryStatus  NextAction = "QUERY_STATUS"
	NextActionManualReview NextAction = "MANUAL_REVIEW"
	NextActionStop         NextAction = "STOP"
)

type MappingDecisionKind string

const (
	DecisionMapped     MappingDecisionKind = "MAPPED"
	DecisionQuarantine MappingDecisionKind = "QUARANTINE"
	DecisionAmbiguous  MappingDecisionKind = "AMBIGUOUS"
)

type StatusMappingRule struct {
	ProviderID     ProviderID          `json:"providerId"`
	Operation      Operation           `json:"operation"`
	RawStatusCode  string              `json:"rawStatusCode"`
	FactKind       InternalFactKind    `json:"factKind,omitempty"`
	Error          ErrorClassification `json:"error,omitempty"`
	Confidence     MappingConfidence   `json:"confidence"`
	NextAction     NextAction          `json:"nextAction"`
	MappingVersion MappingVersion      `json:"mappingVersion"`
}

func NewStatusMappingRule(providerID ProviderID, operation Operation, rawStatusCode string, factKind InternalFactKind, errorClass ErrorClassification, confidence MappingConfidence, nextAction NextAction, mappingVersion MappingVersion) (StatusMappingRule, error) {
	rule := StatusMappingRule{
		ProviderID:     ProviderID(trim(providerID)),
		Operation:      operation,
		RawStatusCode:  strings.TrimSpace(rawStatusCode),
		FactKind:       factKind,
		Error:          errorClass,
		Confidence:     confidence,
		NextAction:     nextAction,
		MappingVersion: MappingVersion(trim(mappingVersion)),
	}
	if err := rule.Validate(); err != nil {
		return StatusMappingRule{}, err
	}
	return rule, nil
}

func (r StatusMappingRule) Validate() error {
	if trim(r.ProviderID) == "" {
		return fmt.Errorf("mapping rule provider id is required")
	}
	if !validOperation(r.Operation) {
		return fmt.Errorf("mapping rule operation is invalid")
	}
	if r.RawStatusCode == "" {
		return fmt.Errorf("mapping rule raw status code is required")
	}
	if err := r.Confidence.Validate(); err != nil {
		return fmt.Errorf("mapping rule confidence invalid: %w", err)
	}
	if !validNextAction(r.NextAction) {
		return fmt.Errorf("unsupported mapping next action: %q", r.NextAction)
	}
	if trim(r.MappingVersion) == "" {
		return fmt.Errorf("mapping version is required")
	}
	if r.NextAction == NextActionQueryStatus || r.NextAction == NextActionManualReview {
		if r.Error != ErrorAmbiguousResult && r.Error != ErrorProviderStateConflict {
			return fmt.Errorf("query status or manual review rules must classify ambiguous result or provider state conflict")
		}
		return nil
	}
	if !validInternalFactKind(r.FactKind) {
		return fmt.Errorf("mapped rules require an internal fact kind")
	}
	if r.Error != "" && !validErrorClassification(r.Error) {
		return fmt.Errorf("unsupported error classification: %q", r.Error)
	}
	return nil
}

type StatusMappingCatalog struct {
	rules map[string]StatusMappingRule
}

func NewStatusMappingCatalog(rules []StatusMappingRule) (StatusMappingCatalog, error) {
	catalog := StatusMappingCatalog{rules: make(map[string]StatusMappingRule, len(rules))}
	for _, rule := range rules {
		if err := rule.Validate(); err != nil {
			return StatusMappingCatalog{}, err
		}
		key := mappingRuleKey(rule.ProviderID, rule.Operation, rule.RawStatusCode)
		if _, exists := catalog.rules[key]; exists {
			return StatusMappingCatalog{}, fmt.Errorf("duplicate provider status mapping rule: %s", key)
		}
		catalog.rules[key] = rule
	}
	return catalog, nil
}

func (c StatusMappingCatalog) Map(status ExternalProviderStatus) (ProviderMappingDecision, error) {
	if err := status.Validate(); err != nil {
		return ProviderMappingDecision{}, err
	}
	rule, exists := c.rules[mappingRuleKey(status.Identity.ProviderID, status.Identity.Operation, status.RawStatusCode)]
	if !exists {
		confidence, _ := NewMappingConfidence(MappingConfidenceQuarantined, 0, "no provider status mapping rule")
		return ProviderMappingDecision{
			Kind:       DecisionQuarantine,
			Error:      ErrorUnmappedProviderStatus,
			Confidence: confidence,
			NextAction: NextActionManualReview,
			Quarantine: &UnmappedStatusQuarantine{
				ProviderID:    status.Identity.ProviderID,
				Operation:     status.Identity.Operation,
				RawStatusCode: status.RawStatusCode,
				RawStatusText: status.RawStatusText,
				BusinessRef:   status.Identity.BusinessRef,
				CorrelationID: status.Identity.CorrelationID,
				Archive:       status.Archive,
				Reason:        "unmapped provider status code",
				QuarantinedAt: status.ObservedAt,
			},
		}, nil
	}
	if rule.NextAction == NextActionQueryStatus || rule.NextAction == NextActionManualReview {
		return ProviderMappingDecision{
			Kind:           DecisionAmbiguous,
			Error:          rule.Error,
			Confidence:     rule.Confidence,
			NextAction:     rule.NextAction,
			MappingVersion: rule.MappingVersion,
			Review: &ReviewDirective{
				ProviderID:    status.Identity.ProviderID,
				Operation:     status.Identity.Operation,
				BusinessRef:   status.Identity.BusinessRef,
				CorrelationID: status.Identity.CorrelationID,
				Archive:       status.Archive,
				Reason:        rule.Confidence.Reason,
			},
		}, nil
	}
	fact, err := NewMappedInternalFact(rule.FactKind, status)
	if err != nil {
		return ProviderMappingDecision{}, err
	}
	return ProviderMappingDecision{
		Kind:           DecisionMapped,
		Fact:           &fact,
		Error:          rule.Error,
		Confidence:     rule.Confidence,
		NextAction:     rule.NextAction,
		MappingVersion: rule.MappingVersion,
	}, nil
}

type ProviderMappingDecision struct {
	Kind           MappingDecisionKind       `json:"kind"`
	Fact           *MappedInternalFact       `json:"fact,omitempty"`
	Quarantine     *UnmappedStatusQuarantine `json:"quarantine,omitempty"`
	Review         *ReviewDirective          `json:"review,omitempty"`
	Error          ErrorClassification       `json:"error,omitempty"`
	Confidence     MappingConfidence         `json:"confidence"`
	NextAction     NextAction                `json:"nextAction"`
	MappingVersion MappingVersion            `json:"mappingVersion,omitempty"`
}

// UnmappedStatusQuarantine is the explicit rejection path for external status
// codes without a reviewed mapping. Downstream core domains should receive this
// as an operations/review input, not as a business fact.
type UnmappedStatusQuarantine struct {
	ProviderID    ProviderID          `json:"providerId"`
	Operation     Operation           `json:"operation"`
	RawStatusCode string              `json:"rawStatusCode"`
	RawStatusText string              `json:"rawStatusText,omitempty"`
	BusinessRef   BusinessRef         `json:"businessRef"`
	CorrelationID CorrelationID       `json:"correlationId"`
	Archive       RawArchiveReference `json:"archive"`
	Reason        string              `json:"reason"`
	QuarantinedAt time.Time           `json:"quarantinedAt"`
}

// ReviewDirective represents retry/query/manual-review follow-up for ambiguous
// external outcomes. Side-effecting operations should query status or escalate;
// they should not be blindly replayed.
type ReviewDirective struct {
	ProviderID    ProviderID          `json:"providerId"`
	Operation     Operation           `json:"operation"`
	BusinessRef   BusinessRef         `json:"businessRef"`
	CorrelationID CorrelationID       `json:"correlationId"`
	Archive       RawArchiveReference `json:"archive"`
	Reason        string              `json:"reason"`
}

type RetryDirective struct {
	ProviderID     ProviderID          `json:"providerId"`
	Operation      Operation           `json:"operation"`
	BusinessRef    BusinessRef         `json:"businessRef"`
	CorrelationID  CorrelationID       `json:"correlationId"`
	Attempt        int                 `json:"attempt"`
	MaxAttempts    int                 `json:"maxAttempts"`
	NextAttemptAt  time.Time           `json:"nextAttemptAt"`
	Classification ErrorClassification `json:"classification"`
	Reason         string              `json:"reason"`
}

func NewRetryDirective(identity ProviderRequestIdentity, attempt, maxAttempts int, nextAttemptAt time.Time, classification ErrorClassification, reason string) (RetryDirective, error) {
	directive := RetryDirective{
		ProviderID:     identity.ProviderID,
		Operation:      identity.Operation,
		BusinessRef:    identity.BusinessRef,
		CorrelationID:  identity.CorrelationID,
		Attempt:        attempt,
		MaxAttempts:    maxAttempts,
		NextAttemptAt:  nextAttemptAt,
		Classification: classification,
		Reason:         strings.TrimSpace(reason),
	}
	if err := identity.Validate(); err != nil {
		return RetryDirective{}, err
	}
	if directive.Attempt <= 0 {
		return RetryDirective{}, fmt.Errorf("retry attempt must be positive")
	}
	if directive.MaxAttempts < directive.Attempt {
		return RetryDirective{}, fmt.Errorf("max attempts must be greater than or equal to attempt")
	}
	if directive.NextAttemptAt.IsZero() {
		return RetryDirective{}, fmt.Errorf("next retry attempt time is required")
	}
	if directive.Classification != ErrorRetryableTechnical && directive.Classification != ErrorAmbiguousResult {
		return RetryDirective{}, fmt.Errorf("retry directive requires retryable or ambiguous classification")
	}
	if directive.Reason == "" {
		return RetryDirective{}, fmt.Errorf("retry directive reason is required")
	}
	return directive, nil
}

func mappingRuleKey(providerID ProviderID, operation Operation, rawStatusCode string) string {
	return strings.Join([]string{string(providerID), string(operation), strings.ToUpper(strings.TrimSpace(rawStatusCode))}, ":")
}

func validOperation(operation Operation) bool {
	switch operation {
	case OperationReserveReservation, OperationConfirmReservation, OperationIssueTicket, OperationCapturePayment, OperationSettleRefund, OperationAcceptBoarding, OperationQueryStatus:
		return true
	default:
		return false
	}
}

func validRawArchiveKind(kind RawArchiveKind) bool {
	switch kind {
	case RawArchiveRequest, RawArchiveResponse, RawArchiveWebhook, RawArchiveStatus, RawArchiveError:
		return true
	default:
		return false
	}
}

func validErrorClassification(classification ErrorClassification) bool {
	switch classification {
	case ErrorBusinessRejected, ErrorRetryableTechnical, ErrorNonRetryableTechnical, ErrorAmbiguousResult, ErrorProviderRuleChanged, ErrorProviderStateConflict, ErrorUnmappedProviderStatus:
		return true
	default:
		return false
	}
}

func validMappingConfidenceLevel(level MappingConfidenceLevel) bool {
	switch level {
	case MappingConfidenceExact, MappingConfidenceHigh, MappingConfidenceMedium, MappingConfidenceLow, MappingConfidenceQuarantined:
		return true
	default:
		return false
	}
}

func validInternalFactKind(kind InternalFactKind) bool {
	switch kind {
	case FactProviderReservationConfirmed, FactProviderReservationFailed, FactChannelPaymentCaptured, FactChannelRefundSettled, FactSupplierTicketIssued, FactProviderBoardingAccepted:
		return true
	default:
		return false
	}
}

func validNextAction(action NextAction) bool {
	switch action {
	case NextActionRetry, NextActionQueryStatus, NextActionManualReview, NextActionStop:
		return true
	default:
		return false
	}
}

func trim[T ~string](value T) string {
	return strings.TrimSpace(string(value))
}
