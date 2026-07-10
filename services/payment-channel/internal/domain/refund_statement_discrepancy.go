package domain

import (
	"fmt"
	"strings"
	"time"
)

type ChannelRefund struct {
	ChannelRefundID              string           `json:"channelRefundId"`
	RefundID                     string           `json:"refundId"`
	PaymentIntentID              string           `json:"paymentIntentId"`
	ChannelOrderID               string           `json:"channelOrderId"`
	OriginalChannelTransactionID string           `json:"originalChannelTransactionId"`
	Channel                      string           `json:"channel"`
	Amount                       Money            `json:"amount"`
	RefundReasonCode             string           `json:"refundReasonCode"`
	Status                       string           `json:"status"`
	IdempotencyKey               string           `json:"idempotencyKey"`
	RequestFingerprint           string           `json:"requestFingerprint"`
	SourceCommandID              string           `json:"sourceCommandId"`
	CorrelationID                string           `json:"correlationId"`
	ChannelRefundTransactionID   string           `json:"channelRefundTransactionId,omitempty"`
	AcceptedAt                   *time.Time       `json:"acceptedAt,omitempty"`
	CompletedAt                  *time.Time       `json:"completedAt,omitempty"`
	FaultSeed                    *FaultSeed       `json:"faultSeed,omitempty"`
	FaultSeedRef                 string           `json:"faultSeedRef,omitempty"`
	Attempts                     []ChannelAttempt `json:"attempts"`
	CreatedAt                    time.Time        `json:"createdAt"`
	UpdatedAt                    time.Time        `json:"updatedAt"`
	Version                      int64            `json:"version"`
	events                       []Event
}

