package messaging

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/travel-insurance/application"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

type InboundHandler struct{ service *application.InsuranceService }

func NewInboundHandler(service *application.InsuranceService) *InboundHandler {
	return &InboundHandler{service: service}
}

func (h *InboundHandler) Handle(ctx context.Context, envelope kitmsg.EventEnvelope) error {
	switch envelope.EventType {
	case "AncillaryOrderItemRefunded":
		var payload struct {
			ItemID      string `json:"ancillaryOrderItemId"`
			ServiceType string `json:"serviceType"`
			RefundID    string `json:"refundRef"`
		}
		if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
			return err
		}
		if payload.ServiceType != "INSURANCE" {
			return nil
		}
		if payload.ItemID == "" || payload.RefundID == "" {
			return fmt.Errorf("insurance refund references missing")
		}
		return h.service.CancelRefundedInsuranceItem(ctx, payload.ItemID, payload.RefundID, envelope.CorrelationID, envelope.EventID)
	case "PostSalesApplied", "RefundApproved":
		return h.handleRefundApplied(ctx, envelope)
	case "TrainDelayed":
		return h.handleTrainDelayed(ctx, envelope)
	case "JourneyOrderPostSalesAdjusted":
		return h.handleJourneyAdjusted(ctx, envelope)
	default:
		return nil
	}
}

type journeyAdjusted struct {
	OrderID         string   `json:"orderId"`
	AccountID       string   `json:"accountId"`
	PostSalesCaseID string   `json:"postSalesCaseId"`
	SegmentRefs     []string `json:"segmentRefs"`
}

func (h *InboundHandler) handleJourneyAdjusted(ctx context.Context, envelope kitmsg.EventEnvelope) error {
	var payload journeyAdjusted
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return err
	}
	if payload.OrderID == "" || payload.AccountID == "" || payload.PostSalesCaseID == "" {
		return fmt.Errorf("changed journey references missing")
	}
	_, err := h.service.AdjustPoliciesForChangedJourney(ctx, application.JourneyAdjustedCommand{
		JourneyOrderID: payload.OrderID,
		AccountID:      payload.AccountID,
		CaseID:         payload.PostSalesCaseID,
		SegmentRefs:    payload.SegmentRefs,
		CorrelationID:  envelope.CorrelationID,
		CausationID:    envelope.EventID,
	})
	if errors.Is(err, application.ErrNotChangeAdjustment) {
		// The order was refunded or cancelled. Its coverage ends through the refund
		// events this service already handles, so this announcement has nothing for
		// it to do; acknowledging keeps it out of the dead letter without pretending
		// anything was adjusted.
		return nil
	}
	return err
}

type refundApplied struct {
	PolicyID        string         `json:"policyId"`
	JourneyOrderID  string         `json:"journeyOrderId"`
	OrderID         string         `json:"orderId"`
	CaseID          string         `json:"caseId"`
	PostSalesCaseID string         `json:"postSalesCaseId"`
	RefundID        string         `json:"refundId"`
	Amount          domain.Money   `json:"amount"`
	ApprovedAt      time.Time      `json:"approvedAt"`
	CaseType        string         `json:"caseType"`
	ResultSummary   map[string]any `json:"resultSummary"`
	RefundDecision  map[string]any `json:"refundDecision"`
}

func (h *InboundHandler) handleRefundApplied(ctx context.Context, envelope kitmsg.EventEnvelope) error {
	var payload refundApplied
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return nil
	}
	journeyOrderID := firstNonBlank(payload.JourneyOrderID, payload.OrderID)
	if strings.TrimSpace(payload.PolicyID) == "" && journeyOrderID == "" {
		return nil
	}
	if !endsCoverage(payload) {
		return nil
	}
	_, err := h.service.CancelPolicyForRefund(ctx, application.RefundAppliedCommand{PolicyID: payload.PolicyID, JourneyOrderID: journeyOrderID, RefundID: firstNonBlank(payload.RefundID, payload.CaseID, payload.PostSalesCaseID, envelope.EventID), CorrelationID: envelope.CorrelationID, CausationID: envelope.EventID})
	if errors.Is(err, application.ErrNotFound) {
		// An order with no policy has nothing to cancel. Most purchases carry no
		// insurance, and every refund of one arrived here, failed as "not found", was
		// classified transient, retried five times and dead lettered: measured,
		// events:post-sales:dlq held 30001 entries -- its cap -- of exactly these,
		// with failureReason "not found" and the newest envelope a PostSalesApplied.
		// Nothing missing is an error here; there was never anything to cancel.
		return nil
	}
	return err
}

// endsCoverage reports whether the applied case ends the coverage the order
// carried.
//
// `PostSalesApplied` announces all five case types through one event, and only a
// refund or a cancellation returns the ticket, so only those two end the policy.
// A compensation issues a wallet credit and keeps the ticket -- the standing
// compensation policy -- so it must not reach the cancellation path: doing so
// read the order's payment purchase reference, found none (nothing was refunded),
// classified the 404 a rule violation and dead-lettered the case five times over.
// A change or a rebook moves the journey instead, and this service is told that
// by JourneyOrderPostSalesAdjusted.
func endsCoverage(payload refundApplied) bool {
	switch strings.ToUpper(strings.TrimSpace(payload.CaseType)) {
	case "REFUND", "CANCELLATION":
		return true
	case "COMPENSATION", "CHANGE", "REBOOK":
		return false
	}
	// No case type: fall back to the refund evidence the envelope itself carries.
	return refundWasApplied(payload)
}

func refundWasApplied(payload refundApplied) bool {
	if boolField(payload.RefundDecision, "refunded") || boolField(payload.RefundDecision, "eligible") || textContains(payload.RefundDecision, "kind", "REFUND") {
		return true
	}
	if boolField(payload.ResultSummary, "refund") || boolField(payload.ResultSummary, "refunded") {
		return true
	}
	if textContains(payload.ResultSummary, "description", "refund") {
		return true
	}
	// A case id is not a refund signal: every PostSalesApplied carries one.
	return payload.RefundID != "" || payload.Amount.MinorUnits > 0
}

func boolField(values map[string]any, key string) bool {
	v, ok := values[key].(bool)
	return ok && v
}

func textContains(values map[string]any, key, needle string) bool {
	v, ok := values[key].(string)
	return ok && strings.Contains(strings.ToUpper(v), strings.ToUpper(needle))
}

type trainDelayed struct {
	SegmentRef            string    `json:"segmentRef"`
	ServiceDate           string    `json:"serviceDate"`
	DelayMinutes          int       `json:"delayMinutes"`
	OccurredAt            time.Time `json:"occurredAt"`
	EstimatedNewDeparture time.Time `json:"estimatedNewDeparture"`
}

func (h *InboundHandler) handleTrainDelayed(ctx context.Context, envelope kitmsg.EventEnvelope) error {
	var payload trainDelayed
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return nil
	}
	if payload.OccurredAt.IsZero() {
		payload.OccurredAt = payload.EstimatedNewDeparture
	}
	if payload.OccurredAt.IsZero() {
		payload.OccurredAt = envelope.OccurredAt
	}
	_, err := h.service.ProcessTrainDelayed(ctx, application.TrainDelayedCommand{SegmentRef: payload.SegmentRef, ServiceDate: payload.ServiceDate, DelayMinutes: payload.DelayMinutes, OccurredAt: payload.OccurredAt, SourceEventID: envelope.EventID, CorrelationID: envelope.CorrelationID, CausationID: envelope.EventID})
	return err
}

func firstNonBlank(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}
