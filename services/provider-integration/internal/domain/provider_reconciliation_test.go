package domain

import (
	"errors"
	"strings"
	"testing"
)

func TestNewProviderReconciliationBatchValidates(t *testing.T) {
	batch, err := NewProviderReconciliationBatch(
		"batch-001",
		"cr-rail",
		"2026-06",
		"sha256-abc123",
		"settlement_june_2026.csv",
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if batch.Status != BatchImported {
		t.Fatalf("expected IMPORTED, got %s", batch.Status)
	}
	if batch.ProviderID != "cr-rail" {
		t.Fatalf("unexpected provider id: %s", batch.ProviderID)
	}
	if batch.FileHash != "sha256-abc123" {
		t.Fatalf("unexpected file hash: %s", batch.FileHash)
	}
}

func TestNewProviderReconciliationBatchRejectsMissingFields(t *testing.T) {
	_, err := NewProviderReconciliationBatch("", "cr", "2026-06", "hash", "file.csv")
	if err == nil || !strings.Contains(err.Error(), "batch id is required") {
		t.Fatalf("expected batch id error, got %v", err)
	}

	_, err = NewProviderReconciliationBatch("batch-1", "", "2026-06", "hash", "file.csv")
	if err == nil || !strings.Contains(err.Error(), "provider id is required") {
		t.Fatalf("expected provider id error, got %v", err)
	}

	_, err = NewProviderReconciliationBatch("batch-1", "cr", "", "hash", "file.csv")
	if err == nil || !strings.Contains(err.Error(), "statement period is required") {
		t.Fatalf("expected statement period error, got %v", err)
	}

	_, err = NewProviderReconciliationBatch("batch-1", "cr", "2026-06", "", "file.csv")
	if err == nil || !strings.Contains(err.Error(), "file hash is required") {
		t.Fatalf("expected file hash error, got %v", err)
	}
}

func TestProviderReconciliationBatchAddAndMatchRecords(t *testing.T) {
	batch, _ := NewProviderReconciliationBatch("batch-002", "cr", "2026-06", "hash", "file.csv")

	record := ReconciliationRecord{
		RecordKey:      "rec-001",
		RecordType:     RecordReservation,
		ProviderRef:    "CNF-001",
		PlatformRef:    "booking-001",
		ProviderAmount: "100.00",
		PlatformAmount: "100.00",
		ProviderStatus: "confirmed",
		PlatformStatus: "confirmed",
		Matched:        true,
	}
	if err := batch.AddRecord(record); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(batch.Records) != 1 {
		t.Fatalf("expected 1 record, got %d", len(batch.Records))
	}

	if err := batch.MatchRecords(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if batch.Status != BatchMatched {
		t.Fatalf("expected MATCHED, got %s", batch.Status)
	}

	if err := batch.Complete(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if batch.Status != BatchCompleted {
		t.Fatalf("expected COMPLETED, got %s", batch.Status)
	}
}

func TestProviderReconciliationBatchMarkFailed(t *testing.T) {
	batch, _ := NewProviderReconciliationBatch("batch-003", "cr", "2026-06", "hash", "file.csv")
	batch.MarkFailed(errors.New("parse error at line 42"))
	if batch.Status != BatchFailed {
		t.Fatalf("expected FAILED, got %s", batch.Status)
	}
	if batch.ErrorMessage != "parse error at line 42" {
		t.Fatalf("unexpected error message: %s", batch.ErrorMessage)
	}
}

func TestProviderReconciliationBatchUnmatchedRecords(t *testing.T) {
	batch, _ := NewProviderReconciliationBatch("batch-004", "cr", "2026-06", "hash", "file.csv")

	batch.AddRecord(ReconciliationRecord{
		RecordKey:  "rec-001",
		RecordType: RecordReservation,
		Matched:    true,
	})
	batch.AddRecord(ReconciliationRecord{
		RecordKey:  "rec-002",
		RecordType: RecordPayment,
		Matched:    false,
	})
	batch.AddRecord(ReconciliationRecord{
		RecordKey:  "rec-003",
		RecordType: RecordRefund,
		Matched:    false,
	})

	unmatched := batch.UnmatchedRecords()
	if len(unmatched) != 2 {
		t.Fatalf("expected 2 unmatched records, got %d", len(unmatched))
	}
}

func TestProviderReconciliationBatchRejectsInvalidTransitions(t *testing.T) {
	batch, _ := NewProviderReconciliationBatch("batch-005", "cr", "2026-06", "hash", "file.csv")

	batch.MatchRecords()
	err := batch.AddRecord(ReconciliationRecord{RecordKey: "rec-001"})
	if err == nil || !strings.Contains(err.Error(), "cannot add records") {
		t.Fatalf("expected error adding records after matching, got %v", err)
	}

	batch2, _ := NewProviderReconciliationBatch("batch-006", "cr", "2026-06", "hash", "file.csv")
	if err := batch2.Complete(); err == nil {
		t.Fatal("expected error completing from IMPORTED")
	}
}
