package main

import (
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

// Stats collects per-service HTTP latencies, journey outcomes, and error counts.
type Stats struct {
	started time.Time

	mu       sync.Mutex
	journeys map[string]int64
	staff    map[string]int64
	http     map[string]int64
	errors   map[string]int64
	latency  map[string]*latencyBuf

	ScalperAttempts    atomic.Int64
	ScalperSuccess     atomic.Int64
	ScalperBlocked     atomic.Int64
	ScalperExhausted   atomic.Int64
	ScalperIPRotations atomic.Int64
}

type latencyBuf struct {
	samples []float64
}

func NewStats() *Stats {
	return &Stats{
		started:  time.Now(),
		journeys: make(map[string]int64),
		staff:    make(map[string]int64),
		http:     make(map[string]int64),
		errors:   make(map[string]int64),
		latency:  make(map[string]*latencyBuf),
	}
}

func (s *Stats) RecordHTTP(service string, status int, ms float64) {
	key := fmt.Sprintf("%s:%d", service, status)
	s.mu.Lock()
	s.http[key]++
	buf, ok := s.latency[service]
	if !ok {
		buf = &latencyBuf{samples: make([]float64, 0, 5000)}
		s.latency[service] = buf
	}
	buf.samples = append(buf.samples, ms)
	if len(buf.samples) > 5000 {
		buf.samples = buf.samples[len(buf.samples)-5000:]
	}
	s.mu.Unlock()
}

func (s *Stats) RecordJourney(key string) {
	s.mu.Lock()
	s.journeys[key]++
	s.mu.Unlock()
}

func (s *Stats) RecordStaff(key string) {
	s.mu.Lock()
	s.staff[key]++
	s.mu.Unlock()
}

func (s *Stats) RecordError(key string) {
	s.mu.Lock()
	s.errors[key]++
	s.mu.Unlock()
}

type Snapshot struct {
	UptimeS      float64            `json:"uptime_s"`
	Journeys     map[string]int64   `json:"journeys"`
	StaffActions map[string]int64   `json:"staff_actions"`
	HTTP         map[string]int64   `json:"http"`
	Errors       map[string]int64   `json:"errors"`
	LatencyMs    map[string]LatPerc `json:"latency_ms"`
	Scalper      ScalperSnap        `json:"scalper"`
}

type LatPerc struct {
	N   int     `json:"n"`
	P50 float64 `json:"p50"`
	P95 float64 `json:"p95"`
	P99 float64 `json:"p99"`
	Max float64 `json:"max"`
}

type ScalperSnap struct {
	Attempts    int64 `json:"attempts"`
	Success     int64 `json:"success"`
	Blocked     int64 `json:"blocked"`
	Exhausted   int64 `json:"exhausted"`
	IPRotations int64 `json:"ip_rotations"`
}

func (s *Stats) Snapshot() Snapshot {
	s.mu.Lock()
	defer s.mu.Unlock()

	snap := Snapshot{
		UptimeS:      math.Round(time.Since(s.started).Seconds()*10) / 10,
		Journeys:     copyMap(s.journeys),
		StaffActions: copyMap(s.staff),
		HTTP:         copyMap(s.http),
		Errors:       copyMap(s.errors),
		LatencyMs:    make(map[string]LatPerc),
		Scalper: ScalperSnap{
			Attempts:    s.ScalperAttempts.Load(),
			Success:     s.ScalperSuccess.Load(),
			Blocked:     s.ScalperBlocked.Load(),
			Exhausted:   s.ScalperExhausted.Load(),
			IPRotations: s.ScalperIPRotations.Load(),
		},
	}

	for svc, buf := range s.latency {
		if len(buf.samples) == 0 {
			continue
		}
		sorted := make([]float64, len(buf.samples))
		copy(sorted, buf.samples)
		sort.Float64s(sorted)
		n := len(sorted)
		snap.LatencyMs[svc] = LatPerc{
			N:   n,
			P50: math.Round(sorted[n/2]*10) / 10,
			P95: math.Round(sorted[max(0, int(float64(n)*0.95)-1)]*10) / 10,
			P99: math.Round(sorted[max(0, int(float64(n)*0.99)-1)]*10) / 10,
			Max: math.Round(sorted[n-1]*10) / 10,
		}
	}
	return snap
}

func (s *Stats) JSON() string {
	snap := s.Snapshot()
	b, _ := json.Marshal(snap)
	return string(b)
}

func copyMap(m map[string]int64) map[string]int64 {
	out := make(map[string]int64, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}