func NewChannelRefund(id, refundID, pi, orderID, origTxn, channel string, amount Money, reason, idem, sourceCmd, corr string, seed *FaultSeed, now time.Time) (*ChannelRefund, error) {
	if strings.TrimSpace(id) == "" {
		id = NewID("chr")
	}
	if strings.TrimSpace(refundID) == "" || strings.TrimSpace(pi) == "" || strings.TrimSpace(orderID) == "" || strings.TrimSpace(origTxn) == "" || strings.TrimSpace(reason) == "" || strings.TrimSpace(idem) == "" || strings.TrimSpace(sourceCmd) == "" || strings.TrimSpace(corr) == "" {
		return nil, fmt.Errorf("required refund fields are missing")
	}
	if !ValidChannel(channel) {
		return nil, fmt.Errorf("unsupported channel")
	}
	if err := amount.ValidatePositive(); err != nil {
		return nil, err
	}
	if err := seed.Validate(); err != nil {
		return nil, err
	}
	fp := Hash(refundID, orderID, origTxn, amount.Currency, amount.MinorUnits)
	r := &ChannelRefund{ChannelRefundID: id, RefundID: refundID, PaymentIntentID: pi, ChannelOrderID: orderID, OriginalChannelTransactionID: origTxn, Channel: channel, Amount: amount, RefundReasonCode: reason, Status: StatusCreated, IdempotencyKey: idem, RequestFingerprint: fp, SourceCommandID: sourceCmd, CorrelationID: corr, FaultSeed: seed, CreatedAt: now.UTC(), UpdatedAt: now.UTC(), Version: 1}
	if seed != nil {
		r.FaultSeedRef = seed.Ref()
	}
	r.addEvent("ChannelRefundCreated", map[string]any{"channelRefundId": id, "refundId": refundID, "paymentIntentId": pi, "channelOrderId": orderID, "originalChannelTransactionId": origTxn, "channel": channel, "amount": amount, "refundReasonCode": reason, "idempotencyKey": idem, "requestFingerprint": fp, "sourceCommandId": sourceCmd, "faultSeed": seed, "status": StatusCreated, "createdAt": now.UTC(), "aggregateVersion": r.Version}, now, sourceCmd, corr)
	return r, nil
}
func (r *ChannelRefund) Submit(expected int64, requestFingerprint string, now time.Time) error {
	if IsTerminal(r.Status) {
		return nil
	}
	if r.Version != expected {
		return fmt.Errorf("version conflict")
	}
	if requestFingerprint != "" && requestFingerprint != r.RequestFingerprint {
		return fmt.Errorf("request fingerprint mismatch")
	}
	attempt := ChannelAttempt{AttemptNo: len(r.Attempts) + 1, AttemptType: "SUBMIT", RequestFingerprint: r.RequestFingerprint, SimStatus: "SUBMITTED", AttemptedAt: now.UTC()}
	r.Status = StatusSubmitted
	r.Attempts = append(r.Attempts, attempt)
	r.bump(now)
	r.addEvent("ChannelRefundSubmitted", map[string]any{"channelRefundId": r.ChannelRefundID, "refundId": r.RefundID, "paymentIntentId": r.PaymentIntentID, "channelOrderId": r.ChannelOrderID, "channel": r.Channel, "amount": r.Amount, "attemptSnapshot": attempt, "faultSeed": r.FaultSeed, "status": StatusSubmitted, "submittedAt": now.UTC(), "aggregateVersion": r.Version}, now, r.SourceCommandID, r.CorrelationID)
	scenario := ""
	if r.FaultSeed != nil {
		scenario = r.FaultSeed.ScenarioCode
	}
	if scenario == "MISSED_REFUND" {
		r.Status = StatusMissed
		t := now.UTC()
		r.CompletedAt = &t
		r.bump(now)
		r.addEvent("ChannelRefundMissed", map[string]any{"channelRefundId": r.ChannelRefundID, "refundId": r.RefundID, "paymentIntentId": r.PaymentIntentID, "channelOrderId": r.ChannelOrderID, "channel": r.Channel, "amount": r.Amount, "missWindowStartAt": now.UTC(), "missWindowEndAt": now.Add(5 * time.Minute).UTC(), "faultSeed": r.FaultSeed, "status": StatusMissed, "missedAt": now.UTC(), "aggregateVersion": r.Version}, now, r.SourceCommandID, r.CorrelationID)
		return nil
	}
	if r.Amount.MinorUnits%10 == 8 {
		r.Status = StatusFailed
		t := now.UTC()
		r.CompletedAt = &t
		r.bump(now)
		r.addEvent("ChannelRefundFailed", map[string]any{"channelRefundId": r.ChannelRefundID, "refundId": r.RefundID, "paymentIntentId": r.PaymentIntentID, "channelOrderId": r.ChannelOrderID, "channel": r.Channel, "amount": r.Amount, "providerErrorCode": "SIM_REFUND_DECLINED", "terminalReason": "AMOUNT_TAIL_FAILURE", "retryable": false, "status": StatusFailed, "failedAt": now.UTC(), "aggregateVersion": r.Version}, now, r.SourceCommandID, r.CorrelationID)
		return nil
	}
	r.Status = StatusSucceeded
	r.ChannelRefundTransactionID = DeterministicTxn("rtx", r.ChannelRefundID)
	t := now.UTC()
	r.AcceptedAt = &t
	r.CompletedAt = &t
	r.bump(now)
	r.addEvent("ChannelRefundSucceeded", map[string]any{"channelRefundId": r.ChannelRefundID, "refundId": r.RefundID, "paymentIntentId": r.PaymentIntentID, "channelOrderId": r.ChannelOrderID, "channel": r.Channel, "succeededAmount": r.Amount, "originalChannelTransactionId": r.OriginalChannelTransactionID, "channelRefundTransactionId": r.ChannelRefundTransactionID, "status": StatusSucceeded, "succeededAt": now.UTC(), "aggregateVersion": r.Version}, now, r.SourceCommandID, r.CorrelationID)
	return nil
}
func (r *ChannelRefund) Query(expected int64, reason string, now time.Time) error {
	if !IsTerminal(r.Status) && r.Version != expected {
		return fmt.Errorf("version conflict")
	}
	attempt := ChannelAttempt{AttemptNo: len(r.Attempts) + 1, AttemptType: "QUERY", RequestFingerprint: Hash(r.ChannelRefundID, len(r.Attempts)+1, reason), SimStatus: r.Status, AttemptedAt: now.UTC()}
	r.Attempts = append(r.Attempts, attempt)
	r.bump(now)
	r.addEvent("ChannelRefundQueryRecorded", map[string]any{"channelRefundId": r.ChannelRefundID, "refundId": r.RefundID, "paymentIntentId": r.PaymentIntentID, "channel": r.Channel, "attemptSnapshot": attempt, "queryReasonCode": reason, "status": r.Status, "queriedAt": now.UTC(), "aggregateVersion": r.Version}, now, r.SourceCommandID, r.CorrelationID)
	if r.Status == StatusMissed {
		r.Status = StatusSucceeded
		r.ChannelRefundTransactionID = DeterministicTxn("rtx", r.ChannelRefundID)
		t := now.UTC()
		r.CompletedAt = &t
		r.bump(now)
		r.addEvent("ChannelRefundRecoveryDetected", map[string]any{"channelRefundId": r.ChannelRefundID, "refundId": r.RefundID, "paymentIntentId": r.PaymentIntentID, "channelOrderId": r.ChannelOrderID, "channel": r.Channel, "recoveredAmount": r.Amount, "channelRefundTransactionId": r.ChannelRefundTransactionID, "evidenceRef": "query:" + reason, "detectedAt": now.UTC(), "aggregateVersion": r.Version}, now, r.SourceCommandID, r.CorrelationID)
	}
	return nil
}
func (r *ChannelRefund) PullEvents() []Event { ev := r.events; r.events = nil; return ev }
func (r *ChannelRefund) bump(now time.Time)  { r.Version++; r.UpdatedAt = now.UTC() }
func (r *ChannelRefund) addEvent(t string, p any, now time.Time, cause, corr string) {
	r.events = append(r.events, Event{EventType: t, Payload: p, OccurredAt: now.UTC(), CausationID: cause, CorrelationID: corr, AggregateID: r.ChannelRefundID, Version: r.Version, MaterialHash: Hash(p)})
}

