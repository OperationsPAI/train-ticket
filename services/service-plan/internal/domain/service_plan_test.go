package domain

import (
	"strings"
	"testing"
	"time"
)

func TestServicePatternRequiresMinimumOrderedStops(t *testing.T) {
	stopA := mustStop(t, 1, "node-a")
	if _, err := NewServicePattern("pattern-1", ServiceModeTrain, "carrier-1", []ServiceStop{stopA}); err == nil {
		t.Fatalf("expected minimum stop validation error")
	}

	stopB := mustStop(t, 2, "node-b")
	pattern, err := NewServicePattern("pattern-1", ServiceModeTrain, "carrier-1", []ServiceStop{stopA, stopB})
	if err != nil {
		t.Fatalf("expected valid pattern: %v", err)
	}
	segments, err := DeriveServiceSegments(pattern)
	if err != nil {
		t.Fatalf("expected segments: %v", err)
	}
	if len(segments) != 1 || segments[0].FromSequence != 1 || segments[0].ToSequence != 2 {
		t.Fatalf("unexpected derived segments: %#v", segments)
	}

	outOfOrder := []ServiceStop{stopB, stopA}
	if _, err := NewServicePattern("pattern-2", ServiceModeTrain, "carrier-1", outOfOrder); err == nil || !strings.Contains(err.Error(), "strictly increasing") {
		t.Fatalf("expected ordered stop sequence validation error, got %v", err)
	}
}

func TestCalendarDetectsExceptionConflictsAndRunSemantics(t *testing.T) {
	calendar, err := NewCalendar("cal-1", date(2026, 1, 1), date(2026, 1, 31), []time.Weekday{time.Monday})
	if err != nil {
		t.Fatalf("expected valid calendar: %v", err)
	}
	monday := date(2026, 1, 5)
	if !calendar.RunsOn(monday) {
		t.Fatalf("expected calendar to run on configured weekday")
	}
	calendar, err = calendar.AddException(monday, CalendarExceptionSuspendedService, "PLANNED_WORK")
	if err != nil {
		t.Fatalf("expected suspension exception: %v", err)
	}
	if calendar.RunsOn(monday) {
		t.Fatalf("expected suspension exception to override operating weekday")
	}
	if _, err := calendar.AddException(monday, CalendarExceptionAddedService, "SPECIAL_SERVICE"); err == nil || !strings.Contains(err.Error(), "conflict") {
		t.Fatalf("expected add/suspend conflict, got %v", err)
	}

	saturday := date(2026, 1, 10)
	if calendar.RunsOn(saturday) {
		t.Fatalf("expected calendar not to run before added-service exception")
	}
	calendar, err = calendar.AddException(saturday, CalendarExceptionAddedService, "HOLIDAY_EXTRA")
	if err != nil {
		t.Fatalf("expected added-service exception: %v", err)
	}
	if !calendar.RunsOn(saturday) {
		t.Fatalf("expected added-service exception to create an operating day")
	}
}

func TestTimetableRequiresOrderingAndExplicitCrossDayOffset(t *testing.T) {
	if _, err := NewTimetable("tt-bad", "Asia/Shanghai", []StopTime{
		mustStopTime(t, 1, nil, plannedPtr(t, 0, 23*time.Hour+50*time.Minute)),
		mustStopTime(t, 2, plannedPtr(t, 0, 30*time.Minute), nil),
	}); err == nil || !strings.Contains(err.Error(), "day offsets") {
		t.Fatalf("expected cross-day offset validation error, got %v", err)
	}

	timetable, err := NewTimetable("tt-good", "Asia/Shanghai", []StopTime{
		mustStopTime(t, 1, nil, plannedPtr(t, 0, 23*time.Hour+50*time.Minute)),
		mustStopTime(t, 2, plannedPtr(t, 1, 30*time.Minute), nil),
	})
	if err != nil {
		t.Fatalf("expected valid cross-day timetable: %v", err)
	}
	if timetable.StopTimes()[1].Arrival.DayOffset != 1 {
		t.Fatalf("expected explicit day offset to be retained")
	}
}

