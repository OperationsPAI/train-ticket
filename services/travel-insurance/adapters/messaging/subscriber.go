package messaging

import (
	"context"
	"encoding/json"
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
	case "JourneyOrderCreated", "JourneyOrderConfirmed":
		return h.handleJourneyOrder(ctx, envelope)
	case "PostSalesApplied", "RefundApproved":
		return h.handleRefundApplied(ctx, envelope)
	case "TrainDelayed":
		return h.handleTrainDelayed(ctx, envelope)
	default:
		return nil
	}
}

type travelerRefEntry struct {
	TravelerID   string `json:"travelerId"`
	TravelerType string `json:"travelerType"`
}

type monetarySummary struct {
	Total domain.Money `json:"total"`
}

type journeyOrder struct {
	JourneyOrderID  string             `json:"journeyOrderId"`
	OrderID         string             `json:"orderId"`
	AccountID       string             `json:"accountId"`
	TravelerRef     string             `json:"travelerRef"`
	TravelerRefs    []travelerRefEntry `json:"travelerRefs"`
	SegmentRefs     []string           `json:"segmentRefs"`
	PaymentIntentID string             `json:"paymentIntentId"`
	MonetarySummary *monetarySummary   `json:"monetarySummary"`
	CreatedAt       time.Time          `json:"createdAt"`
	ConfirmedAt     time.Time          `json:"confirmedAt"`
}

func (h *InboundHandler) handleJourneyOrder(ctx context.Context, envelope kitmsg.EventEnvelope) error {
	var payload journeyOrder
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return nil
	}
	orderID := firstNonBlank(payload.JourneyOrderID, payload.OrderID)
	if orderID == "" || payload.AccountID == "" || len(payload.SegmentRefs) == 0 {
		return nil
	}
	travelerRef := resolveTravelerRef(payload)
	if travelerRef == "" {
		return nil
	}
	paymentIntentID := payload.PaymentIntentID
	if paymentIntentID == "" {
		paymentIntentID = "auto-pi:" + orderID
	}
	start := payload.ConfirmedAt
	if start.IsZero() {
		start = payload.CreatedAt
	}
	if start.IsZero() {
		start = envelope.OccurredAt
	}
	_, err := h.service.IssuePolicy(ctx, application.IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: orderID, AncillaryOrderItemID: "auto-offer:" + orderID, AccountID: payload.AccountID, TravelerRef: travelerRef, SegmentRefs: payload.SegmentRefs, PaymentIntentID: paymentIntentID, CoverageStartAt: start.UTC(), CoverageEndAt: start.UTC().Add(48 * time.Hour), CorrelationID: envelope.CorrelationID, CausationID: envelope.EventID})
	return err
}

func resolveTravelerRef(payload journeyOrder) string {
	if ref := strings.TrimSpace(payload.TravelerRef); ref != "" {
		return ref
	}
	if len(payload.TravelerRefs) > 0 {
		if id := strings.TrimSpace(payload.TravelerRefs[0].TravelerID); id != "" {
			return id
		}
	}
	return ""
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
	if !refundWasApplied(payload) {
		return nil
	}
	_, err := h.service.CancelPolicyForRefund(ctx, application.RefundAppliedCommand{PolicyID: payload.PolicyID, JourneyOrderID: journeyOrderID, RefundID: firstNonBlank(payload.RefundID, payload.CaseID, payload.PostSalesCaseID, envelope.EventID), CorrelationID: envelope.CorrelationID, CausationID: envelope.EventID})
	return err
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
	return payload.RefundID != "" || payload.Amount.MinorUnits > 0 || payload.CaseID != "" || payload.PostSalesCaseID != ""
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
