package domain

import (
	"fmt"
	"strings"
	"time"
)

// PlaceID is the stable cross-context reference for a canonical place.
type PlaceID string

// TransportNodeID identifies a node with transport fulfillment meaning, such as
// a station, terminal, platform, gate, or transfer node.
type TransportNodeID string

type ProviderID string

type SourceSystem string

type ProviderPlaceCode string

// PlaceType describes the business semantics of a Place. Type changes are
// intentionally explicit because historical meaning must remain explainable.
type PlaceType string

const (
	PlaceTypeCity         PlaceType = "CITY"
	PlaceTypeAddress      PlaceType = "ADDRESS"
	PlaceTypePOI          PlaceType = "POI"
	PlaceTypeStation      PlaceType = "STATION"
	PlaceTypeAirport      PlaceType = "AIRPORT"
	PlaceTypePort         PlaceType = "PORT"
	PlaceTypeTerminal     PlaceType = "TERMINAL"
	PlaceTypePlatform     PlaceType = "PLATFORM"
	PlaceTypeGate         PlaceType = "GATE"
	PlaceTypeTransferNode PlaceType = "TRANSFER_NODE"
)

type PlaceStatus string

const (
	PlaceStatusDraft      PlaceStatus = "DRAFT"
	PlaceStatusActive     PlaceStatus = "ACTIVE"
	PlaceStatusDeprecated PlaceStatus = "DEPRECATED"
	PlaceStatusMerged     PlaceStatus = "MERGED"
	PlaceStatusRetired    PlaceStatus = "RETIRED"
)

type TransportMode string

const (
	TransportModeTrain TransportMode = "TRAIN"
	TransportModeBus   TransportMode = "BUS"
	TransportModeAir   TransportMode = "AIR"
	TransportModeFerry TransportMode = "FERRY"
	TransportModeWalk  TransportMode = "WALK"
)

// Coordinate is optional for draft master data, but when present it must be a
// valid WGS84 latitude/longitude pair.
type Coordinate struct {
	Latitude  float64
	Longitude float64
}

// Place is the authoritative master-data primitive for a user, provider, or
// system referenceable location.
type Place struct {
	ID            PlaceID
	Type          PlaceType
	CanonicalName string
	Status        PlaceStatus
	Coordinate    *Coordinate
}

// NewPlace validates the minimum invariants required before a Place can be
// persisted or referenced by downstream contexts.
func NewPlace(id PlaceID, placeType PlaceType, canonicalName string, status PlaceStatus, coordinate *Coordinate) (Place, error) {
	place := Place{
		ID:            PlaceID(strings.TrimSpace(string(id))),
		Type:          placeType,
		CanonicalName: strings.TrimSpace(canonicalName),
		Status:        status,
		Coordinate:    coordinate,
	}
	if err := place.Validate(); err != nil {
		return Place{}, err
	}
	return place, nil
}

func (p Place) Validate() error {
	if strings.TrimSpace(string(p.ID)) == "" {
		return fmt.Errorf("place id is required")
	}
	if !validPlaceType(p.Type) {
		return fmt.Errorf("unsupported place type: %q", p.Type)
	}
	if p.CanonicalName == "" {
		return fmt.Errorf("canonical place name is required")
	}
	if !validPlaceStatus(p.Status) {
		return fmt.Errorf("unsupported place status: %q", p.Status)
	}
	if p.Coordinate != nil {
		if p.Coordinate.Latitude < -90 || p.Coordinate.Latitude > 90 {
			return fmt.Errorf("latitude out of range: %f", p.Coordinate.Latitude)
		}
		if p.Coordinate.Longitude < -180 || p.Coordinate.Longitude > 180 {
			return fmt.Errorf("longitude out of range: %f", p.Coordinate.Longitude)
		}
	}
	return nil
}

// TransportNode binds a transport-meaningful node to a canonical Place. Service
// Plan may only publish stops against known, valid nodes.
type TransportNode struct {
	ID           TransportNodeID
	PlaceID      PlaceID
	DisplayName  string
	ServingModes []TransportMode
}

func NewTransportNode(id TransportNodeID, placeID PlaceID, displayName string, servingModes []TransportMode) (TransportNode, error) {
	node := TransportNode{
		ID:           TransportNodeID(strings.TrimSpace(string(id))),
		PlaceID:      PlaceID(strings.TrimSpace(string(placeID))),
		DisplayName:  strings.TrimSpace(displayName),
		ServingModes: servingModes,
	}
	if err := node.Validate(); err != nil {
		return TransportNode{}, err
	}
	return node, nil
}

