package domain

import (
	"fmt"
	"strings"
	"time"
)

// Stable cross-context references. Service Plan stores these identifiers but
// does not own the referenced Place & Network, Supplier, Capacity, Fare, Order,
// or Disruption state.
type (
	ServicePlanID       string
	ServicePlanKey      string
	ServicePatternID    string
	CalendarID          string
	TimetableID         string
	PlanVersionID       string
	ScheduledServiceID  string
	ScheduledServiceKey string
	ServiceSegmentID    string
	TransportNodeID     string
	NodeSnapshotVersion string
	CarrierID           string
)

type ServiceMode string

const (
	ServiceModeTrain   ServiceMode = "TRAIN"
	ServiceModeBus     ServiceMode = "BUS"
	ServiceModeAir     ServiceMode = "AIR"
	ServiceModeFerry   ServiceMode = "FERRY"
	ServiceModeShuttle ServiceMode = "SHUTTLE"
)

type PlanVersionStatus string

const (
	PlanVersionDraft      PlanVersionStatus = "DRAFT"
	PlanVersionValidated  PlanVersionStatus = "VALIDATED"
	PlanVersionPublished  PlanVersionStatus = "PUBLISHED"
	PlanVersionSuperseded PlanVersionStatus = "SUPERSEDED"
	PlanVersionWithdrawn  PlanVersionStatus = "WITHDRAWN"
)

type ScheduledServiceStatus string

const (
	ScheduledServicePlanned   ScheduledServiceStatus = "PLANNED"
	ScheduledServiceActive    ScheduledServiceStatus = "ACTIVE"
	ScheduledServiceSuspended ScheduledServiceStatus = "SUSPENDED"
	ScheduledServiceReplaced  ScheduledServiceStatus = "REPLACED"
	ScheduledServiceRetired   ScheduledServiceStatus = "RETIRED"
	ScheduledServiceExpired   ScheduledServiceStatus = "EXPIRED"
)

// ServiceStop is a planned stop reference. It intentionally stores only stable
// Place & Network identifiers and boarding semantics; station topology,
// inventory, fare, order, and disruption state belong to other contexts.
type ServiceStop struct {
	Sequence         int
	TransportNodeID  TransportNodeID
	NodeSnapshot     NodeSnapshotVersion
	BoardingAllowed  bool
	AlightingAllowed bool
}

func NewServiceStop(sequence int, nodeID TransportNodeID, nodeSnapshot NodeSnapshotVersion, boardingAllowed, alightingAllowed bool) (ServiceStop, error) {
	stop := ServiceStop{
		Sequence:         sequence,
		TransportNodeID:  TransportNodeID(strings.TrimSpace(string(nodeID))),
		NodeSnapshot:     NodeSnapshotVersion(strings.TrimSpace(string(nodeSnapshot))),
		BoardingAllowed:  boardingAllowed,
		AlightingAllowed: alightingAllowed,
	}
	if err := stop.Validate(); err != nil {
		return ServiceStop{}, err
	}
	return stop, nil
}

func (s ServiceStop) Validate() error {
	if s.Sequence <= 0 {
		return fmt.Errorf("service stop sequence must be positive")
	}
	if strings.TrimSpace(string(s.TransportNodeID)) == "" {
		return fmt.Errorf("service stop transport node id is required")
	}
	if strings.TrimSpace(string(s.NodeSnapshot)) == "" {
		return fmt.Errorf("service stop node snapshot version is required")
	}
	if !s.BoardingAllowed && !s.AlightingAllowed {
		return fmt.Errorf("service stop must allow boarding or alighting")
	}
	return nil
}

// ServicePattern is a stable ordered stop sequence for a service mode and
// carrier. It validates only Service Plan invariants; carrier capability checks
// and node existence checks happen in upstream contexts or application services.
type ServicePattern struct {
	id          ServicePatternID
	serviceMode ServiceMode
	carrierID   CarrierID
	stops       []ServiceStop
}