type StatementLine struct {
	StatementLineID            string    `json:"statementLineId"`
	LineType                   string    `json:"lineType"`
	LineStatus                 string    `json:"lineStatus"`
	ChannelOrderID             string    `json:"channelOrderId,omitempty"`
	ChannelRefundID            string    `json:"channelRefundId,omitempty"`
	PaymentIntentID            string    `json:"paymentIntentId,omitempty"`
	RefundID                   string    `json:"refundId,omitempty"`
	ChannelTransactionID       string    `json:"channelTransactionId,omitempty"`
	ChannelRefundTransactionID string    `json:"channelRefundTransactionId,omitempty"`
	ExpectedAmount             Money     `json:"expectedAmount"`
	ActualAmount               Money     `json:"actualAmount"`
	FeeAmount                  *Money    `json:"feeAmount,omitempty"`
	OccurredAt                 time.Time `json:"occurredAt"`
	FaultInjected              bool      `json:"faultInjected"`
	FaultSeedRef               string    `json:"faultSeedRef,omitempty"`
	EvidenceHash               string    `json:"evidenceHash"`
}
type ChannelStatement struct {
	ChannelStatementID string          `json:"channelStatementId"`
	Channel            string          `json:"channel"`
	StatementDate      string          `json:"statementDate"`
	Currency           string          `json:"currency"`
	SeedVersion        string          `json:"seedVersion"`
	Status             string          `json:"status"`
	PeriodStartAt      time.Time       `json:"periodStartAt"`
	PeriodEndAt        time.Time       `json:"periodEndAt"`
	GeneratedAt        time.Time       `json:"generatedAt"`
	FrozenAt           *time.Time      `json:"frozenAt,omitempty"`
	LineCount          int             `json:"lineCount"`
	GrossPaymentAmount Money           `json:"grossPaymentAmount"`
	GrossRefundAmount  Money           `json:"grossRefundAmount"`
	FeeAmount          Money           `json:"feeAmount"`
	StatementHash      string          `json:"statementHash"`
	Lines              []StatementLine `json:"lines,omitempty"`
	CreatedAt          time.Time       `json:"createdAt"`
	UpdatedAt          time.Time       `json:"updatedAt"`
	Version            int64           `json:"version"`
	events             []Event
}

