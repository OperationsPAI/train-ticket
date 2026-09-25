package domain

import (
	"testing"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
)

func TestStatementLineAndHashAreReplayStable(t *testing.T) {
	occurredAt := time.Date(2026, time.September, 24, 13, 14, 15, 123000000, time.UTC)
	identity := StatementIdentityKey(ChannelAlipay, "2026-09-24", "CNY", "seed-v1")
	payment := FinalizeStatementLine(identity, 0, StatementLine{
		LineType: "PAYMENT", LineStatus: "PRESENT", ChannelOrderID: "cho-source",
		PaymentIntentID: "pi-source", ChannelTransactionID: "ctx-source",
		ExpectedAmount: Money{Currency: "CNY", MinorUnits: 1200},
		ActualAmount:   Money{Currency: "CNY", MinorUnits: 1200}, OccurredAt: occurredAt,
	})
	refund := FinalizeStatementLine(identity, 0, StatementLine{
		LineType: "REFUND", LineStatus: "PRESENT", ChannelOrderID: "cho-source",
		ChannelRefundID: "chr-source", PaymentIntentID: "pi-source", RefundID: "rf-source",
		ChannelRefundTransactionID: "rtx-source",
		ExpectedAmount:             Money{Currency: "CNY", MinorUnits: 400},
		ActualAmount:               Money{Currency: "CNY", MinorUnits: 400}, OccurredAt: occurredAt.Add(time.Second),
	})
	replayedPayment := FinalizeStatementLine(identity, 0, StatementLine{
		LineType: "PAYMENT", LineStatus: "PRESENT", ChannelOrderID: "cho-source",
		PaymentIntentID: "pi-source", ChannelTransactionID: "ctx-source",
		ExpectedAmount: Money{Currency: "CNY", MinorUnits: 1200},
		ActualAmount:   Money{Currency: "CNY", MinorUnits: 1200}, OccurredAt: occurredAt,
	})
	if payment.StatementLineID != replayedPayment.StatementLineID || payment.EvidenceHash != replayedPayment.EvidenceHash {
		t.Fatalf("line replay changed identity or evidence: first=%+v replay=%+v", payment, replayedPayment)
	}
	if !ids.ValidPrefixedUUIDv7(payment.StatementLineID, "csl") {
		t.Fatalf("statement line id is not UUID-v7 shaped: %s", payment.StatementLineID)
	}
	first, err := NewStatement("", ChannelAlipay, "2026-09-24", "CNY", "seed-v1", []StatementLine{refund, payment}, occurredAt.Add(time.Hour), ids.NewCorrelationID(), ids.NewCommandID())
	if err != nil {
		t.Fatal(err)
	}
	second, err := NewStatement("", ChannelAlipay, "2026-09-24", "CNY", "seed-v1", []StatementLine{replayedPayment, refund}, occurredAt.Add(2*time.Hour), ids.NewCorrelationID(), ids.NewCommandID())
	if err != nil {
		t.Fatal(err)
	}
	if first.StatementHash != second.StatementHash {
		t.Fatalf("same facts produced different statement hashes: %s != %s", first.StatementHash, second.StatementHash)
	}
	if first.Lines[0].LineType != "PAYMENT" || second.Lines[0].LineType != "PAYMENT" {
		t.Fatalf("canonical line ordering was not applied: first=%+v second=%+v", first.Lines, second.Lines)
	}
}

func TestFrozenStatementRejectsChangedHashReplay(t *testing.T) {
	now := time.Date(2026, time.September, 24, 13, 14, 15, 0, time.UTC)
	statement, err := NewStatement("", ChannelAlipay, "2026-09-24", "CNY", "seed-v1", nil, now, ids.NewCorrelationID(), ids.NewCommandID())
	if err != nil {
		t.Fatal(err)
	}
	if err := statement.Freeze(statement.StatementHash, statement.Version, now.Add(time.Minute), "ops", "daily-close", ids.NewCorrelationID(), ids.NewCommandID()); err != nil {
		t.Fatal(err)
	}
	if err := statement.Freeze("changed-hash", 1, now.Add(2*time.Minute), "ops", "replay", ids.NewCorrelationID(), ids.NewCommandID()); err == nil || err.Error() != "statement hash mismatch" {
		t.Fatalf("changed frozen replay was accepted: %v", err)
	}
}
