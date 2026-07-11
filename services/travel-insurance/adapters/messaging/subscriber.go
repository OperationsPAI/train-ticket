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
	case "JourneyOrderConfirmed":
		return h.handleJourneyOrderConfirmed(ctx, envelope)
	case "RefundApproved":
		return h.handleRefundApproved(ctx, envelope)
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

type journeyOrderConfirmed struct {
	JourneyOrderID  string           `json:"journeyOrderId"`
	OrderID         string           `json:"orderId"`
	AccountID       string           `json:"accountId"`
	TravelerRef     string           `json:"travelerRef"`
	TravelerRefs    []travelerRefEntry `json:"travelerRefs"`
	SegmentRefs     []string         `json:"segmentRefs"`
	PaymentIntentID string           `json:"paymentIntentId"`
	MonetarySummary *monetarySummary `json:"monetarySummary"`
	ConfirmedAt     time.Time        `json:"confirmedAt"`
}

func (h *InboundHandler) handleJourneyOrderConfirmed(ctx context.Context, envelope kitmsg.EventEnvelope) error {
	var payload journeyOrderConfirmed
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
		start = envelope.OccurredAt
	}
	_, err := h.service.IssuePolicy(ctx, application.IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: orderID, AncillaryOrderItemID: "auto-offer:" + orderID, AccountID: payload.AccountID, TravelerRef: travelerRef, SegmentRefs: payload.SegmentRefs, PaymentIntentID: paymentIntentID, CoverageStartAt: start.UTC(), CoverageEndAt: start.UTC().Add(48 * time.Hour), CorrelationID: envelope.CorrelationID, CausationID: envelope.EventID})
	return err
}

func resolveTravelerRef(payload journeyOrderConfirmed) string {
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

type refundApproved struct {
	PolicyID       string       `json:"policyId"`
	JourneyOrderID string       `json:"journeyOrderId"`
	RefundID       string       `json:"refundId"`
	Amount         domain.Money `json:"amount"`
	ApprovedAt     time.Time    `json:"approvedAt"`
}

func (h *InboundHandler) handleRefundApproved(ctx context.Context, envelope kitmsg.EventEnvelope) error {
	var payload refundApproved
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return nil
	}
	if strings.TrimSpace(payload.PolicyID) == "" || payload.Amount.MinorUnits <= 0 {
		return nil
	}
	_, err := h.service.FileClaim(ctx, application.FileClaimCommand{PolicyID: payload.PolicyID, ClaimType: string(domain.ClaimServiceFailureManual), TriggerFactKey: firstNonBlank(payload.RefundID, envelope.EventID), SupportCaseID: "refund-approved:" + firstNonBlank(payload.RefundID, envelope.EventID), EvidenceRefs: []string{envelope.EventID}, ClaimedAmount: payload.Amount, CorrelationID: envelope.CorrelationID, CausationID: envelope.EventID})
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