func NewServicePattern(id ServicePatternID, mode ServiceMode, carrierID CarrierID, stops []ServiceStop) (ServicePattern, error) {
	pattern := ServicePattern{
		id:          ServicePatternID(strings.TrimSpace(string(id))),
		serviceMode: mode,
		carrierID:   CarrierID(strings.TrimSpace(string(carrierID))),
		stops:       copyStops(stops),
	}
	if err := pattern.Validate(); err != nil {
		return ServicePattern{}, err
	}
	return pattern, nil
}

func (p ServicePattern) ID() ServicePatternID     { return p.id }
func (p ServicePattern) ServiceMode() ServiceMode { return p.serviceMode }
func (p ServicePattern) CarrierID() CarrierID     { return p.carrierID }
func (p ServicePattern) Stops() []ServiceStop     { return copyStops(p.stops) }

func (p ServicePattern) Validate() error {
	if strings.TrimSpace(string(p.id)) == "" {
		return fmt.Errorf("service pattern id is required")
	}
	if !validServiceMode(p.serviceMode) {
		return fmt.Errorf("unsupported service mode: %q", p.serviceMode)
	}
	if strings.TrimSpace(string(p.carrierID)) == "" {
		return fmt.Errorf("service pattern carrier id is required")
	}
	if len(p.stops) < 2 {
		return fmt.Errorf("service pattern requires at least two stops")
	}
	seenSequences := make(map[int]struct{}, len(p.stops))
	previous := 0
	for i, stop := range p.stops {
		if err := stop.Validate(); err != nil {
			return fmt.Errorf("invalid stop at index %d: %w", i, err)
		}
		if _, exists := seenSequences[stop.Sequence]; exists {
			return fmt.Errorf("duplicate service stop sequence: %d", stop.Sequence)
		}
		if stop.Sequence <= previous {
			return fmt.Errorf("service stop sequences must be strictly increasing")
		}
		seenSequences[stop.Sequence] = struct{}{}
		previous = stop.Sequence
	}
	return nil
}

// ServiceSegment is derived from two ordered ServiceStops. It is a planning and
// capacity-seeding reference only; it never carries inventory, price, purchase,
// or disruption state.
type ServiceSegment struct {
	ID                ServiceSegmentID
	PatternID         ServicePatternID
	FromSequence      int
	ToSequence        int
	FromNodeID        TransportNodeID
	ToNodeID          TransportNodeID
	NodeSnapshotStart NodeSnapshotVersion
	NodeSnapshotEnd   NodeSnapshotVersion
}

func DeriveServiceSegments(pattern ServicePattern) ([]ServiceSegment, error) {
	if err := pattern.Validate(); err != nil {
		return nil, err
	}
	stops := pattern.Stops()
	segments := make([]ServiceSegment, 0, len(stops)*(len(stops)-1)/2)
	for i := 0; i < len(stops)-1; i++ {
		for j := i + 1; j < len(stops); j++ {
			from, to := stops[i], stops[j]
			segments = append(segments, ServiceSegment{
				ID:                ServiceSegmentID(fmt.Sprintf("%s:%d-%d", pattern.ID(), from.Sequence, to.Sequence)),
				PatternID:         pattern.ID(),
				FromSequence:      from.Sequence,
				ToSequence:        to.Sequence,
				FromNodeID:        from.TransportNodeID,
				ToNodeID:          to.TransportNodeID,
				NodeSnapshotStart: from.NodeSnapshot,
				NodeSnapshotEnd:   to.NodeSnapshot,
			})
		}
	}
	return segments, nil
}

type CalendarExceptionKind string

const (
	CalendarExceptionAddedService     CalendarExceptionKind = "ADDED_SERVICE"
	CalendarExceptionSuspendedService CalendarExceptionKind = "SUSPENDED_SERVICE"
)

type CalendarException struct {
	Date       time.Time
	Kind       CalendarExceptionKind
	ReasonCode string
}

// Calendar defines planned operating days and planned exceptions. Realtime
// cancellation or recovery remains in Disruption Recovery.
type Calendar struct {
	id                CalendarID
	validFrom         time.Time
	validTo           time.Time
	operatingWeekdays map[time.Weekday]struct{}
	exceptions        map[string]CalendarException
}

