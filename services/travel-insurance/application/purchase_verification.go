package application

import (
	"context"
	"encoding/json"
	"fmt"
	"go.opentelemetry.io/otel/propagation"
	"net/http"
	"net/url"
	"slices"
	"time"

	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

type PurchaseVerifier struct {
	Client          *http.Client
	BaseURLTemplate string
}

func (v PurchaseVerifier) read(ctx context.Context, service, path, correlation string, result any) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, fmt.Sprintf(v.BaseURLTemplate, service)+path, nil)
	if err != nil {
		return err
	}
	request.Header.Set("X-Correlation-Id", correlation)
	propagation.TraceContext{}.Inject(ctx, propagation.HeaderCarrier(request.Header))
	response, err := v.Client.Do(request)
	if err != nil {
		return fmt.Errorf("insurance purchase lookup: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("%w: %s purchase reference returned %d", ErrDomainRule, service, response.StatusCode)
	}
	return json.NewDecoder(response.Body).Decode(result)
}

func (v PurchaseVerifier) Verify(ctx context.Context, cmd IssuePolicyCommand, premium domain.Money) error {
	var order struct {
		OrderID      string   `json:"orderId"`
		AccountID    string   `json:"accountId"`
		Status       string   `json:"status"`
		TravelerRefs []string `json:"travelerRefs"`
		Segments     []struct {
			SegmentRef string    `json:"segmentRef"`
			Departure  time.Time `json:"departureTime"`
			Arrival    time.Time `json:"arrivalTime"`
		} `json:"segments"`
	}
	if err := v.read(ctx, "journey-order", "/api/v1/journey-orders/"+url.PathEscape(cmd.JourneyOrderID), cmd.CorrelationID, &order); err != nil {
		return err
	}
	if order.OrderID != cmd.JourneyOrderID || order.AccountID != cmd.AccountID || order.Status != "CONFIRMED" || !slices.Contains(order.TravelerRefs, cmd.TravelerRef) {
		return fmt.Errorf("%w: confirmed order and traveler are required for insurance", ErrDomainRule)
	}
	var start, end time.Time
	for _, ref := range cmd.SegmentRefs {
		found := false
		for _, segment := range order.Segments {
			if segment.SegmentRef == ref {
				found = true
				if start.IsZero() || segment.Departure.Before(start) {
					start = segment.Departure
				}
				if segment.Arrival.After(end) {
					end = segment.Arrival
				}
			}
		}
		if !found {
			return fmt.Errorf("%w: insurance segment is outside order", ErrDomainRule)
		}
	}
	if !cmd.CoverageStartAt.Equal(start) || !cmd.CoverageEndAt.Equal(end) || !time.Now().Before(start) {
		return fmt.Errorf("%w: coverage must match the future purchased journey", ErrDomainRule)
	}
	var item struct {
		ID          string       `json:"ancillaryOrderItemId"`
		ServiceType string       `json:"serviceType"`
		Order       string       `json:"journeyOrderId"`
		Traveler    string       `json:"travelerRef"`
		Segment     string       `json:"segmentRef"`
		Entitlement string       `json:"entitlementRef"`
		Status      string       `json:"status"`
		Amount      domain.Money `json:"payableAmount"`
	}
	if err := v.read(ctx, "ancillary-service", "/api/v1/ancillary-order-items/"+url.PathEscape(cmd.AncillaryOrderItemID), cmd.CorrelationID, &item); err != nil {
		return err
	}
	if item.ID != cmd.AncillaryOrderItemID || item.ServiceType != "INSURANCE" || item.Order != cmd.JourneyOrderID || item.Traveler != cmd.TravelerRef || len(cmd.SegmentRefs) != 1 || item.Segment != cmd.SegmentRefs[0] || item.Status != "CONFIRMED" || item.Amount != premium {
		return fmt.Errorf("%w: confirmed insurance purchase does not match premium or traveler", ErrDomainRule)
	}
	var ticket struct {
		ID       string `json:"entitlementId"`
		Order    string `json:"journeyOrderId"`
		Traveler string `json:"travelerRef"`
		Segment  string `json:"segmentRef"`
		Status   string `json:"status"`
	}
	if err := v.read(ctx, "entitlement-ticketing", "/api/v1/entitlements/"+url.PathEscape(item.Entitlement), cmd.CorrelationID, &ticket); err != nil {
		return err
	}
	if ticket.ID != item.Entitlement || ticket.Order != cmd.JourneyOrderID || ticket.Traveler != cmd.TravelerRef || ticket.Segment != item.Segment || ticket.Status != "ISSUED" {
		return fmt.Errorf("%w: issued traveler ticket is required", ErrDomainRule)
	}
	var payment struct {
		ID          string       `json:"paymentIntentId"`
		BusinessRef string       `json:"businessRef"`
		PayerRef    string       `json:"payerRef"`
		Purpose     string       `json:"purpose"`
		Status      string       `json:"status"`
		Captured    domain.Money `json:"capturedAmount"`
		Refunded    domain.Money `json:"refundedAmount"`
	}
	if err := v.read(ctx, "payment", "/api/v1/payment-intents/"+url.PathEscape(cmd.PaymentIntentID), cmd.CorrelationID, &payment); err != nil {
		return err
	}
	if payment.ID != cmd.PaymentIntentID || payment.BusinessRef != item.ID || payment.PayerRef != cmd.AccountID || payment.Purpose != "ancillary" || payment.Status != "CAPTURED" || payment.Captured != premium || payment.Refunded.MinorUnits != 0 {
		return fmt.Errorf("%w: insurance premium has not been captured for the purchase", ErrDomainRule)
	}
	return nil
}

