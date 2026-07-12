package domain

import (
	"fmt"
	"strings"
	"time"
)

type ChannelOrder struct {
	ChannelOrderID       string           `json:"channelOrderId"`
	PaymentIntentID      string           `json:"paymentIntentId"`
	BusinessRef          string           `json:"businessRef"`
	Purpose              string           `json:"purpose"`
	Channel              string           `json:"channel"`
	Amount               Money            `json:"amount"`
	Status               string           `json:"status"`
	IdempotencyKey       string           `json:"idempotencyKey"`
	RequestFingerprint   string           `json:"requestFingerprint"`
	SourceCommandID      string           `json:"sourceCommandId"`
	CorrelationID        string           `json:"correlationId"`
	ChannelTransactionID string           `json:"channelTransactionId,omitempty"`
	AcceptedAt           *time.Time       `json:"acceptedAt,omitempty"`
	CompletedAt          *time.Time       `json:"completedAt,omitempty"`
	FaultSeed            *FaultSeed       `json:"faultSeed,omitempty"`
	FaultSeedRef         string           `json:"faultSeedRef,omitempty"`
	Attempts             []ChannelAttempt `json:"attempts"`
	CreatedAt            time.Time        `json:"createdAt"`
	UpdatedAt            time.Time        `json:"updatedAt"`
	Version              int64            `json:"version"`
	events               []Event
}

