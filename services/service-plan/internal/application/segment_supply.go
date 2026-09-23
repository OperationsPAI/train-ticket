package application

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
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
		StopSequenceVersion: fmt.Sprintf("sha256:%x", sha256.Sum256(material)), Stops: stops, DepartureTime: segment.DepartureTime, ArrivalTime: segment.ArrivalTime}
	result.Interval.FromSeq, result.Interval.ToSeq = stops[0].Sequence, stops[1].Sequence
	return result, nil
}