func NewStatement(id, channel, date, currency, seed string, lines []StatementLine, now time.Time, corr, cause string) (*ChannelStatement, error) {
	if strings.TrimSpace(id) == "" {
		id = NewID("chs")
	}
	if !ValidChannel(channel) {
		return nil, fmt.Errorf("unsupported channel")
	}
	if strings.TrimSpace(date) == "" || strings.TrimSpace(currency) == "" || strings.TrimSpace(seed) == "" {
		return nil, fmt.Errorf("statement fields are required")
	}
	start, err := time.Parse("2006-01-02", date)
	if err != nil {
		return nil, fmt.Errorf("statementDate must be YYYY-MM-DD")
	}
	var gp, gr int64
	for _, l := range lines {
		if l.LineType == "PAYMENT" {
			gp += l.ActualAmount.MinorUnits
		}
		if l.LineType == "REFUND" {
			gr += l.ActualAmount.MinorUnits
		}
	}
	st := &ChannelStatement{ChannelStatementID: id, Channel: channel, StatementDate: date, Currency: currency, SeedVersion: seed, Status: "GENERATED", PeriodStartAt: start.UTC(), PeriodEndAt: start.Add(24 * time.Hour).UTC(), GeneratedAt: now.UTC(), LineCount: len(lines), GrossPaymentAmount: Money{currency, gp}, GrossRefundAmount: Money{currency, gr}, FeeAmount: Money{currency, 0}, Lines: lines, CreatedAt: now.UTC(), UpdatedAt: now.UTC(), Version: 1}
	st.StatementHash = Hash(channel, date, currency, seed, lines)
	st.addEvent("ChannelStatementGenerated", map[string]any{"channelStatementId": id, "channel": channel, "statementDate": date, "currency": currency, "seedVersion": seed, "periodStartAt": st.PeriodStartAt, "periodEndAt": st.PeriodEndAt, "lineCount": st.LineCount, "grossPaymentAmount": st.GrossPaymentAmount, "grossRefundAmount": st.GrossRefundAmount, "feeAmount": st.FeeAmount, "statementHash": st.StatementHash, "status": st.Status, "generatedAt": now.UTC(), "aggregateVersion": st.Version}, now, cause, corr)
	return st, nil
}
func (s *ChannelStatement) Freeze(hash string, expected int64, now time.Time, operator, reason, corr, cause string) error {
	if s.Status == "FROZEN" {
		return nil
	}
	if s.Version != expected {
		return fmt.Errorf("version conflict")
	}
	if hash != s.StatementHash {
		return fmt.Errorf("statement hash mismatch")
	}
	s.Status = "FROZEN"
	t := now.UTC()
	s.FrozenAt = &t
	s.Version++
	s.UpdatedAt = t
	s.addEvent("ChannelStatementFrozen", map[string]any{"channelStatementId": s.ChannelStatementID, "channel": s.Channel, "statementDate": s.StatementDate, "currency": s.Currency, "seedVersion": s.SeedVersion, "lineCount": s.LineCount, "statementHash": s.StatementHash, "status": s.Status, "frozenAt": t, "aggregateVersion": s.Version}, now, cause, corr)
	return nil
}
func (s *ChannelStatement) PullEvents() []Event { ev := s.events; s.events = nil; return ev }
func (s *ChannelStatement) addEvent(t string, p any, now time.Time, cause, corr string) {
	s.events = append(s.events, Event{EventType: t, Payload: p, OccurredAt: now.UTC(), CausationID: cause, CorrelationID: corr, AggregateID: s.ChannelStatementID, Version: s.Version, MaterialHash: Hash(p)})
}

