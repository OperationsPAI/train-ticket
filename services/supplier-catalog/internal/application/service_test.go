package application

import (
	"context"
	"errors"
	"strings"
	"testing"

	"go.opentelemetry.io/otel/trace"

	"github.com/trainticket/greenfield/services/supplier-catalog/internal/domain"
)

type recordingPublisher struct {
	envelopes []EventEnvelope
	failures  int
}

func (p *recordingPublisher) Publish(_ context.Context, envelope EventEnvelope) error {
	if p.failures > 0 {
		p.failures--
		return errors.New("redis unavailable")
	}
	p.envelopes = append(p.envelopes, envelope)
	return nil
}

type failingRepository struct{ err error }

func (r failingRepository) SaveSupplier(context.Context, domain.Supplier) error { return r.err }
func (r failingRepository) SaveCarrier(context.Context, domain.Carrier) error   { return r.err }
func (r failingRepository) SaveContract(context.Context, domain.Contract) error { return r.err }
func (r failingRepository) FindSupplier(context.Context, string) (domain.Supplier, error) {
	return domain.Supplier{}, r.err
}
func (r failingRepository) FindCarrier(context.Context, string) (domain.Carrier, error) {
	return domain.Carrier{}, r.err
}
func (r failingRepository) ListSuppliers(context.Context, string, int, int) (SupplierList, error) {
	return SupplierList{}, r.err
}
func (r failingRepository) FindSupplierByProfile(context.Context, string) (domain.Supplier, error) {
	return domain.Supplier{}, r.err
}
func (r failingRepository) CountSuppliersByKey(context.Context, string, string) (int, error) {
	return 0, r.err
}
func (r failingRepository) FindCarrierByCode(context.Context, string) (domain.Carrier, error) {
	return domain.Carrier{}, r.err
}
func (r failingRepository) FindContractByRef(context.Context, string) (domain.Contract, error) {
	return domain.Contract{}, r.err
}

func TestWrapDomainEventProducesContractEnvelope(t *testing.T) {
	envelope, err := WrapDomainEvent(context.Background(), domain.SupplierRegisteredEvent{SupplierID: "sup-1", LegalName: "Legal", BrandName: "Brand", Status: domain.SupplierStatusDraft}, "0194f2e0-7b3e-7610-0284-5c26e8b0c444", "0194f2e0-7b3e-7610-0284-5c26e8b0c555")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(envelope.EventID, "evt-") {
		t.Fatalf("eventId missing prefix: %s", envelope.EventID)
	}
	if envelope.EventType != "SupplierRegistered" || envelope.Producer != "supplier-catalog" || envelope.SchemaVersion != 1 {
		t.Fatalf("unexpected envelope: %#v", envelope)
	}
	if envelope.CorrelationID != "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444" {
		t.Fatalf("unexpected correlationId: %s", envelope.CorrelationID)
	}
	if envelope.CausationID != "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555" {
		t.Fatalf("unexpected causationId: %s", envelope.CausationID)
	}
	if len(envelope.Payload) == 0 {
		t.Fatalf("missing payload")
	}
	if envelope.Traceparent != "" {
		t.Fatalf("traceparent must be absent without span context: %q", envelope.Traceparent)
	}
}

func TestWrapDomainEventIncludesTraceparentFromContext(t *testing.T) {
	ctx := trace.ContextWithSpanContext(context.Background(), mustSpanContext(t))
	envelope, err := WrapDomainEvent(ctx, domain.SupplierRegisteredEvent{SupplierID: "sup-1", LegalName: "Legal", BrandName: "Brand", Status: domain.SupplierStatusDraft}, "0194f2e0-7b3e-7610-0284-5c26e8b0c444", "")
	if err != nil {
		t.Fatal(err)
	}
	if envelope.Traceparent != wantTraceparent {
		t.Fatalf("traceparent mismatch: %q", envelope.Traceparent)
	}
}