func NewCalendar(id CalendarID, validFrom, validTo time.Time, operatingWeekdays []time.Weekday) (Calendar, error) {
	weekdays := make(map[time.Weekday]struct{}, len(operatingWeekdays))
	for _, weekday := range operatingWeekdays {
		if weekday < time.Sunday || weekday > time.Saturday {
			return Calendar{}, fmt.Errorf("unsupported weekday: %d", weekday)
		}
		weekdays[weekday] = struct{}{}
	}
	calendar := Calendar{
		id:                CalendarID(strings.TrimSpace(string(id))),
		validFrom:         normalizeDate(validFrom),
		validTo:           normalizeDate(validTo),
		operatingWeekdays: weekdays,
		exceptions:        map[string]CalendarException{},
	}
	if err := calendar.Validate(); err != nil {
		return Calendar{}, err
	}
	return calendar, nil
}

func (c Calendar) ID() CalendarID       { return c.id }
func (c Calendar) ValidFrom() time.Time { return c.validFrom }
func (c Calendar) ValidTo() time.Time   { return c.validTo }
func (c Calendar) Exceptions() []CalendarException {
	exceptions := make([]CalendarException, 0, len(c.exceptions))
	for _, exception := range c.exceptions {
		exceptions = append(exceptions, exception)
	}
	return exceptions
}

func (c Calendar) Validate() error {
	if strings.TrimSpace(string(c.id)) == "" {
		return fmt.Errorf("calendar id is required")
	}
	if c.validFrom.IsZero() || c.validTo.IsZero() {
		return fmt.Errorf("calendar valid window is required")
	}
	if c.validTo.Before(c.validFrom) {
		return fmt.Errorf("calendar valid-to must be on or after valid-from")
	}
	if len(c.operatingWeekdays) == 0 && len(c.exceptions) == 0 {
		return fmt.Errorf("calendar requires weekday rules or explicit exceptions")
	}
	seen := map[string]CalendarExceptionKind{}
	for key, exception := range c.exceptions {
		if err := validateCalendarException(exception); err != nil {
			return err
		}
		if !c.includesDate(exception.Date) {
			return fmt.Errorf("calendar exception date %s is outside valid window", key)
		}
		dateKey := dateKey(exception.Date)
		if previous, exists := seen[dateKey]; exists && previous != exception.Kind {
			return fmt.Errorf("calendar exception conflict on %s", dateKey)
		}
		seen[dateKey] = exception.Kind
	}
	return nil
}

func (c Calendar) AddException(date time.Time, kind CalendarExceptionKind, reasonCode string) (Calendar, error) {
	exception := CalendarException{Date: normalizeDate(date), Kind: kind, ReasonCode: strings.TrimSpace(reasonCode)}
	if err := validateCalendarException(exception); err != nil {
		return Calendar{}, err
	}
	if !c.includesDate(exception.Date) {
		return Calendar{}, fmt.Errorf("calendar exception date %s is outside valid window", dateKey(exception.Date))
	}
	next := c.copy()
	key := dateKey(exception.Date)
	if existing, exists := next.exceptions[key]; exists {
		if existing.Kind != exception.Kind {
			return Calendar{}, fmt.Errorf("calendar exception conflict on %s: %s vs %s", key, existing.Kind, exception.Kind)
		}
		return Calendar{}, fmt.Errorf("duplicate calendar exception on %s", key)
	}
	next.exceptions[key] = exception
	return next, nil
}

func (c Calendar) RunsOn(date time.Time) bool {
	date = normalizeDate(date)
	if !c.includesDate(date) {
		return false
	}
	if exception, exists := c.exceptions[dateKey(date)]; exists {
		return exception.Kind == CalendarExceptionAddedService
	}
	_, operates := c.operatingWeekdays[date.Weekday()]
	return operates
}

func (c Calendar) includesDate(date time.Time) bool {
	date = normalizeDate(date)
	return !date.Before(c.validFrom) && !date.After(c.validTo)
}