type ReconciliationDiscrepancy struct {
	DiscrepancyID               string     `json:"discrepancyId"`
	ChannelStatementID          string     `json:"channelStatementId"`
	StatementLineID             string     `json:"statementLineId,omitempty"`
	ChannelOrderID              string     `json:"channelOrderId,omitempty"`
	ChannelRefundID             string     `json:"channelRefundId,omitempty"`
	FinanceReconciliationCaseID string     `json:"financeReconciliationCaseId,omitempty"`
	DifferenceType              string     `json:"differenceType"`
	ExpectedAmount              Money      `json:"expectedAmount"`
	ActualAmount                Money      `json:"actualAmount"`
	Status                      string     `json:"status"`
	EvidenceRef                 string     `json:"evidenceRef"`
	ResolutionRef               string     `json:"resolutionRef,omitempty"`
	OpenedAt                    time.Time  `json:"openedAt"`
	ResolvedAt                  *time.Time `json:"resolvedAt,omitempty"`
	UpdatedAt                   time.Time  `json:"updatedAt"`
	Version                     int64      `json:"version"`
	events                      []Event
}

func NewDiscrepancy(id, statementID, lineID, orderID, refundID, financeID, diff string, expected, actual Money, evidence string, now time.Time, corr, cause string) (*ReconciliationDiscrepancy, error) {
	if strings.TrimSpace(id) == "" {
		id = NewID("pcd")
	}
	if strings.TrimSpace(statementID) == "" || strings.TrimSpace(diff) == "" || strings.TrimSpace(evidence) == "" {
		return nil, fmt.Errorf("required discrepancy fields are missing")
	}
	d := &ReconciliationDiscrepancy{DiscrepancyID: id, ChannelStatementID: statementID, StatementLineID: lineID, ChannelOrderID: orderID, ChannelRefundID: refundID, FinanceReconciliationCaseID: financeID, DifferenceType: diff, ExpectedAmount: expected, ActualAmount: actual, Status: "OPENED", EvidenceRef: evidence, OpenedAt: now.UTC(), UpdatedAt: now.UTC(), Version: 1}
	d.addEvent("ReconciliationDiscrepancyOpened", map[string]any{"discrepancyId": id, "channelStatementId": statementID, "statementLineId": lineID, "channelOrderId": orderID, "channelRefundId": refundID, "differenceType": diff, "expectedAmount": expected, "actualAmount": actual, "evidenceRef": evidence, "status": "OPENED", "openedAt": now.UTC(), "aggregateVersion": d.Version}, now, cause, corr)
	if financeID != "" {
		d.Version++
		d.addEvent("ReconciliationDiscrepancyLinkedToFinanceCase", map[string]any{"discrepancyId": id, "financeReconciliationCaseId": financeID, "channelStatementId": statementID, "differenceType": diff, "linkedAt": now.UTC(), "aggregateVersion": d.Version}, now, cause, corr)
	}
	return d, nil
}
func (d *ReconciliationDiscrepancy) Resolve(status, ref, operator, reason string, expected int64, now time.Time, corr, cause string) error {
	if d.Status == "RESOLVED" || d.Status == "REJECTED" {
		return nil
	}
	if expected != d.Version {
		return fmt.Errorf("version conflict")
	}
	if status != "RESOLVED" && status != "REJECTED" {
		return fmt.Errorf("resolutionStatus must be RESOLVED or REJECTED")
	}
	d.Status = status
	d.ResolutionRef = ref
	t := now.UTC()
	d.ResolvedAt = &t
	d.UpdatedAt = t
	d.Version++
	d.addEvent("ReconciliationDiscrepancyResolved", map[string]any{"discrepancyId": d.DiscrepancyID, "financeReconciliationCaseId": d.FinanceReconciliationCaseID, "channelStatementId": d.ChannelStatementID, "resolutionStatus": status, "resolutionRef": ref, "operatorRef": operator, "reasonCode": reason, "resolvedAt": t, "aggregateVersion": d.Version}, now, cause, corr)
	return nil
}
func (d *ReconciliationDiscrepancy) PullEvents() []Event { ev := d.events; d.events = nil; return ev }
func (d *ReconciliationDiscrepancy) addEvent(t string, p any, now time.Time, cause, corr string) {
	d.events = append(d.events, Event{EventType: t, Payload: p, OccurredAt: now.UTC(), CausationID: cause, CorrelationID: corr, AggregateID: d.DiscrepancyID, Version: d.Version, MaterialHash: Hash(p)})
}
