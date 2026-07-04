package domain

import (
	"strings"
	"testing"
)

func TestNewProviderRequestLogValidates(t *testing.T) {
	log, err := NewProviderRequestLog(
		"log-001",
		"cr-rail",
		OperationReserveReservation,
		"idem-abc-123",
		"corr-001",
		"booking-001",
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if log.Status != RequestCreated {
		t.Fatalf("expected CREATED status, got %s", log.Status)
	}
	if log.IdempotencyKey != "idem-abc-123" {
		t.Fatalf("unexpected idempotency key: %s", log.IdempotencyKey)
	}
}

func TestNewProviderRequestLogRejectsMissingFields(t *testing.T) {
	_, err := NewProviderRequestLog("", "cr", OperationReserveReservation, "key", "corr", "ref")
	if err == nil || !strings.Contains(err.Error(), "log id is required") {
		t.Fatalf("expected log id error, got %v", err)
	}

	_, err = NewProviderRequestLog("log-1", "", OperationReserveReservation, "key", "corr", "ref")
	if err == nil || !strings.Contains(err.Error(), "provider id is required") {
		t.Fatalf("expected provider id error, got %v", err)
	}

	_, err = NewProviderRequestLog("log-1", "cr", OperationReserveReservation, "", "corr", "ref")
	if err == nil || !strings.Contains(err.Error(), "idempotency key is required") {
		t.Fatalf("expected idempotency key error, got %v", err)
	}
}

func TestProviderRequestLogStateTransitions(t *testing.T) {
	log, _ := NewProviderRequestLog("log-1", "cr", OperationReserveReservation, "key", "corr", "ref")

	if err := log.MarkSent(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if log.Status != RequestSent {
		t.Fatalf("expected SENT, got %s", log.Status)
	}
	if log.SentAt == nil {
		t.Fatal("expected sent at to be set")
	}

	ref := &ProviderRef{ConfirmationCode: "CNF-123"}
	if err := log.MarkSucceeded(ref); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if log.Status != RequestSucceeded {
		t.Fatalf("expected SUCCEEDED, got %s", log.Status)
	}
	if log.Outcome == nil || *log.Outcome != OutcomeSuccess {
		t.Fatalf("expected SUCCESS outcome")
	}
	if log.CompletedAt == nil {
		t.Fatal("expected completed at to be set")
	}
}

func TestProviderRequestLogRejectedTransition(t *testing.T) {
	log, _ := NewProviderRequestLog("log-2", "cr", OperationReserveReservation, "key", "corr", "ref")
	log.MarkSent()

	if err := log.MarkRejected(ErrorBusinessRejected, "no seats available"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if log.Status != RequestRejected {
		t.Fatalf("expected REJECTED, got %s", log.Status)
	}
	if log.ErrorType == nil || *log.ErrorType != ErrorBusinessRejected {
		t.Fatalf("expected BUSINESS_REJECTED error")
	}
	if log.ErrorMessage != "no seats available" {
		t.Fatalf("unexpected error message: %s", log.ErrorMessage)
	}
}

func TestProviderRequestLogFailedTransition(t *testing.T) {
	log, _ := NewProviderRequestLog("log-3", "cr", OperationReserveReservation, "key", "corr", "ref")
	log.MarkSent()

	if err := log.MarkFailed(ErrorNonRetryableTechnical, "invalid signature"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if log.Status != RequestFailed {
		t.Fatalf("expected FAILED, got %s", log.Status)
	}
}

func TestProviderRequestLogTimeoutAmbiguousProbeConflict(t *testing.T) {
	log, _ := NewProviderRequestLog("log-4", "cr", OperationReserveReservation, "key", "corr", "ref")
	log.MarkSent()

	if err := log.MarkTimedOut(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if log.Status != RequestTimedOut {
		t.Fatalf("expected TIMED_OUT, got %s", log.Status)
	}

	if err := log.MarkAmbiguous(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if log.Status != RequestAmbiguous {
		t.Fatalf("expected AMBIGUOUS, got %s", log.Status)
	}

	if err := log.MarkProbing(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if log.Status != RequestProbing {
		t.Fatalf("expected PROBING, got %s", log.Status)
	}

	if err := log.MarkConflict("provider state does not match"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if log.Status != RequestConflict {
		t.Fatalf("expected CONFLICT, got %s", log.Status)
	}
	if log.ErrorType == nil || *log.ErrorType != ErrorProviderStateConflict {
		t.Fatalf("expected PROVIDER_STATE_CONFLICT")
	}
}

func TestProviderRequestLogRetryChain(t *testing.T) {
	log, _ := NewProviderRequestLog("log-5", "cr", OperationReserveReservation, "key", "corr", "ref")
	log.MarkSent()

	if err := log.MarkRetrying(1); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if log.Status != RequestRetrying {
		t.Fatalf("expected RETRYING, got %s", log.Status)
	}
	if len(log.RetryAttempts) != 1 {
		t.Fatalf("expected 1 retry attempt")
	}

	log.MarkSent()
	ref := &ProviderRef{ConfirmationCode: "CNF-456"}
	log.MarkSucceeded(ref)
	if log.Status != RequestSucceeded {
		t.Fatalf("expected SUCCEEDED after retry, got %s", log.Status)
	}
}

func TestProviderRequestLogRejectsInvalidTransitions(t *testing.T) {
	log, _ := NewProviderRequestLog("log-6", "cr", OperationReserveReservation, "key", "corr", "ref")

	if err := log.MarkSucceeded(nil); err == nil {
		t.Fatal("expected error marking succeeded from CREATED")
	}

	if err := log.MarkTimedOut(); err == nil {
		t.Fatal("expected error marking timed out from CREATED")
	}
}

func TestProviderRequestLogAttachRawArchive(t *testing.T) {
	log, _ := NewProviderRequestLog("log-7", "cr", OperationReserveReservation, "key", "corr", "ref")

	archive := RawArchive{
		RawRequest:  `{"action":"reserve"}`,
		RawResponse: `{"status":"confirmed"}`,
	}
	log.AttachRawArchive(archive)
	if log.RawArchive == nil {
		t.Fatal("expected raw archive to be attached")
	}
	if log.RawArchive.RawRequest != `{"action":"reserve"}` {
		t.Fatalf("unexpected raw request")
	}
}
