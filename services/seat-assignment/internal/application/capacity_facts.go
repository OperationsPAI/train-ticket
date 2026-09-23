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
}

func (s *Service) CapacityFacts(ctx context.Context, service, date, class string) (*SeatCapacityFacts, error) {
	if strings.TrimSpace(service) == "" || strings.TrimSpace(class) == "" {
		return nil, derr("VALIDATION_FAILED", "scheduledServiceRef and classRef are required")
	}
	if _, err := time.Parse("2006-01-02", date); err != nil {
		return nil, derr("VALIDATION_FAILED", "serviceDate must be a calendar date")
	}
	var result *SeatCapacityFacts
	err := s.tx(ctx, func(tx context.Context) error {
		active, err := s.repo.FindActiveSeatAllocations(tx, service, date)
		if err != nil {
			return err
		}
		maps, total, err := s.repo.FindSeatMaps(tx, service, date, domain.SeatMapStatusPublished, 100, 0)
		if err != nil {
			return err
		}
		if total > 100 {
			return derr("CONFLICT", "published seat map count exceeds the allocation selection limit")
		}
		var selected *domain.ContractSeatMap
		for index := range maps {
			if len(maps[index].AssignableUnits(class)) > 0 {
				selected = &maps[index]
				break
			}
		}
		if class == "STANDING" {
			selected = domain.StandingPolicyMap(maps)
		}
		if selected == nil {
			return derr("PRECONDITION_FAILED", "no published SeatMap matches scheduled service, date and class")
		}
		result = &SeatCapacityFacts{ScheduledServiceRef: service, ServiceDate: date, ClassRef: class,
			SeatMapID: selected.SeatMapID, SeatMapVersion: selected.SeatMapVersion, CompositionVersion: selected.CompositionVersion,
			CapturedAt: time.Now().UTC(), Units: selected.AssignableUnits(class), Allocations: []domain.ContractSeatAllocation{}}
		if class == "STANDING" {
			policy := selected.StandingPolicy()
			result.StandingPolicy = &policy
		}
		loaded := map[string]*domain.ContractSeatMap{selected.SeatMapID: selected}
		for _, allocation := range active {
			if allocation.SeatRef.AllocationType == domain.AllocationTypeStanding {
				if class == "STANDING" {
					result.Allocations = append(result.Allocations, allocation)
				}
				continue
			}
			seatMap := loaded[allocation.SeatRef.SeatMapID]
			if seatMap == nil {
				seatMap, _, err = s.repo.GetSeatMap(tx, allocation.SeatRef.SeatMapID)
				if err != nil {
					return err
				}
				if seatMap == nil {
					return derr("CONFLICT", "active allocation references a missing seat map")
				}
				loaded[seatMap.SeatMapID] = seatMap
			}
			if seatMap.ScheduledServiceRef != service || seatMap.ServiceDate != date {
				return derr("CONFLICT", "active allocation references a different service or date")
			}
			found := false
			for _, coach := range seatMap.Coaches {
				for _, unit := range coach.SeatUnits {
					if unit.SeatUnitRef == allocation.SeatRef.SeatUnitRef {
						found = true
						if coach.ClassRef == class {
							result.Allocations = append(result.Allocations, allocation)
						}
					}
				}
			}
			if !found {
				return derr("CONFLICT", "active allocation references a missing seat unit")
			}
		}
		return nil
	})
	return result, err
}
