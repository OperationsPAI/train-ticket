package main

import (
	"context"
	"encoding/json"
	"fmt"
	"math/rand"
	"net/url"
	"time"

	"github.com/redis/go-redis/v9"
)

// StaffSim processes staff work queues: reservation, ticketing, risk, support, dispatch.
type StaffSim struct {
	cfg   *Config
	api   *ApiClient
	reg   *Registry
	stats *Stats
	rng   *rand.Rand
	redis *redis.Client
}

func NewStaffSim(cfg *Config, api *ApiClient, reg *Registry, stats *Stats, rng *rand.Rand) *StaffSim {
	opts, err := redis.ParseURL(cfg.Target.RedisURL)
	var rdb *redis.Client
	if err == nil && cfg.Target.RedisURL != "" {
		rdb = redis.NewClient(opts)
	}
	return &StaffSim{
		cfg:   cfg,
		api:   api,
		reg:   reg,
		stats: stats,
		rng:   rng,
		redis: rdb,
	}
}

func (s *StaffSim) think(ctx context.Context) {
	t := s.cfg.Staff.ThinkTime
	d := time.Duration((t.Min + s.rng.Float64()*(t.Max-t.Min)) * float64(time.Second))
	select {
	case <-ctx.Done():
	case <-time.After(d):
	}
}

// Worker runs a staff worker that polls all queues.
func (s *StaffSim) Worker(ctx context.Context, idx int) {
	pollDuration := time.Duration(s.cfg.Staff.QueuePollSeconds * float64(time.Second))
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		var item *WorkItem
		select {
		case item = <-s.reg.QReservation:
		default:
			select {
			case item = <-s.reg.QTicketing:
			default:
				select {
				case item = <-s.reg.QRisk:
				default:
					select {
					case item = <-s.reg.QSupport:
					default:
						select {
						case item = <-s.reg.QDispatch:
						default:
							select {
							case <-ctx.Done():
								return
							case <-time.After(pollDuration):
								continue
							}
						}
					}
				}
			}
		}

		s.think(ctx)
		var err error
		switch item.Kind {
		case "reservation":
			err = s.doReservation(ctx, item)
		case "ticketing":
			err = s.doTicketing(ctx, item)
		case "risk":
			err = s.doRisk(ctx, item)
		case "support":
			err = s.doSupport(ctx, item)
		case "dispatch":
			err = s.doDispatch(ctx, item)
		}

		if err != nil {
			item.SetFailed(fmt.Sprintf("%T: %v", err, err))
			s.stats.RecordStaff(item.Kind + ":failed")
			fmt.Printf("[staff%d] %s failed - %v\n", idx, item.Kind, err)
		} else {
			item.Complete()
			s.stats.RecordStaff(item.Kind)
		}
	}
}

func (s *StaffSim) doReservation(ctx context.Context, item *WorkItem) error {
	var saga string
	attempts := s.cfg.Polling.Attempts
	interval := time.Duration(s.cfg.Polling.IntervalSeconds * float64(time.Second))

	for i := 0; i < attempts; i++ {
		_, data, err := s.api.Request(ctx, "GET", "booking-orchestration",
			"/api/v1/internal/booking-sagas/by-order/"+url.PathEscape(item.Order),
			nil, nil, []int{200}, "staff-saga-lookup")
		if err == nil {
			saga = getString(data, "sagaId")
			if saga == "" {
				if items, ok := data["items"].([]interface{}); ok && len(items) > 0 {
					if first, ok := items[0].(map[string]interface{}); ok {
						saga = getString(first, "sagaId")
					}
				}
			}
		}
		if saga != "" {
			break
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(interval):
		}
	}

	if saga == "" {
		return &StepError{Step: "reservation", Detail: "no BookingSagaStarted for " + item.Order}
	}

	sb := "sb-" + UUID7()
	_, _, err := s.api.Request(ctx, "POST", "booking-orchestration",
		"/api/v1/internal/booking-sagas/"+url.PathEscape(saga)+"/request-reservation",
		map[string]interface{}{
			"segmentRef":       item.Seg,
			"travelerRef":      item.Traveler,
			"segmentBookingId": sb,
		}, nil, []int{200}, "staff-reservation")
	if err != nil {
		return err
	}

	item.SetResult("saga", saga)
	item.SetResult("sb", sb)

	// Quick no-capacity check: poll saga once after a short delay
	select {
	case <-ctx.Done():
		return nil
	case <-time.After(3 * time.Second):
	}
	code, data, _ := s.api.Request(ctx, "GET", "booking-orchestration",
		"/api/v1/internal/booking-sagas/"+url.PathEscape(saga),
		nil, nil, nil, "staff-poll-reservation")
	if code == 200 {
		b, _ := json.Marshal(data)
		if containsStr(string(b), "NO_AVAILABLE_CAPACITY") {
			item.SetResult("no_capacity", true)
		}
	}
	return nil
}