func (c Calendar) copy() Calendar {
	next := Calendar{
		id:                c.id,
		validFrom:         c.validFrom,
		validTo:           c.validTo,
		operatingWeekdays: make(map[time.Weekday]struct{}, len(c.operatingWeekdays)),
		exceptions:        make(map[string]CalendarException, len(c.exceptions)),
	}
	for weekday := range c.operatingWeekdays {
		next.operatingWeekdays[weekday] = struct{}{}
	}
	for key, exception := range c.exceptions {
		next.exceptions[key] = exception
	}
	return next
}

type PlannedTime struct {
	DayOffset   int
	MinuteOfDay int
}

func NewPlannedTime(dayOffset int, minuteOfDay time.Duration) (PlannedTime, error) {
	planned := PlannedTime{DayOffset: dayOffset, MinuteOfDay: int(minuteOfDay / time.Minute)}
	if err := planned.Validate(); err != nil {
		return PlannedTime{}, err
	}
	return planned, nil
}

func (p PlannedTime) Validate() error {
	if p.DayOffset < 0 {
		return fmt.Errorf("planned time day offset cannot be negative")
	}
	if p.MinuteOfDay < 0 || p.MinuteOfDay >= 24*60 {
		return fmt.Errorf("planned time minute-of-day must be within a service day")
	}
	return nil
}

func (p PlannedTime) absoluteMinute() int {
	return p.DayOffset*24*60 + p.MinuteOfDay
}

type StopTime struct {
	Sequence  int
	Arrival   *PlannedTime
	Departure *PlannedTime
}

func NewStopTime(sequence int, arrival, departure *PlannedTime) (StopTime, error) {
	stopTime := StopTime{Sequence: sequence, Arrival: copyPlannedTimePtr(arrival), Departure: copyPlannedTimePtr(departure)}
	if err := stopTime.Validate(); err != nil {
		return StopTime{}, err
	}
	return stopTime, nil
}

func (s StopTime) Validate() error {
	if s.Sequence <= 0 {
		return fmt.Errorf("stop time sequence must be positive")
	}
	if s.Arrival == nil && s.Departure == nil {
		return fmt.Errorf("stop time requires arrival or departure")
	}
	if s.Arrival != nil {
		if err := s.Arrival.Validate(); err != nil {
			return err
		}
	}
	if s.Departure != nil {
		if err := s.Departure.Validate(); err != nil {
			return err
		}
	}
	if s.Arrival != nil && s.Departure != nil && s.Departure.absoluteMinute() < s.Arrival.absoluteMinute() {
		return fmt.Errorf("stop time departure cannot be before arrival at sequence %d", s.Sequence)
	}
	return nil
}

func (s StopTime) earliestMinute() int {
	if s.Arrival != nil {
		return s.Arrival.absoluteMinute()
	}
	return s.Departure.absoluteMinute()
}

func (s StopTime) latestMinute() int {
	if s.Departure != nil {
		return s.Departure.absoluteMinute()
	}
	return s.Arrival.absoluteMinute()
}

// Timetable expresses planned stop times with explicit cross-day offsets in a
// single time zone. It does not express actual arrivals, delays, cancellation,
// price, or availability.
type Timetable struct {
	id        TimetableID
	timezone  string
	stopTimes []StopTime
}

func NewTimetable(id TimetableID, timezone string, stopTimes []StopTime) (Timetable, error) {
	timetable := Timetable{
		id:        TimetableID(strings.TrimSpace(string(id))),
		timezone:  strings.TrimSpace(timezone),
		stopTimes: copyStopTimes(stopTimes),
	}
	if err := timetable.Validate(); err != nil {
		return Timetable{}, err
	}
	return timetable, nil
}

func (t Timetable) ID() TimetableID       { return t.id }
func (t Timetable) Timezone() string      { return t.timezone }
func (t Timetable) StopTimes() []StopTime { return copyStopTimes(t.stopTimes) }

