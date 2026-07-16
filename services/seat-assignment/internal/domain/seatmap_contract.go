package domain

import (
	"fmt"
	"sort"
	"strings"
	"time"
)

const (
	SeatMapStatusDraft      = "DRAFT"
	SeatMapStatusPublished  = "PUBLISHED"
	SeatMapStatusSuperseded = "SUPERSEDED"
	SeatMapStatusRetired    = "RETIRED"

	AllocationStatusAllocated = "ALLOCATED"
	AllocationStatusStanding  = "STANDING"
	AllocationStatusConfirmed = "CONFIRMED"
	AllocationStatusReleased  = "RELEASED"
	AllocationStatusExpired   = "EXPIRED"

	AllocationTypeSeat     = "SEAT"
	AllocationTypeBerth    = "BERTH"
	AllocationTypeStanding = "STANDING"

	AdjacencyNone            = "NONE"
	AdjacencySameCoach       = "SAME_COACH"
	AdjacencySameRow         = "SAME_ROW"
	AdjacencyAdjacent        = "ADJACENT"
	AdjacencySameCompartment = "SAME_COMPARTMENT"

	DegradationNone             = "NONE"
	DegradationNoAdjacentBlock  = "NO_ADJACENT_BLOCK"
	DegradationStandingAssigned = "STANDING_ASSIGNED"
)

type ContractSeatMap struct {
	SeatMapID           string     `json:"seatMapId"`
	ScheduledServiceRef string     `json:"scheduledServiceRef"`
	ServiceDate         string     `json:"serviceDate"`
	CompositionVersion  string     `json:"compositionVersion"`
	SeatMapVersion      int        `json:"seatMapVersion"`
	Source              string     `json:"source"`
	CompositionSeed     string     `json:"compositionSeed"`
	MappingVersion      string     `json:"mappingVersion"`
	Status              string     `json:"status"`
	Coaches             []Coach    `json:"coaches"`
	CreatedAt           time.Time  `json:"createdAt"`
	PublishedAt         *time.Time `json:"publishedAt,omitempty"`
	RetiredAt           *time.Time `json:"retiredAt,omitempty"`
}

type Coach struct {
	CoachRef   string     `json:"coachRef"`
	CoachNo    string     `json:"coachNo"`
	ClassRef   string     `json:"classRef"`
	CoachType  string     `json:"coachType"`
	Assignable bool       `json:"assignable"`
	SeatUnits  []SeatUnit `json:"seatUnits"`
}

type SeatUnit struct {
	SeatUnitRef       string `json:"seatUnitRef"`
	CoachRef          string `json:"coachRef"`
	CoachNo           string `json:"coachNo"`
	SeatNo            string `json:"seatNo"`
	AllocationType    string `json:"allocationType"`
	BerthPosition     string `json:"berthPosition,omitempty"`
	SeatPosition      string `json:"seatPosition,omitempty"`
	RowNo             string `json:"rowNo,omitempty"`
	AdjacencyGroupKey string `json:"adjacencyGroupKey,omitempty"`
	Assignable        bool   `json:"assignable"`
	UnavailableReason string `json:"unavailableReason,omitempty"`
}