func (n TransportNode) Validate() error {
	if strings.TrimSpace(string(n.ID)) == "" {
		return fmt.Errorf("transport node id is required")
	}
	if strings.TrimSpace(string(n.PlaceID)) == "" {
		return fmt.Errorf("transport node must bind a place id")
	}
	if n.DisplayName == "" {
		return fmt.Errorf("transport node display name is required")
	}
	if len(n.ServingModes) == 0 {
		return fmt.Errorf("transport node must declare at least one serving mode")
	}
	seen := make(map[TransportMode]struct{}, len(n.ServingModes))
	for _, mode := range n.ServingModes {
		if !validTransportMode(mode) {
			return fmt.Errorf("unsupported transport mode: %q", mode)
		}
		if _, exists := seen[mode]; exists {
			return fmt.Errorf("duplicate transport mode: %q", mode)
		}
		seen[mode] = struct{}{}
	}
	return nil
}

type ProviderMappingStatus string

const (
	ProviderMappingStandard      ProviderMappingStatus = "STANDARD"
	ProviderMappingLowConfidence ProviderMappingStatus = "LOW_CONFIDENCE"
	ProviderMappingConflictGap   ProviderMappingStatus = "CONFLICT_GAP"
)

// ProviderPlaceMapping records the ACL output for a provider place code. Low
// confidence and conflict records are explicit gaps rather than silently leaking
// provider codes into core domains.
type ProviderPlaceMapping struct {
	ProviderID ProviderID
	Source     SourceSystem
	RawCode    ProviderPlaceCode
	RawName    string
	TargetNode *TransportNodeID
	Status     ProviderMappingStatus
	Confidence float64
	ValidFrom  time.Time
	ValidTo    *time.Time
	GapReason  string
}

func NewProviderPlaceMapping(providerID ProviderID, source SourceSystem, rawCode ProviderPlaceCode, rawName string, targetNode *TransportNodeID, status ProviderMappingStatus, confidence float64, validFrom time.Time, validTo *time.Time, gapReason string) (ProviderPlaceMapping, error) {
	mapping := ProviderPlaceMapping{
		ProviderID: ProviderID(strings.TrimSpace(string(providerID))),
		Source:     SourceSystem(strings.TrimSpace(string(source))),
		RawCode:    ProviderPlaceCode(strings.TrimSpace(string(rawCode))),
		RawName:    strings.TrimSpace(rawName),
		TargetNode: targetNode,
		Status:     status,
		Confidence: confidence,
		ValidFrom:  validFrom,
		ValidTo:    validTo,
		GapReason:  strings.TrimSpace(gapReason),
	}
	if err := mapping.Validate(); err != nil {
		return ProviderPlaceMapping{}, err
	}
	return mapping, nil
}

func (m ProviderPlaceMapping) Validate() error {
	if strings.TrimSpace(string(m.ProviderID)) == "" {
		return fmt.Errorf("provider id is required")
	}
	if strings.TrimSpace(string(m.Source)) == "" {
		return fmt.Errorf("source system is required")
	}
	if strings.TrimSpace(string(m.RawCode)) == "" {
		return fmt.Errorf("provider raw code is required")
	}
	if m.RawName == "" {
		return fmt.Errorf("provider raw name is required")
	}
	if !validProviderMappingStatus(m.Status) {
		return fmt.Errorf("unsupported provider mapping status: %q", m.Status)
	}
	if m.Confidence < 0 || m.Confidence > 1 {
		return fmt.Errorf("mapping confidence must be between 0 and 1")
	}
	if m.ValidFrom.IsZero() {
		return fmt.Errorf("mapping valid-from time is required")
	}
	if m.ValidTo != nil && !m.ValidTo.After(m.ValidFrom) {
		return fmt.Errorf("mapping valid-to must be after valid-from")
	}
	if m.Status == ProviderMappingStandard {
		if m.TargetNode == nil || strings.TrimSpace(string(*m.TargetNode)) == "" {
			return fmt.Errorf("standard provider mapping requires a target node")
		}
		if m.Confidence < 0.80 {
			return fmt.Errorf("standard provider mapping requires confidence >= 0.80")
		}
		return nil
	}
	if m.GapReason == "" {
		return fmt.Errorf("non-standard provider mapping requires a gap reason")
	}
	return nil
}

func validPlaceType(value PlaceType) bool {
	switch value {
	case PlaceTypeCity, PlaceTypeAddress, PlaceTypePOI, PlaceTypeStation, PlaceTypeAirport, PlaceTypePort, PlaceTypeTerminal, PlaceTypePlatform, PlaceTypeGate, PlaceTypeTransferNode:
		return true
	default:
		return false
	}
}

func validPlaceStatus(value PlaceStatus) bool {
	switch value {
	case PlaceStatusDraft, PlaceStatusActive, PlaceStatusDeprecated, PlaceStatusMerged, PlaceStatusRetired:
		return true
	default:
		return false
	}
}

func validTransportMode(value TransportMode) bool {
	switch value {
	case TransportModeTrain, TransportModeBus, TransportModeAir, TransportModeFerry, TransportModeWalk:
		return true
	default:
		return false
	}
}

func validProviderMappingStatus(value ProviderMappingStatus) bool {
	switch value {
	case ProviderMappingStandard, ProviderMappingLowConfidence, ProviderMappingConflictGap:
		return true
	default:
		return false
	}
}
