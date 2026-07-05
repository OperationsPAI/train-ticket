package domain

import (
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
)

const ProducerPlaceNetwork = "place-network"

// Domain event payloads for Place & Network.

type PlaceUpdatedEvent struct {
	PlaceID       PlaceID     `json:"placeId"`
	PlaceType     PlaceType   `json:"placeType"`
	CanonicalName string      `json:"canonicalName"`
	Code          string      `json:"code,omitempty"`
	Timezone      string      `json:"timezone,omitempty"`
	Status        PlaceStatus `json:"status"`
	UpdatedAt     string      `json:"updatedAt"`
}

type TransportNodeUpdatedEvent struct {
	NodeID       TransportNodeID `json:"nodeId"`
	PlaceID      PlaceID         `json:"placeId"`
	DisplayName  string          `json:"displayName"`
	ServingModes []TransportMode `json:"servingModes"`
	UpdatedAt    string          `json:"updatedAt"`
}

// EventEnvelope is the shared-primitives event envelope used on the event bus.
type EventEnvelope struct {
	EventID       string `json:"eventId"`
	EventType     string `json:"eventType"`
	SchemaVersion int    `json:"schemaVersion"`
	Producer      string `json:"producer"`
	CausationID   string `json:"causationId,omitempty"`
	CorrelationID string `json:"correlationId"`
	OccurredAt    string `json:"occurredAt"`
	Payload       any    `json:"payload"`
}

func NewEventEnvelope(eventType string, occurredAt time.Time, correlationID string, causationID string, producer string, payload any) EventEnvelope {
	return EventEnvelope{
		EventID:       ids.NewEventID(),
		EventType:     eventType,
		SchemaVersion: 1,
		Producer:      producer,
		CausationID:   causationID,
		CorrelationID: correlationID,
		OccurredAt:    FormatTimestamp(occurredAt),
		Payload:       payload,
	}
}

func FormatTimestamp(value time.Time) string {
	return value.UTC().Format(time.RFC3339Nano)
}

func NewPlaceID() PlaceID {
	return PlaceID("plc-" + newUUIDv7())
}

func NewTransportNodeID() TransportNodeID {
	return TransportNodeID("tnd-" + newUUIDv7())
}

func newUUIDv7() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic("secure random id generation failed: " + err.Error())
	}

	ms := uint64(time.Now().UTC().UnixMilli())
	binary.BigEndian.PutUint32(b[0:4], uint32(ms>>16))
	binary.BigEndian.PutUint16(b[4:6], uint16(ms))
	b[6] = (b[6] & 0x0f) | 0x70
	b[8] = (b[8] & 0x3f) | 0x80

	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

type Clock interface {
	Now() time.Time
}

type RealClock struct{}

func (RealClock) Now() time.Time { return time.Now() }