func NewContractSeatMap(scheduledServiceRef, serviceDate, compositionVersion, compositionSeed, mappingVersion, changeScenario string, now time.Time) (*ContractSeatMap, error) {
	if strings.TrimSpace(scheduledServiceRef) == "" || strings.TrimSpace(serviceDate) == "" || strings.TrimSpace(compositionVersion) == "" || strings.TrimSpace(compositionSeed) == "" || strings.TrimSpace(mappingVersion) == "" {
		return nil, fmt.Errorf("scheduledServiceRef, serviceDate, compositionVersion, compositionSeed and mappingVersion are required")
	}
	seatCount := 8
	if strings.EqualFold(changeScenario, "SMALL") || strings.EqualFold(compositionSeed, "SMALL") {
		seatCount = 2
	}
	seatMapID := NewID("smap")
	coachRef := NewID("coach")
	units := make([]SeatUnit, 0, seatCount)
	letters := []string{"A", "B", "C", "D"}
	for i := 0; i < seatCount; i++ {
		row := (i / len(letters)) + 1
		letter := letters[i%len(letters)]
		pos := PositionAisle
		if letter == "A" || letter == "D" {
			pos = PositionWindow
		}
		units = append(units, SeatUnit{SeatUnitRef: NewID("su"), CoachRef: coachRef, CoachNo: "01", SeatNo: fmt.Sprintf("%02d%s", row, letter), AllocationType: AllocationTypeSeat, SeatPosition: pos, RowNo: fmt.Sprintf("%02d", row), AdjacencyGroupKey: fmt.Sprintf("01-%02d", row), Assignable: true})
	}
	return &ContractSeatMap{SeatMapID: seatMapID, ScheduledServiceRef: scheduledServiceRef, ServiceDate: serviceDate, CompositionVersion: compositionVersion, SeatMapVersion: 1, Source: "SIM_SEED", CompositionSeed: compositionSeed, MappingVersion: mappingVersion, Status: SeatMapStatusDraft, Coaches: []Coach{{CoachRef: coachRef, CoachNo: "01", ClassRef: "standard", CoachType: AllocationTypeSeat, Assignable: true, SeatUnits: units}}, CreatedAt: now.UTC()}, nil
}

func (m *ContractSeatMap) Publish(expectedVersion int, now time.Time) error {
	if m.SeatMapVersion != expectedVersion || m.Status != SeatMapStatusDraft {
		return ErrInvalidTransition
	}
	m.SeatMapVersion++
	t := now.UTC()
	m.PublishedAt = &t
	m.Status = SeatMapStatusPublished
	return nil
}

func (m *ContractSeatMap) Retire(expectedVersion int, reason string, now time.Time) error {
	if m.SeatMapVersion != expectedVersion {
		return ErrInvalidTransition
	}
	if m.Status != SeatMapStatusDraft && m.Status != SeatMapStatusPublished && m.Status != SeatMapStatusSuperseded {
		return ErrInvalidTransition
	}
	m.SeatMapVersion++
	t := now.UTC()
	m.RetiredAt = &t
	if strings.EqualFold(reason, "SUPERSEDED") {
		m.Status = SeatMapStatusSuperseded
	} else {
		m.Status = SeatMapStatusRetired
	}
	return nil
}

func (m *ContractSeatMap) MarkSeatUnitUnavailable(ref, reason string, expectedVersion int) error {
	if m.SeatMapVersion != expectedVersion || m.Status == SeatMapStatusPublished {
		return ErrInvalidTransition
	}
	unit, ok := m.FindUnit(ref)
	if !ok {
		return ErrSeatUnavailable
	}
	unit.Assignable = false
	unit.UnavailableReason = strings.TrimSpace(reason)
	m.SeatMapVersion++
	return nil
}

func (m *ContractSeatMap) ReopenSeatUnit(ref string, expectedVersion int) error {
	if m.SeatMapVersion != expectedVersion || m.Status == SeatMapStatusPublished {
		return ErrInvalidTransition
	}
	unit, ok := m.FindUnit(ref)
	if !ok {
		return ErrSeatUnavailable
	}
	unit.Assignable = true
	unit.UnavailableReason = ""
	m.SeatMapVersion++
	return nil
}

func (m *ContractSeatMap) FindUnit(ref string) (*SeatUnit, bool) {
	for ci := range m.Coaches {
		for ui := range m.Coaches[ci].SeatUnits {
			if m.Coaches[ci].SeatUnits[ui].SeatUnitRef == ref {
				return &m.Coaches[ci].SeatUnits[ui], true
			}
		}
	}
	return nil, false
}

func (m ContractSeatMap) UnitByRef(ref string) (SeatUnit, bool) {
	for _, coach := range m.Coaches {
		for _, unit := range coach.SeatUnits {
			if unit.SeatUnitRef == ref {
				return unit, true
			}
		}
	}
	return SeatUnit{}, false
}

