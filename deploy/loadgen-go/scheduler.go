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

// RunOpenLoop injects requests at a fixed rate regardless of response time.
func (s *Scheduler) RunOpenLoop(wg *sync.WaitGroup, journeyRunner func(ctx context.Context, p *Providers)) {
	targetRPS := s.cfg.Run.TargetRPS
	if targetRPS <= 0 {
		targetRPS = 100
	}
	rampDuration := time.Duration(s.cfg.Run.RampDurationSeconds * float64(time.Second))
	if rampDuration <= 0 {
		rampDuration = 30 * time.Second
	}

	startTime := time.Now()
	interval := time.Second / time.Duration(targetRPS)

	wg.Add(1)
	go func() {
		defer wg.Done()
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for {
			select {
			case <-s.stop.Done():
				return
			case <-ticker.C:
				// Ramp: linearly increase rate from 0 to target over ramp duration
				elapsed := time.Since(startTime)
				if elapsed < rampDuration {
					rampFraction := float64(elapsed) / float64(rampDuration)
					if s.rng.Float64() > rampFraction {
						continue
					}
				}

				seed := s.rng.Int63()
				go func(sd int64) {
					rng := rand.New(rand.NewSource(sd))
					prov := NewProviders(s.cfg, s.api, s.reg, s.stats, rng)
					journeyRunner(s.stop, prov)
				}(seed)
			}
		}
	}()
}
