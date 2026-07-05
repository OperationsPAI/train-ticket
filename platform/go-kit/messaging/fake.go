package messaging

import (
	"context"
	"sync"
)

type InMemoryEventBus struct {
	mu        sync.Mutex
	published []EventEnvelope
	subs      []fakeSub
}
type fakeSub struct {
	streams  []string
	group    string
	consumer string
	handler  Handler
}

func NewInMemoryEventBus() *InMemoryEventBus { return &InMemoryEventBus{} }
func (b *InMemoryEventBus) Publish(ctx context.Context, envelope EventEnvelope) error {
	b.mu.Lock()
	b.published = append(b.published, envelope)
	subs := append([]fakeSub(nil), b.subs...)
	b.mu.Unlock()
	for _, s := range subs {
		for _, stream := range s.streams {
			if StreamName(stream) == StreamName(envelope.Producer) {
				if err := s.handler(ctx, envelope); err != nil {
					return err
				}
			}
		}
	}
	return nil
}
func (b *InMemoryEventBus) Subscribe(_ context.Context, sub Subscription, handler Handler) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.subs = append(b.subs, fakeSub{streams: sub.Streams, group: sub.Group, consumer: sub.ConsumerName, handler: handler})
	return nil
}
func (b *InMemoryEventBus) Published() []EventEnvelope {
	b.mu.Lock()
	defer b.mu.Unlock()
	return append([]EventEnvelope(nil), b.published...)
}
