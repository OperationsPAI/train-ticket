package main

import (
	"math"
	"math/rand"
	"time"
)

// Arrival shaping for open-loop mode.
//
// WHY THIS EXISTS. The previous open-loop scheduler paced itself with
// time.NewTicker(1s/target_rps), which emits at exactly fixed intervals. Against
// the deployed stack that produced a request rate flat to within +-2% per minute
// and a per-second coefficient of variation of 0.13 -- measured over 8 hours in
// ClickHouse. Real traffic does not look like that, and the difference is not
// cosmetic:
//
//   - A constant arrival rate never queues. Queues form from VARIANCE, not from
//     mean load (Kingman's formula: waiting time scales with the sum of the
//     squared CVs of arrival and service). A flat generator at 80% utilization
//     produces almost no queueing, so it cannot exercise backpressure, retry
//     storms, connection-pool exhaustion or consumer lag -- the failures that
//     actually happen.
//   - Autoscaling, rate limiters, circuit breakers and cache warmth are all
//     RESPONSES TO CHANGE. A flat signal leaves them untested by construction.
//   - Capacity planning read off a flat run understates what peak needs, because
//     peak is not the mean.
//
// Three independent shapes compose multiplicatively onto a base rate, each
// switchable on its own so a run can isolate one:
//
//	rate(t) = base_rps * diurnal(t) * burst(t)      -- the intensity
//	interval ~ Exponential(rate(t))                 -- the arrival process
//
// The distinction between the two lines matters. The first is the slow-moving
// intensity (where the "peak hour" lives); the second is the randomness WITHIN
// any instant. Shaping only the first still yields a locally-smooth generator;
// only the exponential draw gives the bursty micro-structure of independent
// users arriving.
type ArrivalProcess struct {
	cfg   ArrivalConfig
	base  float64
	rng   *rand.Rand
	start time.Time

	// Current burst, if any. Bursts are sampled as a Poisson process in their
	// own right, so at most one is active at a time -- overlapping spikes would
	// compound into a rate the cluster cannot absorb, which reads as a broken
	// generator rather than a busy hour.
	burstUntil time.Time
	burstMult  float64
	nextBurst  time.Time
}

func NewArrivalProcess(cfg ArrivalConfig, baseRPS float64, rng *rand.Rand, now time.Time) *ArrivalProcess {
	a := &ArrivalProcess{cfg: cfg, base: baseRPS, rng: rng, start: now}
	if cfg.Burst.Enabled {
		a.nextBurst = now.Add(a.sampleBurstGap())
	}
	return a
}

// RateAt returns the instantaneous arrival rate in requests per second,
// including the ramp, the diurnal cycle and any active burst.
func (a *ArrivalProcess) RateAt(now time.Time) float64 {
	rate := a.base * a.rampFactor(now) * a.diurnalFactor(now) * a.burstFactor(now)
	// A rate of zero would make the exponential draw below infinite, parking the
	// generator forever with no way back. Floor it well below any useful load but
	// above zero.
	if rate < 0.01 {
		return 0.01
	}
	return rate
}

// NextInterval draws the gap to the next arrival from an exponential
// distribution, which is what makes arrivals a Poisson process rather than a
// metronome.
//
// This is a piecewise-constant-rate approximation of a non-homogeneous Poisson
// process: the rate is re-evaluated at each arrival and held for that one gap.
// Exact simulation would need thinning, which is not worth it here -- the
// diurnal period is minutes-to-hours while gaps are milliseconds, so the rate is
// effectively constant across any single interval.
func (a *ArrivalProcess) NextInterval(now time.Time) time.Duration {
	rate := a.RateAt(now)
	// rng.ExpFloat64() has mean 1, so dividing by the rate gives mean 1/rate --
	// exactly the exponential inter-arrival time for a Poisson process of that
	// intensity.
	gap := a.rng.ExpFloat64() / rate
	// Cap the tail. An exponential draw is unbounded, and a single unlucky draw
	// at a low rate can sleep for minutes, which looks like the generator hung.
	if gap > 5 {
		gap = 5
	}
	return time.Duration(gap * float64(time.Second))
}

// rampFactor scales linearly from 0 to 1 over the ramp window, so a fresh pod
// does not slam a cold cluster (empty caches, unwarmed pools, unscaled
// deployments) with full load on its first tick.
func (a *ArrivalProcess) rampFactor(now time.Time) float64 {
	if a.cfg.RampSeconds <= 0 {
		return 1
	}
	elapsed := now.Sub(a.start).Seconds()
	if elapsed >= a.cfg.RampSeconds {
		return 1
	}
	if elapsed < 0 {
		return 0
	}
	return elapsed / a.cfg.RampSeconds
}

// diurnalFactor is the slow day/night cycle, a raised cosine over
// diurnal.period_seconds returning trough..peak.
//
// The period is configuration rather than a real 24h clock on purpose: a
// development cluster is observed for tens of minutes, and a true 24-hour cycle
// would look flat across any window anyone actually watches. Compressing the
// same shape into ~30 minutes makes the peak and trough both visible in one
// sitting, which is the point of having the cycle at all.
func (a *ArrivalProcess) diurnalFactor(now time.Time) float64 {
	d := a.cfg.Diurnal
	if !d.Enabled || d.PeriodSeconds <= 0 {
		return 1
	}
	peak, trough := d.PeakMultiplier, d.TroughMultiplier
	if peak <= 0 {
		peak = 1
	}
	if trough <= 0 {
		trough = 1
	}
	phase := math.Mod(now.Sub(a.start).Seconds()/d.PeriodSeconds+d.PhaseOffset, 1.0)
	if phase < 0 {
		phase++
	}
	// 0 at phase 0 (trough), 1 at phase 0.5 (peak).
	wave := (1 - math.Cos(2*math.Pi*phase)) / 2
	return trough + (peak-trough)*wave
}

// burstFactor returns the multiplier of any burst active at `now`, advancing the
// burst schedule as time passes.
//
// Bursts model the spikes that a smooth curve cannot: a ticket release, a push
// notification, a scalper script waking up. They are the shape most likely to
// find a real defect, because they are the one that makes the queue depth move
// faster than any autoscaler can react to.
func (a *ArrivalProcess) burstFactor(now time.Time) float64 {
	b := a.cfg.Burst
	if !b.Enabled {
		return 1
	}
	if now.Before(a.burstUntil) {
		return a.burstMult
	}
	// Not in a burst: start one if the schedule says so, and schedule the next.
	if !a.nextBurst.IsZero() && !now.Before(a.nextBurst) {
		dur := b.DurationSeconds.Min + a.rng.Float64()*(b.DurationSeconds.Max-b.DurationSeconds.Min)
		if dur <= 0 {
			dur = 30
		}
		a.burstUntil = now.Add(time.Duration(dur * float64(time.Second)))
		a.burstMult = b.Multiplier.Min + a.rng.Float64()*(b.Multiplier.Max-b.Multiplier.Min)
		if a.burstMult < 1 {
			a.burstMult = 1
		}
		a.nextBurst = a.burstUntil.Add(a.sampleBurstGap())
		return a.burstMult
	}
	return 1
}

// sampleBurstGap draws the wait until the next burst. Exponential, for the same
// reason arrivals are: bursts that arrived on a fixed schedule would be
// predictable, and a system can absorb a predictable spike in ways it cannot
// absorb a surprising one.
func (a *ArrivalProcess) sampleBurstGap() time.Duration {
	mean := a.cfg.Burst.MeanGapSeconds
	if mean <= 0 {
		mean = 600
	}
	return time.Duration(a.rng.ExpFloat64() * mean * float64(time.Second))
}