func TestPlanVersionPublishMaterializeAndImmutability(t *testing.T) {
	version := mustDraftVersion(t, "v1", date(2026, 1, 1), date(2026, 1, 31))
	validated, validationEvent, err := version.MarkValidated()
	if err != nil {
		t.Fatalf("expected validation: %v", err)
	}
	if validationEvent.PlanVersionID != "v1" || validated.Status() != PlanVersionValidated {
		t.Fatalf("unexpected validation result: %#v %#v", validated.Status(), validationEvent)
	}
	plan, created, err := NewServicePlan("plan-1", "TRAIN-1234", validated)
	if err != nil {
		t.Fatalf("expected service plan: %v", err)
	}
	if created.ServicePlanID != "plan-1" {
		t.Fatalf("unexpected creation event: %#v", created)
	}
	publishedPlan, publishedEvent, err := plan.PublishVersion("v1", time.Date(2025, 12, 1, 10, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("expected publish: %v", err)
	}
	if publishedEvent.PlanVersionID != "v1" || publishedPlan.Versions()[0].Status() != PlanVersionPublished {
		t.Fatalf("unexpected publish result: %#v %#v", publishedPlan.Versions()[0].Status(), publishedEvent)
	}

	publishedVersion := publishedPlan.Versions()[0]
	_, materializedEvent, err := NewScheduledService("svc-1", "TRAIN-1234-20260105", publishedVersion, date(2026, 1, 5))
	if err != nil {
		t.Fatalf("expected scheduled service from published operating date: %v", err)
	}
	if materializedEvent.PlanVersionID != "v1" {
		t.Fatalf("expected source plan version in event: %#v", materializedEvent)
	}
	if _, _, err := NewScheduledService("svc-2", "TRAIN-1234-20260106", publishedVersion, date(2026, 1, 6)); err == nil {
		t.Fatalf("expected non-operating date materialization to fail")
	}

	newTimetable := mustTimetable(t, "tt-new")
	if _, err := publishedVersion.WithTimetable(newTimetable); err == nil || !strings.Contains(err.Error(), "immutable") {
		t.Fatalf("expected published version immutability error, got %v", err)
	}
}

func TestServicePlanRejectsOverlappingPublishedVersions(t *testing.T) {
	v1 := mustValidatedVersion(t, "v1", date(2026, 1, 1), date(2026, 1, 31))
	plan, _, err := NewServicePlan("plan-1", "TRAIN-1234", v1)
	if err != nil {
		t.Fatalf("expected service plan: %v", err)
	}
	plan, _, err = plan.PublishVersion("v1", time.Date(2025, 12, 1, 10, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("expected initial publish: %v", err)
	}
	v2 := mustValidatedVersion(t, "v2", date(2026, 1, 15), date(2026, 2, 15))
	plan, err = plan.ReplaceVersion(v2)
	if err != nil {
		t.Fatalf("expected version add: %v", err)
	}
	if _, _, err := plan.PublishVersion("v2", time.Date(2025, 12, 2, 10, 0, 0, 0, time.UTC)); err == nil || !strings.Contains(err.Error(), "overlaps") {
		t.Fatalf("expected published version overlap rejection, got %v", err)
	}
}

func mustValidatedVersion(t *testing.T, id PlanVersionID, effectiveFrom, effectiveTo time.Time) PlanVersion {
	t.Helper()
	version := mustDraftVersion(t, id, effectiveFrom, effectiveTo)
	validated, _, err := version.MarkValidated()
	if err != nil {
		t.Fatalf("expected validated version: %v", err)
	}
	return validated
}

func mustDraftVersion(t *testing.T, id PlanVersionID, effectiveFrom, effectiveTo time.Time) PlanVersion {
	t.Helper()
	pattern := mustPattern(t)
	calendar, err := NewCalendar(CalendarID("cal-"+string(id)), effectiveFrom, effectiveTo, []time.Weekday{time.Monday})
	if err != nil {
		t.Fatalf("expected calendar: %v", err)
	}
	version, err := NewDraftPlanVersion(id, effectiveFrom, effectiveTo, pattern, calendar, mustTimetable(t, TimetableID("tt-"+string(id))))
	if err != nil {
		t.Fatalf("expected draft version: %v", err)
	}
	return version
}

func mustPattern(t *testing.T) ServicePattern {
	t.Helper()
	pattern, err := NewServicePattern("pattern-1", ServiceModeTrain, "carrier-1", []ServiceStop{
		mustStop(t, 1, "node-a"),
		mustStop(t, 2, "node-b"),
		mustStop(t, 3, "node-c"),
	})
	if err != nil {
		t.Fatalf("expected pattern: %v", err)
	}
	return pattern
}

func mustTimetable(t *testing.T, id TimetableID) Timetable {
	t.Helper()
	timetable, err := NewTimetable(id, "Asia/Shanghai", []StopTime{
		mustStopTime(t, 1, nil, plannedPtr(t, 0, 8*time.Hour)),
		mustStopTime(t, 2, plannedPtr(t, 0, 9*time.Hour), plannedPtr(t, 0, 9*time.Hour+5*time.Minute)),
		mustStopTime(t, 3, plannedPtr(t, 0, 10*time.Hour), nil),
	})
	if err != nil {
		t.Fatalf("expected timetable: %v", err)
	}
	return timetable
}

func mustStop(t *testing.T, sequence int, nodeID TransportNodeID) ServiceStop {
	t.Helper()
	stop, err := NewServiceStop(sequence, nodeID, "nodes-v1", true, true)
	if err != nil {
		t.Fatalf("expected stop: %v", err)
	}
	return stop
}

func mustStopTime(t *testing.T, sequence int, arrival, departure *PlannedTime) StopTime {
	t.Helper()
	stopTime, err := NewStopTime(sequence, arrival, departure)
	if err != nil {
		t.Fatalf("expected stop time: %v", err)
	}
	return stopTime
}

func plannedPtr(t *testing.T, dayOffset int, minuteOfDay time.Duration) *PlannedTime {
	t.Helper()
	planned, err := NewPlannedTime(dayOffset, minuteOfDay)
	if err != nil {
		t.Fatalf("expected planned time: %v", err)
	}
	return &planned
}

func date(year int, month time.Month, day int) time.Time {
	return time.Date(year, month, day, 0, 0, 0, 0, time.UTC)
}