func (t Timetable) Validate() error {
	if strings.TrimSpace(string(t.id)) == "" {
		return fmt.Errorf("timetable id is required")
	}
	if t.timezone == "" {
		return fmt.Errorf("timetable timezone is required")
	}
	if _, err := time.LoadLocation(t.timezone); err != nil {
		return fmt.Errorf("invalid timetable timezone %q: %w", t.timezone, err)
	}
	if len(t.stopTimes) < 2 {
		return fmt.Errorf("timetable requires at least two stop times")
	}
	previousSequence := 0
	previousMinute := -1
	for i, stopTime := range t.stopTimes {
		if err := stopTime.Validate(); err != nil {
			return fmt.Errorf("invalid stop time at index %d: %w", i, err)
		}
		if stopTime.Sequence <= previousSequence {
			return fmt.Errorf("timetable stop sequences must be strictly increasing")
		}
		if stopTime.earliestMinute() < previousMinute {
			return fmt.Errorf("timetable times must be non-decreasing; use day offsets for cross-day services")
		}
		previousSequence = stopTime.Sequence
		previousMinute = stopTime.latestMinute()
	}
	return nil
}

func (t Timetable) ValidateAgainst(pattern ServicePattern) error {
	if err := t.Validate(); err != nil {
		return err
	}
	stops := pattern.Stops()
	if len(stops) != len(t.stopTimes) {
		return fmt.Errorf("timetable stop count must match service pattern")
	}
	for i := range stops {
		if stops[i].Sequence != t.stopTimes[i].Sequence {
			return fmt.Errorf("timetable sequence %d does not match pattern sequence %d", t.stopTimes[i].Sequence, stops[i].Sequence)
		}
	}
	return nil
}

type PlanVersion struct {
	id            PlanVersionID
	status        PlanVersionStatus
	effectiveFrom time.Time
	effectiveTo   time.Time
	pattern       ServicePattern
	calendar      Calendar
	timetable     Timetable
	publishedAt   *time.Time
}

func NewDraftPlanVersion(id PlanVersionID, effectiveFrom, effectiveTo time.Time, pattern ServicePattern, calendar Calendar, timetable Timetable) (PlanVersion, error) {
	version := PlanVersion{
		id:            PlanVersionID(strings.TrimSpace(string(id))),
		status:        PlanVersionDraft,
		effectiveFrom: normalizeDate(effectiveFrom),
		effectiveTo:   normalizeDate(effectiveTo),
		pattern:       pattern,
		calendar:      calendar,
		timetable:     timetable,
	}
	if err := version.Validate(); err != nil {
		return PlanVersion{}, err
	}
	return version, nil
}

func (v PlanVersion) ID() PlanVersionID         { return v.id }
func (v PlanVersion) Status() PlanVersionStatus { return v.status }
func (v PlanVersion) EffectiveFrom() time.Time  { return v.effectiveFrom }
func (v PlanVersion) EffectiveTo() time.Time    { return v.effectiveTo }
func (v PlanVersion) Pattern() ServicePattern   { return v.pattern }
func (v PlanVersion) Calendar() Calendar        { return v.calendar.copy() }
func (v PlanVersion) Timetable() Timetable      { return v.timetable }
func (v PlanVersion) PublishedAt() *time.Time   { return copyTimePtr(v.publishedAt) }

func (v PlanVersion) Validate() error {
	if strings.TrimSpace(string(v.id)) == "" {
		return fmt.Errorf("plan version id is required")
	}
	if !validPlanVersionStatus(v.status) {
		return fmt.Errorf("unsupported plan version status: %q", v.status)
	}
	if v.effectiveFrom.IsZero() || v.effectiveTo.IsZero() {
		return fmt.Errorf("plan version effective window is required")
	}
	if v.effectiveTo.Before(v.effectiveFrom) {
		return fmt.Errorf("plan version effective-to must be on or after effective-from")
	}
	if err := v.pattern.Validate(); err != nil {
		return err
	}
	if err := v.calendar.Validate(); err != nil {
		return err
	}
	if err := v.timetable.ValidateAgainst(v.pattern); err != nil {
		return err
	}
	if v.status == PlanVersionPublished && v.publishedAt == nil {
		return fmt.Errorf("published plan version requires published-at")
	}
	return nil
}

