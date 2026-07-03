package domain

import (
	"testing"
	"time"
)

func TestProviderIdentityAndRawArchiveValidation(t *testing.T) {
	identity, err := NewProviderRequestIdentity(" rail-cr ", " req-1 ", OperationConfirmReservation, " idem-1 ", " corr-1 ", " segment-booking:sb-1 ")
	if err != nil {
		t.Fatalf("identity should be valid: %v", err)
	}
	if identity.ProviderID != "rail-cr" {
		t.Fatalf("provider id was not normalized: %q", identity.ProviderID)
	}
	if identity.IdempotencyScope() != "rail-cr:CONFIRM_PROVIDER_RESERVATION:idem-1" {
		t.Fatalf("unexpected idempotency scope: %s", identity.IdempotencyScope())
	}

	callback, err := NewProviderCallbackIdentity("pay-channel", "event-9", "merchant-credential:v2", "idem-cb-9", "corr-9")
	if err != nil {
		t.Fatalf("callback identity should be valid: %v", err)
	}
	if callback.InboxKey() != "pay-channel:merchant-credential:v2:event-9" {
		t.Fatalf("unexpected inbox key: %s", callback.InboxKey())
	}

	archive, err := NewRawArchiveReference("raw-1", RawArchiveWebhook, "archive://provider/raw-1", "abc123", "provider.schema.v1")
	if err != nil {
		t.Fatalf("archive should be valid: %v", err)
	}
	if archive.Kind != RawArchiveWebhook {
		t.Fatalf("unexpected archive kind: %s", archive.Kind)
	}

	if _, err := NewProviderRequestIdentity("", "req-1", OperationConfirmReservation, "idem-1", "corr-1", "segment-booking:sb-1"); err == nil {
		t.Fatalf("missing provider id should be rejected")
	}
	if _, err := NewRawArchiveReference("raw-1", "RAW_MUTABLE_PAYLOAD", "archive://provider/raw-1", "abc123", "provider.schema.v1"); err == nil {
		t.Fatalf("unsupported raw archive kind should be rejected")
	}
}

func TestMapsExternalStatusesToInternalFacts(t *testing.T) {
	catalog := mustCatalog(t)

	cases := []struct {
		name              string
		operation         Operation
		rawStatus         string
		providerReference ProviderReference
		wantFact          InternalFactKind
	}{
		{
			name:              "provider reservation confirmed",
			operation:         OperationConfirmReservation,
			rawStatus:         "CONFIRMED",
			providerReference: "PNR-123",
			wantFact:          FactProviderReservationConfirmed,
		},
		{
			name:              "provider reservation failed",
			operation:         OperationConfirmReservation,
			rawStatus:         "REJECTED",
			providerReference: "",
			wantFact:          FactProviderReservationFailed,
		},
		{
			name:              "channel payment captured",
			operation:         OperationCapturePayment,
			rawStatus:         "CAPTURED",
			providerReference: "txn-123",
			wantFact:          FactChannelPaymentCaptured,
		},
		{
			name:              "channel refund settled",
			operation:         OperationSettleRefund,
			rawStatus:         "REFUND_SUCCESS",
			providerReference: "refund-123",
			wantFact:          FactChannelRefundSettled,
		},
		{
			name:              "supplier ticket issued",
			operation:         OperationIssueTicket,
			rawStatus:         "TICKETED",
			providerReference: "ticket-123",
			wantFact:          FactSupplierTicketIssued,
		},
		{
			name:              "provider boarding accepted",
			operation:         OperationAcceptBoarding,
			rawStatus:         "BOARDED",
			providerReference: "gate-pass-123",
			wantFact:          FactProviderBoardingAccepted,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			status := mustExternalStatus(t, tc.operation, tc.rawStatus, tc.providerReference)
			decision, err := catalog.Map(status)
			if err != nil {
				t.Fatalf("mapping failed: %v", err)
			}
			if decision.Kind != DecisionMapped {
				t.Fatalf("expected mapped decision, got %s", decision.Kind)
			}
			if decision.Fact == nil {
				t.Fatalf("expected mapped fact")
			}
			if decision.Fact.Kind != tc.wantFact {
				t.Fatalf("unexpected fact: got %s want %s", decision.Fact.Kind, tc.wantFact)
			}
			if decision.Fact.BusinessRef != "segment-booking:sb-1" {
				t.Fatalf("business ref should remain a reference, got %q", decision.Fact.BusinessRef)
			}
			if decision.Fact.Archive.ArchiveID == "" {
				t.Fatalf("mapped fact must keep raw archive reference")
			}
		})
	}
}