func (m ContractSeatMap) AssignableUnits(classRef string) []SeatUnit {
	out := []SeatUnit{}
	for _, coach := range m.Coaches {
		if !coach.Assignable || !strings.EqualFold(coach.ClassRef, classRef) {
			continue
		}
		for _, unit := range coach.SeatUnits {
			if unit.Assignable {
				out = append(out, unit)
			}
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].CoachNo+out[i].SeatNo < out[j].CoachNo+out[j].SeatNo })
	return out
}

type StationInterval struct {
	FromSeq int `json:"fromSeq"`
	ToSeq   int `json:"toSeq"`
}

func (i StationInterval) Valid() bool { return i.FromSeq >= 0 && i.ToSeq > i.FromSeq }
func (i StationInterval) Overlaps(o StationInterval) bool {
	return i.FromSeq < o.ToSeq && o.FromSeq < i.ToSeq
}

type SeatPreferences struct {
	AcceptStanding          bool     `json:"acceptStanding"`
	AdjacencyPreference     string   `json:"adjacencyPreference,omitempty"`
	AdjacencyGroupRef       string   `json:"adjacencyGroupRef,omitempty"`
	PreferredSeatPositions  []string `json:"preferredSeatPositions,omitempty"`
	PreferredBerthPositions []string `json:"preferredBerthPositions,omitempty"`
	SameCompartment         bool     `json:"sameCompartment,omitempty"`
	AvoidSeatUnitRefs       []string `json:"avoidSeatUnitRefs,omitempty"`
	PreferenceVersion       string   `json:"preferenceVersion"`
}

type SeatRef struct {
	SeatAllocationID  string `json:"seatAllocationId"`
	AllocationType    string `json:"allocationType"`
	SeatMapID         string `json:"seatMapId,omitempty"`
	SeatMapVersion    int    `json:"seatMapVersion,omitempty"`
	SeatUnitRef       string `json:"seatUnitRef,omitempty"`
	CoachNo           string `json:"coachNo,omitempty"`
	SeatNo            string `json:"seatNo,omitempty"`
	BerthPosition     string `json:"berthPosition,omitempty"`
	DisplayLabel      string `json:"displayLabel"`
	Degraded          bool   `json:"degraded"`
	DegradationReason string `json:"degradationReason,omitempty"`
}

type ContractSeatAllocation struct {
	SeatAllocationID    string           `json:"seatAllocationId"`
	SegmentBookingID    string           `json:"segmentBookingId"`
	JourneyOrderID      string           `json:"journeyOrderId"`
	TravelerRef         string           `json:"travelerRef"`
	SegmentRef          string           `json:"segmentRef"`
	ScheduledServiceRef string           `json:"scheduledServiceRef"`
	ServiceDate         string           `json:"serviceDate"`
	CapacityHoldID      string           `json:"capacityHoldId"`
	CapacityUnitRef     string           `json:"capacityUnitRef"`
	Interval            StationInterval  `json:"interval"`
	SeatRef             SeatRef          `json:"seatRef"`
	Status              string           `json:"status"`
	Preferences         *SeatPreferences `json:"preferences,omitempty"`
	CreatedAt           time.Time        `json:"createdAt"`
	ConfirmedAt         *time.Time       `json:"confirmedAt,omitempty"`
	ReleasedAt          *time.Time       `json:"releasedAt,omitempty"`
	ExpiresAt           time.Time        `json:"expiresAt"`
}

func (a *ContractSeatAllocation) Release(now time.Time) {
	if a.Status == AllocationStatusReleased || a.Status == AllocationStatusExpired {
		return
	}
	t := now.UTC()
	a.ReleasedAt = &t
	a.Status = AllocationStatusReleased
}

func (a *ContractSeatAllocation) Expire(now time.Time) {
	if a.Status == AllocationStatusReleased || a.Status == AllocationStatusExpired || a.Status == AllocationStatusConfirmed {
		return
	}
	t := now.UTC()
	a.ReleasedAt = &t
	a.Status = AllocationStatusExpired
}