func (v PlanVersion) MarkValidated() (PlanVersion, PlanVersionValidatedEvent, error) {
	if v.status != PlanVersionDraft {
		return PlanVersion{}, PlanVersionValidatedEvent{}, fmt.Errorf("only draft plan versions can be validated")
	}
	if err := v.Validate(); err != nil {
		return PlanVersion{}, PlanVersionValidatedEvent{}, err
	}
	next := v
	next.status = PlanVersionValidated
	return next, PlanVersionValidatedEvent{PlanVersionID: v.id}, nil
}

func (v PlanVersion) WithTimetable(timetable Timetable) (PlanVersion, error) {
	if v.status == PlanVersionPublished || v.status == PlanVersionSuperseded || v.status == PlanVersionWithdrawn {
		return PlanVersion{}, fmt.Errorf("%s plan version is immutable", v.status)
	}
	next := v
	next.timetable = timetable
	next.status = PlanVersionDraft
	if err := next.Validate(); err != nil {
		return PlanVersion{}, err
	}
	return next, nil
}

func (v PlanVersion) publish(publishedAt time.Time) (PlanVersion, error) {
	if v.status != PlanVersionValidated {
		return PlanVersion{}, fmt.Errorf("only validated plan versions can be published")
	}
	next := v
	next.status = PlanVersionPublished
	next.publishedAt = copyTimePtr(&publishedAt)
	if err := next.Validate(); err != nil {
		return PlanVersion{}, err
	}
	return next, nil
}

func (v PlanVersion) overlaps(other PlanVersion) bool {
	return !v.effectiveTo.Before(other.effectiveFrom) && !other.effectiveTo.Before(v.effectiveFrom)
}

type ServicePlan struct {
	id          ServicePlanID
	businessKey ServicePlanKey
	versions    []PlanVersion
}

func NewServicePlan(id ServicePlanID, businessKey ServicePlanKey, initialVersion PlanVersion) (ServicePlan, ServicePlanCreatedEvent, error) {
	plan := ServicePlan{
		id:          ServicePlanID(strings.TrimSpace(string(id))),
		businessKey: ServicePlanKey(strings.TrimSpace(string(businessKey))),
		versions:    []PlanVersion{initialVersion},
	}
	if err := plan.Validate(); err != nil {
		return ServicePlan{}, ServicePlanCreatedEvent{}, err
	}
	return plan, ServicePlanCreatedEvent{ServicePlanID: plan.id, BusinessKey: plan.businessKey}, nil
}

func (p ServicePlan) ID() ServicePlanID           { return p.id }
func (p ServicePlan) BusinessKey() ServicePlanKey { return p.businessKey }
func (p ServicePlan) Versions() []PlanVersion     { return copyPlanVersions(p.versions) }

func (p ServicePlan) Validate() error {
	if strings.TrimSpace(string(p.id)) == "" {
		return fmt.Errorf("service plan id is required")
	}
	if strings.TrimSpace(string(p.businessKey)) == "" {
		return fmt.Errorf("service plan business key is required")
	}
	if len(p.versions) == 0 {
		return fmt.Errorf("service plan requires at least one version")
	}
	seen := map[PlanVersionID]struct{}{}
	for _, version := range p.versions {
		if err := version.Validate(); err != nil {
			return err
		}
		if _, exists := seen[version.ID()]; exists {
			return fmt.Errorf("duplicate plan version id: %s", version.ID())
		}
		seen[version.ID()] = struct{}{}
	}
	return nil
}

func (p ServicePlan) ReplaceVersion(version PlanVersion) (ServicePlan, error) {
	next := p
	next.versions = copyPlanVersions(p.versions)
	for i, existing := range next.versions {
		if existing.ID() == version.ID() {
			if existing.Status() == PlanVersionPublished && version.Status() != PlanVersionPublished {
				return ServicePlan{}, fmt.Errorf("published plan versions cannot be replaced in place")
			}
			next.versions[i] = version
			return next, next.Validate()
		}
	}
	next.versions = append(next.versions, version)
	return next, next.Validate()
}