func NewChannelOrder(id, pi, businessRef, purpose, channel string, amount Money, idem, sourceCmd, corr string, seed *FaultSeed, now time.Time) (*ChannelOrder, error) {
	if strings.TrimSpace(id) == "" {
		id = NewID("cho")
	}
	if strings.TrimSpace(pi) == "" || strings.TrimSpace(businessRef) == "" || strings.TrimSpace(purpose) == "" || strings.TrimSpace(idem) == "" || strings.TrimSpace(sourceCmd) == "" || strings.TrimSpace(corr) == "" {
		return nil, fmt.Errorf("required order fields are missing")
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
	fp := Hash(pi, businessRef, purpose, channel, amount.Currency, amount.MinorUnits, sourceCmd)
	o := &ChannelOrder{ChannelOrderID: id, PaymentIntentID: pi, BusinessRef: businessRef, Purpose: purpose, Channel: channel, Amount: amount, Status: StatusCreated, IdempotencyKey: idem, RequestFingerprint: fp, SourceCommandID: sourceCmd, CorrelationID: corr, FaultSeed: seed, CreatedAt: now.UTC(), UpdatedAt: now.UTC(), Version: 1}
	if seed != nil {
		o.FaultSeedRef = seed.Ref()
	}
	o.addEvent("ChannelOrderCreated", map[string]any{"channelOrderId": id, "paymentIntentId": pi, "businessRef": businessRef, "purpose": purpose, "channel": channel, "amount": amount, "idempotencyKey": idem, "requestFingerprint": fp, "sourceCommandId": sourceCmd, "faultSeed": seed, "status": StatusCreated, "createdAt": now.UTC(), "aggregateVersion": o.Version}, now, sourceCmd, corr)
	return o, nil
}

func (o *ChannelOrder) Submit(expected int64, requestFingerprint string, now time.Time) error {
	if IsTerminal(o.Status) {
		return nil
	}
	if o.Version != expected {
		return fmt.Errorf("version conflict")
	}
	if requestFingerprint != "" && requestFingerprint != o.RequestFingerprint {
		return fmt.Errorf("request fingerprint mismatch")
	}
	attempt := ChannelAttempt{AttemptNo: len(o.Attempts) + 1, AttemptType: "SUBMIT", RequestFingerprint: o.RequestFingerprint, Retryable: false, AttemptedAt: now.UTC()}
	scenario := ""
	if o.FaultSeed != nil {
		scenario = o.FaultSeed.ScenarioCode
	}
	o.Status = StatusSubmitted
	attempt.SimStatus = "SUBMITTED"
	o.Attempts = append(o.Attempts, attempt)
	o.bump(now)
	o.addEvent("ChannelOrderSubmitted", map[string]any{"channelOrderId": o.ChannelOrderID, "paymentIntentId": o.PaymentIntentID, "businessRef": o.BusinessRef, "channel": o.Channel, "amount": o.Amount, "attemptSnapshot": attempt, "faultSeed": o.FaultSeed, "status": StatusSubmitted, "submittedAt": now.UTC(), "aggregateVersion": o.Version}, now, o.SourceCommandID, o.CorrelationID)
	if scenario == "MISSED_ORDER" {
		o.Status = StatusMissed
		t := now.UTC()
		o.CompletedAt = &t
		o.bump(now)
		o.addEvent("ChannelOrderMissed", map[string]any{"channelOrderId": o.ChannelOrderID, "paymentIntentId": o.PaymentIntentID, "businessRef": o.BusinessRef, "channel": o.Channel, "amount": o.Amount, "missWindowStartAt": now.UTC(), "missWindowEndAt": now.Add(5 * time.Minute).UTC(), "faultSeed": o.FaultSeed, "status": StatusMissed, "missedAt": now.UTC(), "aggregateVersion": o.Version}, now, o.SourceCommandID, o.CorrelationID)
		return nil
	}
	digit := o.Amount.MinorUnits % 10
	if digit == 9 {
		o.Status = StatusFailed
		t := now.UTC()
		o.CompletedAt = &t
		o.bump(now)
		o.addEvent("ChannelOrderFailed", map[string]any{"channelOrderId": o.ChannelOrderID, "paymentIntentId": o.PaymentIntentID, "businessRef": o.BusinessRef, "channel": o.Channel, "amount": o.Amount, "providerErrorCode": "SIM_DECLINED", "terminalReason": "AMOUNT_TAIL_FAILURE", "retryable": false, "status": StatusFailed, "failedAt": now.UTC(), "aggregateVersion": o.Version}, now, o.SourceCommandID, o.CorrelationID)
		return nil
	}
	o.Status = StatusAccepted
	o.ChannelTransactionID = DeterministicTxn("ctx", o.ChannelOrderID)
	t := now.UTC()
	o.AcceptedAt = &t
	o.bump(now)
	o.addEvent("ChannelOrderAccepted", map[string]any{"channelOrderId": o.ChannelOrderID, "paymentIntentId": o.PaymentIntentID, "businessRef": o.BusinessRef, "channel": o.Channel, "amount": o.Amount, "channelAcceptRef": "acc-" + o.ChannelTransactionID, "attemptSnapshot": attempt, "status": StatusAccepted, "acceptedAt": now.UTC(), "aggregateVersion": o.Version}, now, o.SourceCommandID, o.CorrelationID)
	o.Status = StatusSucceeded
	o.CompletedAt = &t
	o.bump(now)
	o.addEvent("ChannelOrderSucceeded", map[string]any{"channelOrderId": o.ChannelOrderID, "paymentIntentId": o.PaymentIntentID, "businessRef": o.BusinessRef, "purpose": o.Purpose, "channel": o.Channel, "succeededAmount": o.Amount, "channelTransactionId": o.ChannelTransactionID, "faultSeed": o.FaultSeed, "status": StatusSucceeded, "succeededAt": now.UTC(), "aggregateVersion": o.Version}, now, o.SourceCommandID, o.CorrelationID)
	return nil
}

func (o *ChannelOrder) Query(expected int64, reason string, now time.Time) error {
	if !IsTerminal(o.Status) && o.Version != expected {
		return fmt.Errorf("version conflict")
	}
	attempt := ChannelAttempt{AttemptNo: len(o.Attempts) + 1, AttemptType: "QUERY", RequestFingerprint: Hash(o.ChannelOrderID, len(o.Attempts)+1, reason), SimStatus: o.Status, Retryable: false, AttemptedAt: now.UTC()}
	o.Attempts = append(o.Attempts, attempt)
	o.bump(now)
	o.addEvent("ChannelOrderQueryRecorded", map[string]any{"channelOrderId": o.ChannelOrderID, "paymentIntentId": o.PaymentIntentID, "channel": o.Channel, "attemptSnapshot": attempt, "queryReasonCode": reason, "status": o.Status, "queriedAt": now.UTC(), "aggregateVersion": o.Version}, now, o.SourceCommandID, o.CorrelationID)
	if o.Status == StatusMissed {
		o.Status = StatusSucceeded
		o.ChannelTransactionID = DeterministicTxn("ctx", o.ChannelOrderID)
		t := now.UTC()
		o.CompletedAt = &t
		o.bump(now)
		o.addEvent("ChannelOrderRecoveryDetected", map[string]any{"channelOrderId": o.ChannelOrderID, "paymentIntentId": o.PaymentIntentID, "businessRef": o.BusinessRef, "channel": o.Channel, "recoveredAmount": o.Amount, "channelTransactionId": o.ChannelTransactionID, "evidenceRef": "query:" + reason, "detectedAt": now.UTC(), "aggregateVersion": o.Version}, now, o.SourceCommandID, o.CorrelationID)
	}
	return nil
}

func (o *ChannelOrder) PullEvents() []Event { ev := o.events; o.events = nil; return ev }
func (o *ChannelOrder) bump(now time.Time)  { o.Version++; o.UpdatedAt = now.UTC() }
func (o *ChannelOrder) addEvent(t string, p any, now time.Time, cause, corr string) {
	o.events = append(o.events, Event{EventType: t, Payload: p, OccurredAt: now.UTC(), CausationID: cause, CorrelationID: corr, AggregateID: o.ChannelOrderID, Version: o.Version, MaterialHash: Hash(p)})
}
