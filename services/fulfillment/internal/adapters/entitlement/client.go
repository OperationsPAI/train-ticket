package entitlement

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"go.opentelemetry.io/otel/propagation"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/fulfillment/internal/application"
	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

const maxResponseBytes = 1 << 20

type Client struct {
	baseURL string
	http    *http.Client
}

func NewClient(baseURL string, client *http.Client) *Client {
	return &Client{baseURL: strings.TrimRight(strings.TrimSpace(baseURL), "/"), http: client}
}

func (c *Client) GetEntitlement(ctx context.Context, entitlementID domain.EntitlementRef) (application.EntitlementSnapshot, error) {
	if c == nil || c.http == nil || c.baseURL == "" {
		return application.EntitlementSnapshot{}, fmt.Errorf("%w: entitlement client is not configured", application.ErrDependencyUnavailable)
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/api/v1/entitlements/"+url.PathEscape(string(entitlementID)), nil)
	if err != nil {
		return application.EntitlementSnapshot{}, fmt.Errorf("%w: build entitlement request: %v", application.ErrDependencyUnavailable, err)
	}
	if correlationID := strings.TrimSpace(goruntime.CorrelationID(ctx)); correlationID != "" {
		request.Header.Set(goruntime.CorrelationIDHeader, correlationID)
	}
	propagation.TraceContext{}.Inject(ctx, propagation.HeaderCarrier(request.Header))

	response, err := c.http.Do(request)
	if err != nil {
		return application.EntitlementSnapshot{}, fmt.Errorf("%w: read entitlement: %v", application.ErrDependencyUnavailable, err)
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNotFound {
		return application.EntitlementSnapshot{}, application.ErrNotFound
	}
	if response.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, maxResponseBytes))
		return application.EntitlementSnapshot{}, fmt.Errorf("%w: entitlement lookup returned %d", application.ErrDependencyUnavailable, response.StatusCode)
	}

	var payload struct {
		EntitlementID    string `json:"entitlementId"`
		SegmentBookingID string `json:"segmentBookingId"`
		JourneyOrderID   string `json:"journeyOrderId"`
		TravelerRef      string `json:"travelerRef"`
		SegmentRef       string `json:"segmentRef"`
		Status           string `json:"status"`
	}
	decoder := json.NewDecoder(io.LimitReader(response.Body, maxResponseBytes))
	if err := decoder.Decode(&payload); err != nil {
		return application.EntitlementSnapshot{}, fmt.Errorf("%w: decode entitlement response: %v", application.ErrDependencyUnavailable, err)
	}
	if err := ensureEOF(decoder); err != nil {
		return application.EntitlementSnapshot{}, fmt.Errorf("%w: decode entitlement response: %v", application.ErrDependencyUnavailable, err)
	}
	return application.EntitlementSnapshot{
		EntitlementID:    domain.EntitlementRef(payload.EntitlementID),
		SegmentBookingID: domain.SegmentBookingRef(payload.SegmentBookingID),
		JourneyOrderID:   domain.OrderRef(payload.JourneyOrderID),
		TravelerID:       domain.TravelerRef(payload.TravelerRef),
		SegmentRef:       domain.SegmentRef(payload.SegmentRef),
		Status:           payload.Status,
	}, nil
}

func ensureEOF(decoder *json.Decoder) error {
	var extra any
	err := decoder.Decode(&extra)
	if errors.Is(err, io.EOF) {
		return nil
	}
	if err == nil {
		return errors.New("response contains multiple JSON values")
	}
	return err
}
