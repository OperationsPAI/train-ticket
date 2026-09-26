package application

import (
	"context"
	"strings"
	"time"

	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

type SeatCapacityFacts struct {
	StandingPolicy      *domain.StandingPolicy          `json:"standingPolicy,omitempty"`
	ScheduledServiceRef string                          `json:"scheduledServiceRef"`
	ServiceDate         string                          `json:"serviceDate"`
	ClassRef            string                          `json:"classRef"`
	SeatMapID           string                          `json:"seatMapId"`
	SeatMapVersion      int                             `json:"seatMapVersion"`
	CompositionVersion  string                          `json:"compositionVersion"`
	CapturedAt          time.Time                       `json:"capturedAt"`
	Units               []domain.SeatUnit               `json:"units"`
	Allocations         []domain.ContractSeatAllocation `json:"allocations"`
	ReplacementMappings []CapacityUnitReplacement       `json:"replacementMappings,omitempty"`
}

type CapacityUnitReplacement struct {
	SourceSeatMapID          string `json:"sourceSeatMapId"`
	SourceSeatMapVersion     int    `json:"sourceSeatMapVersion"`
	SourceCompositionVersion string `json:"sourceCompositionVersion"`
	SourceSeatUnitRef        string `json:"sourceSeatUnitRef"`
	TargetSeatUnitRef        string `json:"targetSeatUnitRef"`
	MappingVersion           string `json:"mappingVersion"`
}

// SeatCapacityComposition is the part of the published supply that only changes when a new
// composition version is published. Capacity caches it until the occupancy reports another
// version, so admission does not refetch every physical unit for every Hold.
type SeatCapacityComposition struct {
	StandingPolicy      *domain.StandingPolicy    `json:"standingPolicy,omitempty"`
	ScheduledServiceRef string                    `json:"scheduledServiceRef"`
	ServiceDate         string                    `json:"serviceDate"`
	ClassRef            string                    `json:"classRef"`
	SeatMapID           string                    `json:"seatMapId"`
	SeatMapVersion      int                       `json:"seatMapVersion"`
	CompositionVersion  string                    `json:"compositionVersion"`
	CapturedAt          time.Time                 `json:"capturedAt"`
	Units               []domain.SeatUnit         `json:"units"`
	ReplacementMappings []CapacityUnitReplacement `json:"replacementMappings,omitempty"`
}

// SeatCapacityOccupancy carries the active allocations of one class together with the
// composition identity they were resolved against.
type SeatCapacityOccupancy struct {
	ScheduledServiceRef string                          `json:"scheduledServiceRef"`
	ServiceDate         string                          `json:"serviceDate"`
	ClassRef            string                          `json:"classRef"`
	SeatMapID           string                          `json:"seatMapId"`
	SeatMapVersion      int                             `json:"seatMapVersion"`
	CompositionVersion  string                          `json:"compositionVersion"`
	CapturedAt          time.Time                       `json:"capturedAt"`
	Allocations         []domain.ContractSeatAllocation `json:"allocations"`
}

func (s *Service) CapacityFacts(ctx context.Context, service, date, class string) (*SeatCapacityFacts, error) {
	if err := validateCapacityQuery(service, date, class); err != nil {
		return nil, err
	}
	var result *SeatCapacityFacts
	err := s.tx(ctx, func(tx context.Context) error {
		active, err := s.repo.FindActiveSeatAllocations(tx, service, date)
		if err != nil {
			return err
		}
		selected, err := s.publishedClassMap(tx, service, date, class)
		if err != nil {
			return err
		}
		allocations, err := s.classAllocations(tx, service, date, class, selected, active)
		if err != nil {
			return err
		}
		mappings, err := s.capacityUnitReplacements(tx, selected)
		if err != nil {
			return err
		}
		result = &SeatCapacityFacts{ScheduledServiceRef: service, ServiceDate: date, ClassRef: class,
			SeatMapID: selected.SeatMapID, SeatMapVersion: selected.SeatMapVersion, CompositionVersion: selected.CompositionVersion,
			CapturedAt: time.Now().UTC(), Units: selected.AssignableUnits(class), Allocations: allocations, ReplacementMappings: mappings}
		if class == "STANDING" {
			policy := selected.StandingPolicy()
			result.StandingPolicy = &policy
		}
		return nil
	})
	return result, err
}

func (s *Service) CapacityComposition(ctx context.Context, service, date, class string) (*SeatCapacityComposition, error) {
	if err := validateCapacityQuery(service, date, class); err != nil {
		return nil, err
	}
	var result *SeatCapacityComposition
	err := s.tx(ctx, func(tx context.Context) error {
		selected, err := s.publishedClassMap(tx, service, date, class)
		if err != nil {
			return err
		}
		mappings, err := s.capacityUnitReplacements(tx, selected)
		if err != nil {
			return err
		}
		result = &SeatCapacityComposition{ScheduledServiceRef: service, ServiceDate: date, ClassRef: class,
			SeatMapID: selected.SeatMapID, SeatMapVersion: selected.SeatMapVersion, CompositionVersion: selected.CompositionVersion,
			CapturedAt: time.Now().UTC(), Units: selected.AssignableUnits(class), ReplacementMappings: mappings}
		if class == "STANDING" {
			policy := selected.StandingPolicy()
			result.StandingPolicy = &policy
		}
		return nil
	})
	return result, err
}

func (s *Service) CapacityOccupancy(ctx context.Context, service, date, class string) (*SeatCapacityOccupancy, error) {
	if err := validateCapacityQuery(service, date, class); err != nil {
		return nil, err
	}
	var result *SeatCapacityOccupancy
	err := s.tx(ctx, func(tx context.Context) error {
		active, err := s.repo.FindActiveSeatAllocations(tx, service, date)
		if err != nil {
			return err
		}
		selected, err := s.publishedClassMap(tx, service, date, class)
		if err != nil {
			return err
		}
		allocations, err := s.classAllocations(tx, service, date, class, selected, active)
		if err != nil {
			return err
		}
		result = &SeatCapacityOccupancy{ScheduledServiceRef: service, ServiceDate: date, ClassRef: class,
			SeatMapID: selected.SeatMapID, SeatMapVersion: selected.SeatMapVersion, CompositionVersion: selected.CompositionVersion,
			CapturedAt: time.Now().UTC(), Allocations: allocations}
		return nil
	})
	return result, err
}

func validateCapacityQuery(service, date, class string) error {
	if strings.TrimSpace(service) == "" || strings.TrimSpace(class) == "" {
		return derr("VALIDATION_FAILED", "scheduledServiceRef and classRef are required")
	}
	if _, err := time.Parse("2006-01-02", date); err != nil {
		return derr("VALIDATION_FAILED", "serviceDate must be a calendar date")
	}
	return nil
}

// publishedClassMap selects the published map that carries the requested class.
func (s *Service) publishedClassMap(ctx context.Context, service, date, class string) (*domain.ContractSeatMap, error) {
	maps, total, err := s.seatMaps(ctx, service, date, domain.SeatMapStatusPublished)
	if err != nil {
		return nil, err
	}
	if total > 100 {
		return nil, derr("CONFLICT", "published seat map count exceeds the allocation selection limit")
	}
	matching := []domain.ContractSeatMap{}
	for index := range maps {
		basis := class
		if class == "STANDING" {
			basis = "SECOND_CLASS"
		}
		if len(maps[index].AssignableUnits(basis)) > 0 {
			matching = append(matching, maps[index])
		}
	}
	if len(matching) != 1 {
		return nil, derr("PRECONDITION_FAILED", "published SeatMap selection is missing or ambiguous")
	}
	return &matching[0], nil
}

func (s *Service) capacityUnitReplacements(ctx context.Context, target *domain.ContractSeatMap) ([]CapacityUnitReplacement, error) {
	maps, total, err := s.seatMaps(ctx, target.ScheduledServiceRef, target.ServiceDate, "")
	if err != nil {
		return nil, err
	}
	if total > 100 {
		return nil, derr("CONFLICT", "seat map replacement history exceeds selection limit")
	}
	byID := map[string]domain.ContractSeatMap{}
	for _, candidate := range maps {
		byID[candidate.SeatMapID] = candidate
	}
	targetByLocator := map[string][]domain.SeatUnit{}
	for _, coach := range target.Coaches {
		for _, unit := range coach.SeatUnits {
			if unit.Assignable {
				key := unitLocator(coach.ClassRef, unit)
				targetByLocator[key] = append(targetByLocator[key], unit)
			}
		}
	}
	result := []CapacityUnitReplacement{}
	visited := map[string]bool{}
	for sourceID := target.ReplacesSeatMapID; sourceID != ""; {
		if visited[sourceID] {
			return nil, derr("CONFLICT", "seat map replacement lineage contains a cycle")
		}
		visited[sourceID] = true
		source, ok := byID[sourceID]
		if !ok {
			return nil, derr("CONFLICT", "seat map replacement lineage is incomplete")
		}
		for _, coach := range source.Coaches {
			for _, unit := range coach.SeatUnits {
				matches := targetByLocator[unitLocator(coach.ClassRef, unit)]
				if len(matches) > 1 {
					return nil, derr("CONFLICT", "seat map replacement mapping is ambiguous")
				}
				if len(matches) == 1 {
					result = append(result, CapacityUnitReplacement{SourceSeatMapID: source.SeatMapID,
						SourceSeatMapVersion: source.SeatMapVersion, SourceCompositionVersion: source.CompositionVersion,
						SourceSeatUnitRef: unit.SeatUnitRef, TargetSeatUnitRef: matches[0].SeatUnitRef, MappingVersion: target.MappingVersion})
				}
			}
		}
		sourceID = source.ReplacesSeatMapID
	}
	return result, nil
}

func unitLocator(class string, unit domain.SeatUnit) string {
	return strings.Join([]string{strings.ToUpper(class), unit.AllocationType, unit.CoachNo, unit.SeatNo}, "|")
}

// classAllocations keeps the active allocations whose unit belongs to the requested class. The
// unit to class index is built once per loaded map, so classification does not rescan every unit
// of every map for every allocation.
func (s *Service) classAllocations(ctx context.Context, service, date, class string, selected *domain.ContractSeatMap, active []domain.ContractSeatAllocation) ([]domain.ContractSeatAllocation, error) {
	loaded := map[string]map[string]string{selected.SeatMapID: unitClassIndex(selected)}
	out := []domain.ContractSeatAllocation{}
	for _, allocation := range active {
		if allocation.SeatRef.AllocationType == domain.AllocationTypeStanding {
			if class == "STANDING" {
				out = append(out, allocation)
			}
			continue
		}
		index, ok := loaded[allocation.SeatRef.SeatMapID]
		if !ok {
			seatMap, _, err := s.repo.GetSeatMap(ctx, allocation.SeatRef.SeatMapID)
			if err != nil {
				return nil, err
			}
			if seatMap == nil {
				return nil, derr("CONFLICT", "active allocation references a missing seat map")
			}
			if seatMap.ScheduledServiceRef != service || seatMap.ServiceDate != date {
				return nil, derr("CONFLICT", "active allocation references a different service or date")
			}
			index = unitClassIndex(seatMap)
			loaded[seatMap.SeatMapID] = index
		}
		owner, found := index[allocation.SeatRef.SeatUnitRef]
		if !found {
			return nil, derr("CONFLICT", "active allocation references a missing seat unit")
		}
		if owner == class {
			out = append(out, allocation)
		}
	}
	return out, nil
}

func unitClassIndex(seatMap *domain.ContractSeatMap) map[string]string {
	index := map[string]string{}
	for _, coach := range seatMap.Coaches {
		for _, unit := range coach.SeatUnits {
			index[unit.SeatUnitRef] = coach.ClassRef
		}
	}
	return index
}