func TestUnmappedRawStatusIsQuarantinedAndDoesNotLeakAsFact(t *testing.T) {
	catalog := mustCatalog(t)
	status := mustExternalStatus(t, OperationIssueTicket, "SUPPLIER_MAGIC_STATE", "ticket-123")

	decision, err := catalog.Map(status)
	if err != nil {
		t.Fatalf("mapping should produce quarantine decision: %v", err)
	}
	if decision.Kind != DecisionQuarantine {
		t.Fatalf("expected quarantine, got %s", decision.Kind)
	}
	if decision.Fact != nil {
		t.Fatalf("unmapped raw status must not produce a core domain fact")
	}
	if decision.Error != ErrorUnmappedProviderStatus {
		t.Fatalf("unexpected error classification: %s", decision.Error)
	}
	if decision.Quarantine == nil {
		t.Fatalf("quarantine details are required")
	}
	if decision.Quarantine.RawStatusCode != "SUPPLIER_MAGIC_STATE" {
		t.Fatalf("raw status should be retained only in quarantine: %q", decision.Quarantine.RawStatusCode)
	}
	if decision.NextAction != NextActionManualReview {
		t.Fatalf("unmapped status should require manual review, got %s", decision.NextAction)
	}
	if decision.Confidence.Level != MappingConfidenceQuarantined || decision.Confidence.Score != 0 {
		t.Fatalf("unexpected quarantine confidence: %+v", decision.Confidence)
	}
}

func TestAmbiguousStatusProducesReviewDirective(t *testing.T) {
	catalog := mustCatalog(t)
	status := mustExternalStatus(t, OperationCapturePayment, "PROCESSING", "txn-123")

	decision, err := catalog.Map(status)
	if err != nil {
		t.Fatalf("mapping ambiguous status failed: %v", err)
	}
	if decision.Kind != DecisionAmbiguous {
		t.Fatalf("expected ambiguous decision, got %s", decision.Kind)
	}
	if decision.Fact != nil {
		t.Fatalf("ambiguous status must not produce a core domain fact")
	}
	if decision.NextAction != NextActionQueryStatus {
		t.Fatalf("expected status query next action, got %s", decision.NextAction)
	}
	if decision.Error != ErrorAmbiguousResult {
		t.Fatalf("expected ambiguous result classification, got %s", decision.Error)
	}
	if decision.Review == nil || decision.Review.BusinessRef != "segment-booking:sb-1" {
		t.Fatalf("review directive should carry traceable references: %+v", decision.Review)
	}
}

func TestRetryDirectiveRequiresSafeClassification(t *testing.T) {
	identity := mustIdentity(t, OperationQueryStatus)
	_, err := NewRetryDirective(identity, 1, 3, time.Date(2026, 7, 3, 12, 5, 0, 0, time.UTC), ErrorRetryableTechnical, "provider rate limited")
	if err != nil {
		t.Fatalf("retryable technical error should allow retry directive: %v", err)
	}

	if _, err := NewRetryDirective(identity, 0, 3, time.Now(), ErrorRetryableTechnical, "provider rate limited"); err == nil {
		t.Fatalf("zero retry attempt should be rejected")
	}
	if _, err := NewRetryDirective(identity, 1, 3, time.Now(), ErrorBusinessRejected, "no seats"); err == nil {
		t.Fatalf("business rejection should not allow retry directive")
	}
}

