package application

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

type SupplyStop struct {
	Sequence int    `json:"sequence"`
	NodeRef  string `json:"nodeRef"`
}

type SegmentSupply struct {
	SegmentRef          string       `json:"segmentRef"`
	ScheduledServiceRef string       `json:"scheduledServiceRef"`
	ServiceDate         string       `json:"serviceDate"`
	StopSequenceVersion string       `json:"stopSequenceVersion"`
	Stops               []SupplyStop `json:"stops"`
	Interval            struct {
		FromSeq int `json:"fromSeq"`
		ToSeq   int `json:"toSeq"`
	} `json:"interval"`
	DepartureTime time.Time `json:"departureTime"`
	ArrivalTime   time.Time `json:"arrivalTime"`
}

func (s *Service) GetSegmentSupply(ctx context.Context, ref string) (*SegmentSupply, error) {
	segment, err := s.GetServiceSegment(ctx, ref)
	if err != nil {
		return nil, err
	}
	service, err := s.GetScheduledService(ctx, segment.ScheduledServiceRef)
	if err != nil {
		return nil, err
	}
	if !serviceViewHasSegment(service, segment.OriginStopRef, segment.DestinationStopRef) || service.OriginNodeID == service.DestinationNodeID {
		return nil, fmt.Errorf("%w: segment endpoints differ from scheduled service", ErrDomainRule)
	}
	if !segment.DepartureTime.Equal(service.DepartureTime) || !segment.ArrivalTime.Equal(service.ArrivalTime) {
		return nil, fmt.Errorf("%w: segment timestamps differ from scheduled service", ErrDomainRule)
	}
	stops := []SupplyStop{{Sequence: 0, NodeRef: service.OriginNodeID}, {Sequence: 1, NodeRef: service.DestinationNodeID}}
	material, err := json.Marshal(struct {
		Service string       `json:"service"`
		Stops   []SupplyStop `json:"stops"`
	}{service.ScheduledServiceRef, stops})
	if err != nil {
		return nil, err
	}
	result := &SegmentSupply{SegmentRef: segment.SegmentRef, ScheduledServiceRef: service.ScheduledServiceRef, ServiceDate: service.DepartureTime.UTC().Format("2006-01-02"),
		StopSequenceVersion: fmt.Sprintf("%s:%x", s.stopSequenceDigest(), sha256.Sum256(material)), Stops: stops, DepartureTime: segment.DepartureTime, ArrivalTime: segment.ArrivalTime}
	result.Interval.FromSeq, result.Interval.ToSeq = s.publishedInterval(stops)
	return result, nil
}

// publishedInterval names the stop positions the published interval covers.
// The contract writes the interval half open, so the end names the first
// position past the segment and a caller reads the bound the service states.
func (s *Service) publishedInterval(stops []SupplyStop) (int, int) {
	first, last := stops[0].Sequence, stops[len(stops)-1].Sequence
	if strings.EqualFold(strings.TrimSpace(s.supply.IntervalBound), SupplyIntervalBoundExclusive) {
		last = last + 1
	}
	return first, last
}

func (s *Service) stopSequenceDigest() string {
	if strings.TrimSpace(s.supply.StopSequenceDigest) == "" {
		return "sha256"
	}
	return strings.TrimSpace(s.supply.StopSequenceDigest)
}
