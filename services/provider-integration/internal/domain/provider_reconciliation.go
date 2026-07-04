package domain

import (
	"fmt"
	"strings"
	"time"
)

// ReconciliationBatchStatus tracks the lifecycle of a reconciliation batch.
type ReconciliationBatchStatus string

const (
	BatchImported  ReconciliationBatchStatus = "IMPORTED"
	BatchMatched   ReconciliationBatchStatus = "MATCHED"
	BatchCompleted ReconciliationBatchStatus = "COMPLETED"
	BatchFailed    ReconciliationBatchStatus = "FAILED"
)

// ReconciliationRecordType distinguishes different types of reconciliation records.
type ReconciliationRecordType string

const (
	RecordReservation ReconciliationRecordType = "RESERVATION"
	RecordCredential  ReconciliationRecordType = "CREDENTIAL"
	RecordPayment     ReconciliationRecordType = "PAYMENT"
	RecordRefund      ReconciliationRecordType = "REFUND"
	RecordSettlement  ReconciliationRecordType = "SETTLEMENT"
)

// ReconciliationRecord represents a single line item from a provider statement.
type ReconciliationRecord struct {
	RecordKey       string
	RecordType      ReconciliationRecordType
	ProviderRef     string
	PlatformRef     string
	ProviderAmount  string
	PlatformAmount  string
	ProviderStatus  string
	PlatformStatus  string
	Matched         bool
	DiffDescription string
}

// ProviderReconciliationBatch is the aggregate root for importing and matching
// provider settlement statements. It generates reconciliation inputs or state
// conflict cases but never directly modifies business aggregates.
type ProviderReconciliationBatch struct {
	BatchID         string
	ProviderID      ProviderID
	StatementPeriod string
	FileHash        string
	FileName        string
	Status          ReconciliationBatchStatus
	Records         []ReconciliationRecord
	ImportedAt      time.Time
	CompletedAt     *time.Time
	ErrorMessage    string
}

// NewProviderReconciliationBatch creates a validated ProviderReconciliationBatch.
func NewProviderReconciliationBatch(batchID string, providerID ProviderID, statementPeriod string, fileHash string, fileName string) (ProviderReconciliationBatch, error) {
	batch := ProviderReconciliationBatch{
		BatchID:         strings.TrimSpace(batchID),
		ProviderID:      ProviderID(strings.TrimSpace(string(providerID))),
		StatementPeriod: strings.TrimSpace(statementPeriod),
		FileHash:        strings.TrimSpace(fileHash),
		FileName:        strings.TrimSpace(fileName),
		Status:          BatchImported,
		Records:         nil,
		ImportedAt:      timeNow().UTC(),
	}
	if err := batch.Validate(); err != nil {
		return ProviderReconciliationBatch{}, err
	}
	return batch, nil
}

func (b ProviderReconciliationBatch) Validate() error {
	if strings.TrimSpace(b.BatchID) == "" {
		return fmt.Errorf("batch id is required")
	}
	if strings.TrimSpace(string(b.ProviderID)) == "" {
		return fmt.Errorf("provider id is required")
	}
	if strings.TrimSpace(b.StatementPeriod) == "" {
		return fmt.Errorf("statement period is required")
	}
	if strings.TrimSpace(b.FileHash) == "" {
		return fmt.Errorf("file hash is required")
	}
	if !validReconciliationBatchStatus(b.Status) {
		return fmt.Errorf("unsupported reconciliation batch status: %q", b.Status)
	}
	return nil
}

func (b *ProviderReconciliationBatch) AddRecord(record ReconciliationRecord) error {
	if b.Status != BatchImported {
		return fmt.Errorf("cannot add records from status %q", b.Status)
	}
	b.Records = append(b.Records, record)
	return nil
}

func (b *ProviderReconciliationBatch) MatchRecords() error {
	if b.Status != BatchImported {
		return fmt.Errorf("cannot match records from status %q", b.Status)
	}
	b.Status = BatchMatched
	return nil
}

func (b *ProviderReconciliationBatch) Complete() error {
	if b.Status != BatchMatched {
		return fmt.Errorf("cannot complete batch from status %q", b.Status)
	}
	now := timeNow().UTC()
	b.Status = BatchCompleted
	b.CompletedAt = &now
	return nil
}

func (b *ProviderReconciliationBatch) MarkFailed(err error) {
	b.Status = BatchFailed
	b.ErrorMessage = err.Error()
}

func (b ProviderReconciliationBatch) UnmatchedRecords() []ReconciliationRecord {
	var unmatched []ReconciliationRecord
	for _, r := range b.Records {
		if !r.Matched {
			unmatched = append(unmatched, r)
		}
	}
	return unmatched
}

func validReconciliationBatchStatus(value ReconciliationBatchStatus) bool {
	switch value {
	case BatchImported, BatchMatched, BatchCompleted, BatchFailed:
		return true
	default:
		return false
	}
}
