package application

import (
	"context"
	"os"

	"github.com/trainticket/greenfield/platform/go-kit/messaging"
)

const (
	JourneyOrderStream = "events:journey-order"
	PostSalesStream    = "events:post-sales"
	ConsumerGroup      = "travel-insurance"
)

type EventBus interface {
	Subscribe(context.Context, messaging.Subscription, messaging.Handler) error
}

func Subscribe(ctx context.Context, bus EventBus, svc *InsuranceService) error {
	consumer := os.Getenv("TRAVEL_INSURANCE_CONSUMER_NAME")
	if consumer == "" {
		consumer = "travel-insurance-local"
	}
	return bus.Subscribe(ctx, messaging.Subscription{Streams: []string{JourneyOrderStream, PostSalesStream}, Group: ConsumerGroup, ConsumerName: consumer}, svc.HandleEvent)
}