func (v PurchaseVerifier) VerifyRefund(ctx context.Context, policy domain.Policy, refundID string) error {
	var refund struct {
		ID        string       `json:"refundId"`
		PaymentID string       `json:"paymentIntentId"`
		Business  string       `json:"businessCaseRef"`
		Status    string       `json:"status"`
		Amount    domain.Money `json:"amount"`
	}
	if err := v.read(ctx, "payment", "/api/v1/refunds/"+url.PathEscape(refundID), "", &refund); err != nil {
		return err
	}
	if refund.ID != refundID || refund.PaymentID != policy.PaymentIntentID || refund.Business != "ancillary:"+policy.AncillaryOrderItemID || refund.Status != "SETTLED" || refund.Amount != policy.Premium {
		return fmt.Errorf("%w: settled refund does not match policy premium", ErrDomainRule)
	}
	return nil
}

func (v PurchaseVerifier) ResolveJourneyCoverage(ctx context.Context, journeyOrderID, caseID string) (JourneyCoverage, error) {
	var change struct {
		CaseID         string `json:"caseId"`
		JourneyOrderID string `json:"journeyOrderId"`
		CaseType       string `json:"caseType"`
		Status         string `json:"status"`
		Scope          struct {
			SegmentRefs  []string `json:"segmentRefs"`
			TravelerRefs []string `json:"travelerRefs"`
		} `json:"scope"`
		ChangeOperations map[string]struct {
			Itinerary struct {
				Legs []struct {
					SegmentRef string    `json:"serviceSegmentRef"`
					Departure  time.Time `json:"departureTime"`
					Arrival    time.Time `json:"arrivalTime"`
				} `json:"legs"`
			} `json:"itinerary"`
		} `json:"changeOperations"`
	}
	if err := v.read(ctx, "post-sales", "/api/v1/post-sales-cases/"+url.PathEscape(caseID), "", &change); err != nil {
		return JourneyCoverage{}, err
	}
	if change.CaseID != caseID || change.JourneyOrderID != journeyOrderID || change.CaseType != "CHANGE" || (change.Status != "EXECUTING" && change.Status != "APPLIED") || len(change.Scope.SegmentRefs) == 0 || len(change.Scope.TravelerRefs) == 0 {
		return JourneyCoverage{}, fmt.Errorf("%w: applied change scope is required", ErrDomainRule)
	}
	target, ok := change.ChangeOperations["target"]
	if !ok || len(target.Itinerary.Legs) == 0 {
		return JourneyCoverage{}, fmt.Errorf("%w: applied change target itinerary is required", ErrDomainRule)
	}

	var order struct {
		OrderID     string   `json:"orderId"`
		AccountID   string   `json:"accountId"`
		Status      string   `json:"status"`
		SegmentRefs []string `json:"segmentRefs"`
		Segments    []struct {
			SegmentRef string    `json:"segmentRef"`
			Departure  time.Time `json:"departureTime"`
			Arrival    time.Time `json:"arrivalTime"`
		} `json:"segments"`
	}
	if err := v.read(ctx, "journey-order", "/api/v1/journey-orders/"+url.PathEscape(journeyOrderID), "", &order); err != nil {
		return JourneyCoverage{}, err
	}
	if order.OrderID != journeyOrderID || order.AccountID == "" || order.Status != "CONFIRMED" || len(order.SegmentRefs) == 0 || len(order.Segments) != len(order.SegmentRefs) {
		return JourneyCoverage{}, fmt.Errorf("%w: confirmed changed order with complete segments is required", ErrDomainRule)
	}
	current := make(map[string]struct{ start, end time.Time }, len(order.Segments))
	for _, segment := range order.Segments {
		if segment.SegmentRef == "" || segment.Departure.IsZero() || !segment.Arrival.After(segment.Departure) {
			return JourneyCoverage{}, fmt.Errorf("%w: changed order segment timing is incomplete", ErrDomainRule)
		}
		current[segment.SegmentRef] = struct{ start, end time.Time }{segment.Departure, segment.Arrival}
	}
	refs := make([]string, 0, len(target.Itinerary.Legs))
	var start, end time.Time
	for _, leg := range target.Itinerary.Legs {
		currentLeg, present := current[leg.SegmentRef]
		if !present || !currentLeg.start.Equal(leg.Departure) || !currentLeg.end.Equal(leg.Arrival) {
			return JourneyCoverage{}, fmt.Errorf("%w: target itinerary differs from current order", ErrDomainRule)
		}
		refs = append(refs, leg.SegmentRef)
		if start.IsZero() || leg.Departure.Before(start) {
			start = leg.Departure
		}
		if end.IsZero() || leg.Arrival.After(end) {
			end = leg.Arrival
		}
	}
	return JourneyCoverage{OrderID: order.OrderID, AccountID: order.AccountID, Status: order.Status,
		TravelerRefs: change.Scope.TravelerRefs, ReplacedSegmentRefs: change.Scope.SegmentRefs,
		SegmentRefs: refs, StartsAt: start, EndsAt: end}, nil
}