func (s *StaffSim) sagaFailedNoCapacity(ctx context.Context, saga string) bool {
	attempts := s.cfg.Polling.Attempts
	interval := time.Duration(s.cfg.Polling.IntervalSeconds * float64(time.Second))

	for i := 0; i < attempts; i++ {
		code, data, _ := s.api.Request(ctx, "GET", "booking-orchestration",
			"/api/v1/internal/booking-sagas/"+url.PathEscape(saga),
			nil, nil, nil, "staff-poll-reservation")
		if code == 200 {
			b, _ := json.Marshal(data)
			text := string(b)
			if containsStr(text, "NO_AVAILABLE_CAPACITY") {
				return true
			}
			status := getString(data, "status")
			if status == "WAITING_PAYMENT" || status == "HELD" || status == "TICKETING" || status == "COMPLETED" {
				return false
			}
		}
		select {
		case <-ctx.Done():
			return false
		case <-time.After(interval):
		}
	}
	return false
}

func (s *StaffSim) doTicketing(ctx context.Context, item *WorkItem) error {
	body := map[string]interface{}{
		"segmentBookingId": item.SB,
		"journeyOrderId":   item.Order,
		"travelerRef":      item.Traveler,
		"segmentRef":       item.Seg,
		"issuePurpose":     "INITIAL",
	}
	if s.rng.Float64() < s.cfg.Staff.PSeatPreferences {
		body["seatPreferences"] = map[string]interface{}{
			"acceptStanding":      true,
			"adjacencyPreference": "NONE",
			"preferenceVersion":   "loadgen-v1",
		}
	}

	_, data, err := s.api.Request(ctx, "POST", "entitlement-ticketing", "/api/v1/entitlements",
		body, nil, []int{200, 201}, "staff-ticketing")
	if err != nil {
		return err
	}

	item.SetResult("entitlement", getString(data, "entitlementId"))
	return nil
}

func (s *StaffSim) doRisk(ctx context.Context, item *WorkItem) error {
	if s.rng.Float64() < s.cfg.Staff.PRiskApprove {
		_, _, err := s.api.Request(ctx, "POST", "risk-compliance", "/api/v1/risk-blocks/lift",
			map[string]interface{}{
				"subjectRef": item.Order,
				"scope":      "ORDER",
				"reasonCode": "MANUAL_REVIEW_CLEARED",
			}, nil, []int{200, 201, 409}, "staff-risk-lift")
		if err != nil {
			return err
		}
		item.SetResult("risk", "lifted")
	} else {
		item.SetResult("risk", "rejected")
	}
	return nil
}

func (s *StaffSim) doSupport(ctx context.Context, item *WorkItem) error {
	caseID := item.Case
	branches := map[string]float64{
		"assign_resolve":   s.cfg.Staff.PSupportAssignResolveBranch,
		"classify_escalate": s.cfg.Staff.PSupportClassifyEscalateBranch,
		"classify_close":    s.cfg.Staff.PSupportClassifyCloseBranch,
	}
	branch := WeightedChoice(s.rng, branches)

	if branch == "assign_resolve" && s.rng.Float64() < s.cfg.Staff.PSupportAssign {
		_, _, err := s.api.Request(ctx, "POST", "customer-service",
			"/api/v1/support-cases/"+url.PathEscape(caseID)+"/assign",
			map[string]interface{}{"ownerQueue": "tier1"},
			nil, []int{200, 201}, "staff-support-assign")
		if err != nil {
			return err
		}
		item.SetResult("support", "assigned")

		if s.rng.Float64() < s.cfg.Staff.PSupportResolve {
			s.think(ctx)
			_, _, err := s.api.Request(ctx, "POST", "customer-service",
				"/api/v1/support-cases/"+url.PathEscape(caseID)+"/resolve",
				map[string]interface{}{
					"summary":        "Handled by simulated tier1 agent",
					"resolutionCode": "POST_SALES_EXPLAINED",
				}, nil, []int{200, 201}, "staff-support-resolve")
			if err != nil {
				return err
			}
			item.SetResult("support", "resolved")
		}
	} else {
		_, _, err := s.api.Request(ctx, "POST", "customer-service",
			"/api/v1/support-cases/"+url.PathEscape(caseID)+"/classify",
			map[string]interface{}{
				"classification": "POST_SALES_HELP",
				"priority":       "NORMAL",
			}, nil, []int{200, 201}, "staff-support-classify")
		if err != nil {
			return err
		}
		s.think(ctx)

		if branch == "classify_escalate" {
			_, _, _ = s.api.Request(ctx, "POST", "customer-service",
				"/api/v1/support-cases/"+url.PathEscape(caseID)+"/assign",
				map[string]interface{}{"ownerQueue": "tier1"},
				nil, []int{200, 201}, "staff-support-assign-before-escalate")
			_, _, _ = s.api.Request(ctx, "POST", "customer-service",
				"/api/v1/support-cases/"+url.PathEscape(caseID)+"/escalate",
				map[string]interface{}{
					"targetQueue": "tier2",
					"reason":      "loadgen long-tail escalation",
				}, nil, []int{200, 201}, "staff-support-escalate")
			item.SetResult("support", "escalated")
		} else {
			_, _, _ = s.api.Request(ctx, "POST", "customer-service",
				"/api/v1/support-cases/"+url.PathEscape(caseID)+"/close",
				map[string]interface{}{"reason": "NO_FURTHER_ACTION"},
				nil, []int{200, 201}, "staff-support-close")
			item.SetResult("support", "closed")

			if s.rng.Float64() < s.cfg.Staff.PSupportReopenAfterClose {
				s.think(ctx)
				_, _, _ = s.api.Request(ctx, "POST", "customer-service",
					"/api/v1/support-cases/"+url.PathEscape(caseID)+"/reopen",
					map[string]interface{}{
						"reason":       "customer supplied more context",
						"requesterRef": item.Requester,
					}, nil, []int{200, 201}, "staff-support-reopen")
				s.think(ctx)
				_, _, _ = s.api.Request(ctx, "POST", "customer-service",
					"/api/v1/support-cases/"+url.PathEscape(caseID)+"/close",
					map[string]interface{}{"reason": "NO_FURTHER_ACTION"},
					nil, []int{200, 201}, "staff-support-close-again")
				item.SetResult("support", "reopened_closed")
			}
		}
	}
	return nil
}

