package main

import (
	"context"
	"math/rand"
	"sync"
	"time"
)

// Scheduler drives the load generation in either closed-loop or open-loop mode.
type Scheduler struct {
	cfg     *Config
	api     *ApiClient
	reg     *Registry
	stats   *Stats
	rng     *rand.Rand
	stop    context.Context
	cancel  context.CancelFunc
}

func NewScheduler(cfg *Config, api *ApiClient, reg *Registry, stats *Stats, rng *rand.Rand) *Scheduler {
	ctx, cancel := context.WithCancel(context.Background())
	return &Scheduler{
		cfg:    cfg,
		api:    api,
		reg:    reg,
		stats:  stats,
		rng:    rng,
		stop:   ctx,
		cancel: cancel,
	}
}

func (s *Scheduler) Stop() {
	s.cancel()
}

// RunClosedLoop launches N worker goroutines that cycle through journeys with think time.
func (s *Scheduler) RunClosedLoop(wg *sync.WaitGroup, journeyRunner func(ctx context.Context, p *Providers)) {
	for i := 0; i < s.cfg.Run.Workers; i++ {
		wg.Add(1)
		workerSeed := s.rng.Int63()
		go func(idx int, seed int64) {
			defer wg.Done()
			rng := rand.New(rand.NewSource(seed))
			prov := NewProviders(s.cfg, s.api, s.reg, s.stats, rng)
			for {
				select {
				case <-s.stop.Done():
					return
				default:
				}
				journeyRunner(s.stop, prov)
				pause := s.cfg.Run.SessionPause
				d := time.Duration((pause.Min + rng.Float64()*(pause.Max-pause.Min)) * float64(time.Second))
				select {
				case <-s.stop.Done():
					return
				case <-time.After(d):
				}
			}
		}(i, workerSeed)
	}
}

// RunOpenLoop injects requests at a rate independent of response time, shaped by
// the arrival process in arrival.go: exponential inter-arrival gaps (Poisson),
// an optional diurnal cycle, and optional random bursts.
//
// Open-loop is what makes those shapes meaningful. A closed-loop pool cannot
// have an arrival "shape" at all -- its rate is a consequence of how fast the
// cluster answers, so the generator slows down exactly when the system is
// struggling, which is the opposite of what real users do.
//
// One goroutine per arrival, deliberately: a journey must not delay the next
// arrival, or the offered load would silently become closed-loop again under
// latency. The cost is unbounded concurrency if the cluster stalls, which is the
// honest behaviour here -- it shows up as growing in-flight count rather than as
// a quietly reduced request rate.
func (s *Scheduler) RunOpenLoop(wg *sync.WaitGroup, journeyRunner func(ctx context.Context, p *Providers)) {
	targetRPS := s.cfg.Run.TargetRPS
	if targetRPS <= 0 {
		targetRPS = 100
	}

	arrivalCfg := s.cfg.Run.Arrival
	// ramp_duration_seconds predates arrival.ramp_seconds and still works; the
	// nested key wins when both are set.
	if arrivalCfg.RampSeconds <= 0 {
		arrivalCfg.RampSeconds = s.cfg.Run.RampDurationSeconds
	}
	if arrivalCfg.RampSeconds <= 0 {
		arrivalCfg.RampSeconds = 30
	}

	usePoisson := arrivalCfg.PoissonEnabled()
	arrival := NewArrivalProcess(arrivalCfg, targetRPS, s.rng, time.Now())

	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			var wait time.Duration
			now := time.Now()
			if usePoisson {
				wait = arrival.NextInterval(now)
			} else {
				// Fixed-interval fallback. Still honours the rate envelope, so
				// the diurnal cycle and bursts work -- only the micro-structure
				// is a metronome.
				wait = time.Duration(float64(time.Second) / arrival.RateAt(now))
			}

			select {
			case <-s.stop.Done():
				return
			case <-time.After(wait):
			}

			seed := s.rng.Int63()
			go func(sd int64) {
				rng := rand.New(rand.NewSource(sd))
				prov := NewProviders(s.cfg, s.api, s.reg, s.stats, rng)
				journeyRunner(s.stop, prov)
			}(seed)
		}
	}()
}
