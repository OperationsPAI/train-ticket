package application

import (
	"context"
	"errors"
	"strings"
	"testing"

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
	envelope, err := WrapDomainEvent(domain.SupplierRegisteredEvent{SupplierID: "sup-1", LegalName: "Legal", BrandName: "Brand", Status: domain.SupplierStatusDraft}, "0194f2e0-7b3e-7610-0284-5c26e8b0c444", "0194f2e0-7b3e-7610-0284-5c26e8b0c555")
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