func TestPublishFailureDoesNotUpdateCacheBeforeSuccessfulRetry(t *testing.T) {
	publisher := &recordingPublisher{failures: 1}
	service := NewService(publisher)
	cmd := RegisterSupplierCommand{LegalName: "China Railway", BrandName: "CR", SupplierCode: "CR", CorrelationID: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444"}

	if _, err := service.RegisterSupplier(context.Background(), cmd); err == nil || !strings.Contains(err.Error(), "publish failed") {
		t.Fatalf("expected publish failure, got %v", err)
	}
	if len(publisher.envelopes) != 0 {
		t.Fatalf("unexpected published envelopes after failure: %d", len(publisher.envelopes))
	}
	suppliers, err := service.ListSuppliers(context.Background(), "", 20, 0)
	if err != nil {
		t.Fatalf("list suppliers: %v", err)
	}
	if suppliers.Total != 0 {
		t.Fatalf("expected cache unchanged after publish failure, got %d suppliers", suppliers.Total)
	}

	if _, err := service.RegisterSupplier(context.Background(), cmd); err != nil {
		t.Fatalf("retry should publish and update cache: %v", err)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected retry event to publish once, got %d", len(publisher.envelopes))
	}
}

func TestListSuppliersPropagatesRepositoryError(t *testing.T) {
	boom := errors.New("postgres unavailable")
	service := NewService(nil).WithRepository(failingRepository{err: boom})
	_, err := service.ListSuppliers(context.Background(), "", 20, 0)
	if !errors.Is(err, boom) {
		t.Fatalf("expected repository error, got %v", err)
	}
}

// Two onboardings of one partner mint two codes and hand over two legal names, and
// the master data downstream reconciles a partner by the name it trades as: the
// catalogue holds two entries for it today, and the operator import merges them
// afterwards.
func TestTwoOnboardingsUnderOneTradingNameFileTwoPartners(t *testing.T) {
	service := NewService(nil)
	first := RegisterSupplierCommand{LegalName: "Transfer carrier 0194f2e0", BrandName: "Transfer rail", SupplierCode: "T4c1f2a8b9d", CorrelationID: "corr-0194f2e0-7b3e-7000-8000-000000000021"}
	if _, err := service.RegisterSupplier(context.Background(), first); err != nil {
		t.Fatalf("first onboarding: %v", err)
	}
	second := RegisterSupplierCommand{LegalName: "Transfer carrier 0194f2e1", BrandName: "Transfer rail", SupplierCode: "T5d2e3b0c1e", CorrelationID: "corr-0194f2e1-7b3e-7000-8000-000000000022"}
	if _, err := service.RegisterSupplier(context.Background(), second); err != nil {
		t.Fatalf("second onboarding: %v", err)
	}
	filed, err := service.ListSuppliers(context.Background(), "", 20, 0)
	if err != nil {
		t.Fatalf("list suppliers: %v", err)
	}
	if filed.Total != 2 {
		t.Fatalf("expected the catalogue to hold both partners, got %d", filed.Total)
	}
}

// countingRepository answers the count the partner bound reads and refuses every
// other call, so a test can tell the bound's read apart from the profile's.
type countingRepository struct {
	held     int
	requests []string
}

func (r *countingRepository) SaveSupplier(context.Context, domain.Supplier) error {
	return errors.New("not used")
}
func (r *countingRepository) SaveCarrier(context.Context, domain.Carrier) error {
	return errors.New("not used")
}
func (r *countingRepository) SaveContract(context.Context, domain.Contract) error {
	return errors.New("not used")
}
func (r *countingRepository) FindSupplier(context.Context, string) (domain.Supplier, error) {
	return domain.Supplier{}, errors.New("not used")
}
func (r *countingRepository) FindCarrier(context.Context, string) (domain.Carrier, error) {
	return domain.Carrier{}, errors.New("not used")
}
func (r *countingRepository) ListSuppliers(context.Context, string, int, int) (SupplierList, error) {
	return SupplierList{}, errors.New("not used")
}
func (r *countingRepository) FindSupplierByProfile(context.Context, string) (domain.Supplier, error) {
	return domain.Supplier{}, ErrNotFound
}
func (r *countingRepository) CountSuppliersByKey(_ context.Context, key, value string) (int, error) {
	r.requests = append(r.requests, key+"="+value)
	return r.held, nil
}
func (r *countingRepository) FindCarrierByCode(context.Context, string) (domain.Carrier, error) {
	return domain.Carrier{}, ErrNotFound
}
func (r *countingRepository) FindContractByRef(context.Context, string) (domain.Contract, error) {
	return domain.Contract{}, ErrNotFound
}

func TestPartnerBoundRefusesTheSecondPartnerUnderOneIdentity(t *testing.T) {
	bound := PartnerBound{Key: PartnerKeyBrandName, Limit: PartnersPerIdentity}
	service := NewService(nil).WithPartnerBound(bound)
	first := RegisterSupplierCommand{LegalName: "Transfer carrier 0194f2e0", BrandName: "Transfer rail", SupplierCode: "T4c1f2a8b9d", CorrelationID: "corr-0194f2e0-7b3e-7000-8000-000000000031"}
	if _, err := service.RegisterSupplier(context.Background(), first); err != nil {
		t.Fatalf("first partner under one trading name: %v", err)
	}
	second := RegisterSupplierCommand{LegalName: "Transfer carrier 0194f2e1", BrandName: "Transfer rail", SupplierCode: "T5d2e3b0c1e", CorrelationID: "corr-0194f2e1-7b3e-7000-8000-000000000032"}
	if _, err := service.RegisterSupplier(context.Background(), second); !errors.Is(err, ErrConflict) {
		t.Fatalf("expected the second partner under one trading name to be refused, got %v", err)
	}
}

func TestPartnerBoundKeepsTheIdentityTheReleaseDoesNotName(t *testing.T) {
	service := NewService(nil)
	first := RegisterSupplierCommand{LegalName: "Transfer carrier 0194f2e0", BrandName: "Transfer rail", SupplierCode: "T4c1f2a8b9d", CorrelationID: "corr-0194f2e0-7b3e-7000-8000-000000000033"}
	if _, err := service.RegisterSupplier(context.Background(), first); err != nil {
		t.Fatalf("first partner: %v", err)
	}
	second := RegisterSupplierCommand{LegalName: "Transfer carrier 0194f2e1", BrandName: "Transfer rail", SupplierCode: "T5d2e3b0c1e", CorrelationID: "corr-0194f2e1-7b3e-7000-8000-000000000034"}
	if _, err := service.RegisterSupplier(context.Background(), second); err != nil {
		t.Fatalf("a second legal name is a second partner by default: %v", err)
	}
}

func TestPartnerBoundCountsTheIdentityTheRepositoryHolds(t *testing.T) {
	repository := &countingRepository{held: PartnersPerIdentity}
	service := NewService(nil).WithRepository(repository).WithPartnerBound(PartnerBound{Key: PartnerKeyBrandName, Limit: PartnersPerIdentity})
	_, err := service.RegisterSupplier(context.Background(), RegisterSupplierCommand{LegalName: "Transfer carrier 0194f2e0", BrandName: "Transfer rail", SupplierCode: "T4c1f2a8b9d", CorrelationID: "corr-0194f2e0-7b3e-7000-8000-000000000035"})
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("expected the count the repository holds to refuse the filing, got %v", err)
	}
	if len(repository.requests) != 1 || repository.requests[0] != PartnerKeyBrandName+"=Transfer rail" {
		t.Fatalf("expected one read of the named identity, got %v", repository.requests)
	}
}

const wantTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

func mustSpanContext(t *testing.T) trace.SpanContext {
	t.Helper()
	traceID, err := trace.TraceIDFromHex("4bf92f3577b34da6a3ce929d0e0e4736")
	if err != nil {
		t.Fatal(err)
	}
	spanID, err := trace.SpanIDFromHex("00f067aa0ba902b7")
	if err != nil {
		t.Fatal(err)
	}
	spanContext := trace.NewSpanContext(trace.SpanContextConfig{TraceID: traceID, SpanID: spanID, TraceFlags: trace.FlagsSampled})
	if !spanContext.IsValid() {
		t.Fatal("invalid span context")
	}
	return spanContext
}
