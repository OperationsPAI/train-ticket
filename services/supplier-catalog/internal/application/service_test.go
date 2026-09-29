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

// Two onboardings of one partner under one trading name and two codes file two
// entries, because the profile each registration is matched on is the code it
// handed over. Pinned here before the identity the release names is read, so the
// change that introduces it has something to answer.
func TestRegisterSupplierFilesEachCodeAsItsOwnPartner(t *testing.T) {
	service := NewService(nil)
	registered, err := service.RegisterSupplier(context.Background(), RegisterSupplierCommand{
		LegalName: "Transfer carrier 0194f2e0", BrandName: "Transfer rail", SupplierCode: "T7191d26549",
	})
	if err != nil {
		t.Fatalf("first registration: %v", err)
	}
	second, err := service.RegisterSupplier(context.Background(), RegisterSupplierCommand{
		LegalName: "Transfer carrier 0194f2e1", BrandName: "Transfer rail", SupplierCode: "T8191d26549",
	})
	if err != nil {
		t.Fatalf("second registration under one trading name and a second code: %v", err)
	}
	if registered.SupplierID == second.SupplierID {
		t.Fatalf("two codes should file two partners, got %s twice", registered.SupplierID)
	}
	suppliers, err := service.ListSuppliers(context.Background(), "", 20, 0)
	if err != nil {
		t.Fatalf("list suppliers: %v", err)
	}
	if suppliers.Total != 2 {
		t.Fatalf("expected two partners filed under two codes, got %d", suppliers.Total)
	}
}

// The release decides which field names a partner, so a release that names the
// brand resolves the second onboarding of one trading name to the entry the
// first one filed and refuses it rather than filing a second partner.
func TestRegisterSupplierRefusesASecondPartnerUnderOneTradingName(t *testing.T) {
	service := NewService(nil).WithIdentitySource(IdentitySourceBrand)
	if _, err := service.RegisterSupplier(context.Background(), RegisterSupplierCommand{
		LegalName: "Transfer carrier 0194f2e0", BrandName: "Transfer rail", SupplierCode: "T7191d26549",
	}); err != nil {
		t.Fatalf("first registration under the trading name: %v", err)
	}
	_, err := service.RegisterSupplier(context.Background(), RegisterSupplierCommand{
		LegalName: "Transfer carrier 0194f2e1", BrandName: "Transfer rail", SupplierCode: "T8191d26549",
	})
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("a second onboarding of one trading name should conflict, got %v", err)
	}
	if _, err := service.RegisterSupplier(context.Background(), RegisterSupplierCommand{
		LegalName: "Transfer carrier 0194f2e2", BrandName: "Transfer coach", SupplierCode: "T9191d26549",
	}); err != nil {
		t.Fatalf("a second trading name is a second partner: %v", err)
	}
}

// The default is the code, so a release that says nothing -- or names a field
// this version does not know -- keeps filing what it filed before the identity
// became a release decision.
func TestSupplierIdentitySourceFromEnvDefaultsToTheCode(t *testing.T) {
	t.Setenv(SupplierIdentitySourceEnv, "")
	if got := SupplierIdentitySourceFromEnv(); got != IdentitySourceCode {
		t.Fatalf("a release that names no field files the code, got %q", got)
	}
	t.Setenv(SupplierIdentitySourceEnv, "market")
	if got := SupplierIdentitySourceFromEnv(); got != IdentitySourceCode {
		t.Fatalf("a field this version does not know files the code, got %q", got)
	}
	t.Setenv(SupplierIdentitySourceEnv, " Brand ")
	if got := SupplierIdentitySourceFromEnv(); got != IdentitySourceBrand {
		t.Fatalf("a padded field name still names the brand, got %q", got)
	}
}

// filedProfile answers the profile lookup the way the deployed path does: a
// partner with that profile is already filed, everything else is not.
type filedProfile struct {
	failingRepository
	profile string
}

func (r filedProfile) FindSupplierByProfile(_ context.Context, profile string) (domain.Supplier, error) {
	if strings.EqualFold(strings.TrimSpace(profile), r.profile) {
		return domain.Supplier{SupplierID: "sup-filed", Profile: profile, Status: domain.SupplierStatusDraft}, nil
	}
	return domain.Supplier{}, ErrNotFound
}

// The deployed registration reads the profile back through the repository before
// it files anything, so the identity the release names is the value that lookup
// is given -- and a release that names the code still files a second partner
// beside the trading name the repository holds.
func TestRegisterSupplierReadsTheFilingIdentityFromTheRepository(t *testing.T) {
	repository := filedProfile{failingRepository{}, "transfer rail"}
	byBrand := NewService(nil).WithRepository(repository).WithIdentitySource(IdentitySourceBrand)
	_, err := byBrand.RegisterSupplier(context.Background(), RegisterSupplierCommand{
		LegalName: "Transfer carrier 0194f2e0", BrandName: "Transfer Rail", SupplierCode: "T7191d26549",
	})
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("a partner already filed under the trading name should conflict, got %v", err)
	}
	byCode := NewService(nil).WithRepository(repository).WithIdentitySource(IdentitySourceCode)
	if _, err := byCode.RegisterSupplier(context.Background(), RegisterSupplierCommand{
		LegalName: "Transfer carrier 0194f2e0", BrandName: "Transfer Rail", SupplierCode: "T7191d26549",
	}); err != nil {
		t.Fatalf("a release that files the code does not resolve that code to the trading name: %v", err)
	}
}

// The list path applies the bounds a query omitted, so a caller that pages
// without a limit or an offset gets the default page rather than an empty one,
// and one that asks for more than the ceiling gets the ceiling.
func TestListSuppliersAppliesTheBoundsTheQueryOmitted(t *testing.T) {
	service := NewService(nil)
	for _, code := range []string{"CR", "LG", "TR"} {
		if _, err := service.RegisterSupplier(context.Background(), RegisterSupplierCommand{
			LegalName: "Partner " + code, BrandName: "Brand " + code, SupplierCode: code,
		}); err != nil {
			t.Fatalf("register %s: %v", code, err)
		}
	}
	page, err := service.ListSuppliers(context.Background(), "", 0, -1)
	if err != nil {
		t.Fatalf("list suppliers: %v", err)
	}
	if page.Limit != 20 || page.Offset != 0 || page.Total != 3 || len(page.Items) != 3 {
		t.Fatalf("unexpected bounds: limit=%d offset=%d total=%d items=%d", page.Limit, page.Offset, page.Total, len(page.Items))
	}
	wide, err := service.ListSuppliers(context.Background(), "", 500, 0)
	if err != nil {
		t.Fatalf("list suppliers: %v", err)
	}
	if wide.Limit != 100 {
		t.Fatalf("a limit above the ceiling is clamped to 100, got %d", wide.Limit)
	}
}