func (p ServicePlan) PublishVersion(versionID PlanVersionID, publishedAt time.Time) (ServicePlan, PlanVersionPublishedEvent, error) {
	next := p
	next.versions = copyPlanVersions(p.versions)
	publishIndex := -1
	for i := range next.versions {
		if next.versions[i].ID() == versionID {
			publishIndex = i
			break
		}
	}
	if publishIndex == -1 {
		return ServicePlan{}, PlanVersionPublishedEvent{}, fmt.Errorf("plan version not found: %s", versionID)
	}
	candidate, err := next.versions[publishIndex].publish(publishedAt)
	if err != nil {
		return ServicePlan{}, PlanVersionPublishedEvent{}, err
	}
	for i, existing := range next.versions {
		if i == publishIndex || existing.Status() != PlanVersionPublished {
			continue
		}
		if candidate.overlaps(existing) {
			return ServicePlan{}, PlanVersionPublishedEvent{}, fmt.Errorf("published plan version %s overlaps %s", candidate.ID(), existing.ID())
		}
	}
	next.versions[publishIndex] = candidate
	if err := next.Validate(); err != nil {
		return ServicePlan{}, PlanVersionPublishedEvent{}, err
	}
	return next, PlanVersionPublishedEvent{ServicePlanID: p.id, PlanVersionID: candidate.ID(), EffectiveFrom: candidate.EffectiveFrom(), EffectiveTo: candidate.EffectiveTo()}, nil
}

// ScheduledService is a planned service instance materialized from a published
// PlanVersion and a service date. It is suitable for Trip Planning and Capacity
// seeding, but does not own seats, fares, orders, or real-time disruption state.
type ScheduledService struct {
	id            ScheduledServiceID
	key           ScheduledServiceKey
	planVersionID PlanVersionID
	serviceDate   time.Time
	status        ScheduledServiceStatus
	segments      []ServiceSegment
}

func NewScheduledService(id ScheduledServiceID, key ScheduledServiceKey, version PlanVersion, serviceDate time.Time) (ScheduledService, ScheduledServiceMaterializedEvent, error) {
	if version.Status() != PlanVersionPublished {
		return ScheduledService{}, ScheduledServiceMaterializedEvent{}, fmt.Errorf("scheduled service can only be materialized from a published plan version")
	}
	date := normalizeDate(serviceDate)
	if !version.Calendar().RunsOn(date) {
		return ScheduledService{}, ScheduledServiceMaterializedEvent{}, fmt.Errorf("calendar does not operate on %s", dateKey(date))
	}
	segments, err := DeriveServiceSegments(version.Pattern())
	if err != nil {
		return ScheduledService{}, ScheduledServiceMaterializedEvent{}, err
	}
	service := ScheduledService{
		id:            ScheduledServiceID(strings.TrimSpace(string(id))),
		key:           ScheduledServiceKey(strings.TrimSpace(string(key))),
		planVersionID: version.ID(),
		serviceDate:   date,
		status:        ScheduledServicePlanned,
		segments:      segments,
	}
	if err := service.Validate(); err != nil {
		return ScheduledService{}, ScheduledServiceMaterializedEvent{}, err
	}
	return service, ScheduledServiceMaterializedEvent{ScheduledServiceID: service.id, PlanVersionID: service.planVersionID, ServiceDate: service.serviceDate}, nil
}

func (s ScheduledService) ID() ScheduledServiceID         { return s.id }
func (s ScheduledService) Key() ScheduledServiceKey       { return s.key }
func (s ScheduledService) PlanVersionID() PlanVersionID   { return s.planVersionID }
func (s ScheduledService) ServiceDate() time.Time         { return s.serviceDate }
func (s ScheduledService) Status() ScheduledServiceStatus { return s.status }
func (s ScheduledService) Segments() []ServiceSegment     { return copySegments(s.segments) }

