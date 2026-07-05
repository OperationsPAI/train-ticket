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

func TestWrapDomainEventProducesContractEnvelope(t *testing.T) {
	envelope := WrapDomainEvent(domain.SupplierRegisteredEvent{SupplierID: "sup-1", LegalName: "Legal", BrandName: "Brand", Status: domain.SupplierStatusDraft}, "0194f2e0-7b3e-7610-0284-5c26e8b0c444", "0194f2e0-7b3e-7610-0284-5c26e8b0c555")
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
	if _, ok := envelope.Payload.(domain.SupplierRegisteredEvent); !ok {
		t.Fatalf("unexpected payload type: %T", envelope.Payload)
	}
}

func TestPublishFailureRetainsEventAndFlushesOnRetry(t *testing.T) {
	publisher := &recordingPublisher{failures: 1}
	service := NewService(publisher)
	cmd := RegisterSupplierCommand{LegalName: "China Railway", BrandName: "CR", SupplierCode: "CR", CorrelationID: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444"}

	if _, err := service.RegisterSupplier(context.Background(), cmd); err == nil || !strings.Contains(err.Error(), "publish failed") {
		t.Fatalf("expected publish failure, got %v", err)
	}
	if len(publisher.envelopes) != 0 {
		t.Fatalf("unexpected published envelopes after failure: %d", len(publisher.envelopes))
	}
	suppliers := service.ListSuppliers(context.Background(), "", 20, 0)
	if suppliers.Total != 1 {
		t.Fatalf("expected mutation retained after publish failure, got %d suppliers", suppliers.Total)
	}

	cmd.SupplierCode = "CR2"
	cmd.BrandName = "CR2"
	if _, err := service.RegisterSupplier(context.Background(), cmd); err != nil {
		t.Fatalf("retry should flush retained event and publish new event: %v", err)
	}
	if len(publisher.envelopes) != 2 {
		t.Fatalf("expected retained and new event to publish, got %d", len(publisher.envelopes))
	}
}