func TestMappingRuleValidation(t *testing.T) {
	exact := mustConfidence(t, MappingConfidenceExact, 1, "")
	if _, err := NewStatusMappingRule("rail-cr", OperationConfirmReservation, "CONFIRMED", FactProviderReservationConfirmed, "", exact, NextActionStop, "map-v1"); err != nil {
		t.Fatalf("valid mapped rule rejected: %v", err)
	}

	if _, err := NewMappingConfidence(MappingConfidenceLow, 0.4, ""); err == nil {
		t.Fatalf("low confidence without reason should be rejected")
	}
	if _, err := NewMappingConfidence(MappingConfidenceQuarantined, 0.1, "unknown"); err == nil {
		t.Fatalf("quarantined confidence with non-zero score should be rejected")
	}
	if _, err := NewStatusMappingRule("rail-cr", OperationConfirmReservation, "WAIT", "", ErrorBusinessRejected, exact, NextActionQueryStatus, "map-v1"); err == nil {
		t.Fatalf("query status rule without ambiguous/conflict classification should be rejected")
	}
}

func mustCatalog(t *testing.T) StatusMappingCatalog {
	t.Helper()
	exact := mustConfidence(t, MappingConfidenceExact, 1, "")
	ambiguous := mustConfidence(t, MappingConfidenceLow, 0.35, "provider still processing; query final status")
	rules := []StatusMappingRule{
		mustRule(t, "rail-cr", OperationConfirmReservation, "CONFIRMED", FactProviderReservationConfirmed, "", exact, NextActionStop),
		mustRule(t, "rail-cr", OperationConfirmReservation, "REJECTED", FactProviderReservationFailed, ErrorBusinessRejected, exact, NextActionStop),
		mustRule(t, "rail-cr", OperationCapturePayment, "CAPTURED", FactChannelPaymentCaptured, "", exact, NextActionStop),
		mustRule(t, "rail-cr", OperationCapturePayment, "PROCESSING", "", ErrorAmbiguousResult, ambiguous, NextActionQueryStatus),
		mustRule(t, "rail-cr", OperationSettleRefund, "REFUND_SUCCESS", FactChannelRefundSettled, "", exact, NextActionStop),
		mustRule(t, "rail-cr", OperationIssueTicket, "TICKETED", FactSupplierTicketIssued, "", exact, NextActionStop),
		mustRule(t, "rail-cr", OperationAcceptBoarding, "BOARDED", FactProviderBoardingAccepted, "", exact, NextActionStop),
	}
	catalog, err := NewStatusMappingCatalog(rules)
	if err != nil {
		t.Fatalf("catalog should be valid: %v", err)
	}
	return catalog
}

func mustRule(t *testing.T, providerID ProviderID, operation Operation, rawStatus string, factKind InternalFactKind, errorClass ErrorClassification, confidence MappingConfidence, nextAction NextAction) StatusMappingRule {
	t.Helper()
	rule, err := NewStatusMappingRule(providerID, operation, rawStatus, factKind, errorClass, confidence, nextAction, "map-v1")
	if err != nil {
		t.Fatalf("rule should be valid: %v", err)
	}
	return rule
}

func mustExternalStatus(t *testing.T, operation Operation, rawStatus string, providerReference ProviderReference) ExternalProviderStatus {
	t.Helper()
	status, err := NewExternalProviderStatus(
		mustIdentity(t, operation),
		rawStatus,
		"provider status text",
		providerReference,
		time.Date(2026, 7, 3, 12, 0, 0, 0, time.UTC),
		mustArchive(t),
	)
	if err != nil {
		t.Fatalf("external status should be valid: %v", err)
	}
	return status
}

func mustIdentity(t *testing.T, operation Operation) ProviderRequestIdentity {
	t.Helper()
	identity, err := NewProviderRequestIdentity("rail-cr", "req-1", operation, "idem-1", "corr-1", "segment-booking:sb-1")
	if err != nil {
		t.Fatalf("identity should be valid: %v", err)
	}
	return identity
}

func mustArchive(t *testing.T) RawArchiveReference {
	t.Helper()
	archive, err := NewRawArchiveReference("raw-1", RawArchiveStatus, "archive://provider/raw-1", "abc123", "provider.schema.v1")
	if err != nil {
		t.Fatalf("archive should be valid: %v", err)
	}
	return archive
}

func mustConfidence(t *testing.T, level MappingConfidenceLevel, score float64, reason string) MappingConfidence {
	t.Helper()
	confidence, err := NewMappingConfidence(level, score, reason)
	if err != nil {
		t.Fatalf("confidence should be valid: %v", err)
	}
	return confidence
}