func (s *StaffSim) doDispatch(ctx context.Context, item *WorkItem) error {
	rideID := item.Ride
	branch := item.Branch

	// Assign driver
	_, _, err := s.api.Request(ctx, "POST", "dispatch",
		"/api/v1/ride-requests/"+url.PathEscape(rideID)+"/assign",
		map[string]interface{}{
			"driverRef":  "drv-" + UUID7(),
			"vehicleRef": "veh-" + UUID7(),
			"etaSeconds": s.rng.Intn(270) + 30,
		}, nil, []int{200}, "staff-dispatch-assign")
	if err != nil {
		return err
	}

	if branch == "driver_cancel_reassign" {
		_, _, _ = s.api.Request(ctx, "POST", "dispatch",
			"/api/v1/ride-requests/"+url.PathEscape(rideID)+"/driver-cancel",
			map[string]interface{}{"reason": "DRIVER_UNAVAILABLE"},
			nil, []int{200}, "staff-dispatch-driver-cancel")
		_, _, _ = s.api.Request(ctx, "POST", "dispatch",
			"/api/v1/ride-requests/"+url.PathEscape(rideID)+"/assign",
			map[string]interface{}{
				"driverRef":  "drv-" + UUID7(),
				"vehicleRef": "veh-" + UUID7(),
				"etaSeconds": s.rng.Intn(270) + 30,
			}, nil, []int{200}, "staff-dispatch-reassign")
	}

	// ETA update
	_, _, _ = s.api.Request(ctx, "POST", "dispatch",
		"/api/v1/ride-requests/"+url.PathEscape(rideID)+"/eta",
		map[string]interface{}{"etaSeconds": s.rng.Intn(110) + 10},
		nil, []int{200}, "staff-dispatch-eta")

	// Driver arrived
	_, _, _ = s.api.Request(ctx, "POST", "dispatch",
		"/api/v1/ride-requests/"+url.PathEscape(rideID)+"/driver-arrived",
		map[string]interface{}{}, nil, []int{200}, "staff-dispatch-arrived")

	if branch == "no_show" {
		_, _, _ = s.api.Request(ctx, "POST", "dispatch",
			"/api/v1/ride-requests/"+url.PathEscape(rideID)+"/no-show",
			map[string]interface{}{"reason": "RIDER_ABSENT"},
			nil, []int{200}, "staff-dispatch-no-show")
		item.SetResult("dispatch", "no_show")
		return nil
	}

	// Start ride
	_, _, _ = s.api.Request(ctx, "POST", "dispatch",
		"/api/v1/ride-requests/"+url.PathEscape(rideID)+"/start",
		map[string]interface{}{}, nil, []int{200}, "staff-dispatch-start")

	// Complete ride
	_, _, _ = s.api.Request(ctx, "POST", "dispatch",
		"/api/v1/ride-requests/"+url.PathEscape(rideID)+"/complete",
		map[string]interface{}{"finalFareRef": "fare-final-" + UUID7()},
		nil, []int{200}, "staff-dispatch-complete")

	if branch == "driver_cancel_reassign" {
		item.SetResult("dispatch", "driver_cancel_reassigned_completed")
	} else {
		item.SetResult("dispatch", "completed")
	}
	return nil
}

func (s *StaffSim) Close() {
	if s.redis != nil {
		s.redis.Close()
	}
}

func containsStr(s, substr string) bool {
	return len(s) >= len(substr) && (s == substr || len(s) > 0 && findSubstr(s, substr))
}

func findSubstr(s, sub string) bool {
	for i := 0; i <= len(s)-len(sub); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