func (s ScheduledService) Validate() error {
	if strings.TrimSpace(string(s.id)) == "" {
		return fmt.Errorf("scheduled service id is required")
	}
	if strings.TrimSpace(string(s.key)) == "" {
		return fmt.Errorf("scheduled service key is required")
	}
	if strings.TrimSpace(string(s.planVersionID)) == "" {
		return fmt.Errorf("scheduled service must retain source plan version")
	}
	if s.serviceDate.IsZero() {
		return fmt.Errorf("scheduled service date is required")
	}
	if !validScheduledServiceStatus(s.status) {
		return fmt.Errorf("unsupported scheduled service status: %q", s.status)
	}
	if len(s.segments) == 0 {
		return fmt.Errorf("scheduled service requires derived service segments")
	}
	return nil
}

type ServicePlanCreatedEvent struct {
	ServicePlanID ServicePlanID
	BusinessKey   ServicePlanKey
}

type PlanVersionValidatedEvent struct {
	PlanVersionID PlanVersionID
}

type PlanVersionPublishedEvent struct {
	ServicePlanID ServicePlanID
	PlanVersionID PlanVersionID
	EffectiveFrom time.Time
	EffectiveTo   time.Time
}

type ScheduledServiceMaterializedEvent struct {
	ScheduledServiceID ScheduledServiceID
	PlanVersionID      PlanVersionID
	ServiceDate        time.Time
}

func validServiceMode(value ServiceMode) bool {
	switch value {
	case ServiceModeTrain, ServiceModeBus, ServiceModeAir, ServiceModeFerry, ServiceModeShuttle:
		return true
	default:
		return false
	}
}

func validPlanVersionStatus(value PlanVersionStatus) bool {
	switch value {
	case PlanVersionDraft, PlanVersionValidated, PlanVersionPublished, PlanVersionSuperseded, PlanVersionWithdrawn:
		return true
	default:
		return false
	}
}

func validScheduledServiceStatus(value ScheduledServiceStatus) bool {
	switch value {
	case ScheduledServicePlanned, ScheduledServiceActive, ScheduledServiceSuspended, ScheduledServiceReplaced, ScheduledServiceRetired, ScheduledServiceExpired:
		return true
	default:
		return false
	}
}

func validateCalendarException(exception CalendarException) error {
	if exception.Date.IsZero() {
		return fmt.Errorf("calendar exception date is required")
	}
	if exception.Kind != CalendarExceptionAddedService && exception.Kind != CalendarExceptionSuspendedService {
		return fmt.Errorf("unsupported calendar exception kind: %q", exception.Kind)
	}
	if strings.TrimSpace(exception.ReasonCode) == "" {
		return fmt.Errorf("calendar exception reason code is required")
	}
	return nil
}

func normalizeDate(value time.Time) time.Time {
	if value.IsZero() {
		return time.Time{}
	}
	year, month, day := value.Date()
	return time.Date(year, month, day, 0, 0, 0, 0, time.UTC)
}

func dateKey(value time.Time) string {
	return normalizeDate(value).Format("2006-01-02")
}

func copyStops(stops []ServiceStop) []ServiceStop {
	copied := make([]ServiceStop, len(stops))
	copy(copied, stops)
	return copied
}

func copySegments(segments []ServiceSegment) []ServiceSegment {
	copied := make([]ServiceSegment, len(segments))
	copy(copied, segments)
	return copied
}

func copyStopTimes(stopTimes []StopTime) []StopTime {
	copied := make([]StopTime, len(stopTimes))
	for i, stopTime := range stopTimes {
		copied[i] = StopTime{Sequence: stopTime.Sequence, Arrival: copyPlannedTimePtr(stopTime.Arrival), Departure: copyPlannedTimePtr(stopTime.Departure)}
	}
	return copied
}

func copyPlanVersions(versions []PlanVersion) []PlanVersion {
	copied := make([]PlanVersion, len(versions))
	copy(copied, versions)
	return copied
}

func copyPlannedTimePtr(value *PlannedTime) *PlannedTime {
	if value == nil {
		return nil
	}
	copied := *value
	return &copied
}

func copyTimePtr(value *time.Time) *time.Time {
	if value == nil {
		return nil
	}
	copied := *value
	return &copied
}
