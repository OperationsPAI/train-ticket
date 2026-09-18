package main

import (
	"math"
	"math/rand"
	"testing"
	"time"
)

// The arrival process exists to fix a measured problem: the fixed-interval
// scheduler produced a per-second coefficient of variation of 0.13 against the
// deployed stack, where independent arrivals give ~1.0. These tests assert the
// statistical properties that distinction rests on -- not that the functions
// return without panicking.

func testArrivalCfg() ArrivalConfig {
	return ArrivalConfig{
		Diurnal: DiurnalConfig{PeriodSeconds: 1800, PeakMultiplier: 4, TroughMultiplier: 0.5},
		Burst:   BurstConfig{MeanGapSeconds: 600, DurationSeconds: RangeSeconds{Min: 20, Max: 60}, Multiplier: RangeSeconds{Min: 3, Max: 8}},
	}
}

// TestPoissonInterArrivalIsExponential is the core guarantee. An exponential
// distribution has mean == standard deviation, so CV == 1; a metronome has
// CV == 0. Asserting CV near 1 is what distinguishes a real Poisson process from
// the fixed-interval ticker this replaced -- and it is the property that makes
// queues form, per Kingman's formula.
func TestPoissonInterArrivalIsExponential(t *testing.T) {
	cfg := ArrivalConfig{} // no diurnal, no burst, no ramp: isolate the draw
	a := NewArrivalProcess(cfg, 100, rand.New(rand.NewSource(1)), time.Now())

	const n = 200000
	var sum, sumSq float64
	now := time.Now()
	for i := 0; i < n; i++ {
		g := a.NextInterval(now).Seconds()
		sum += g
		sumSq += g * g
	}
	mean := sum / n
	variance := sumSq/n - mean*mean
	cv := math.Sqrt(variance) / mean

	// Mean gap must track 1/rate.
	if want := 0.01; math.Abs(mean-want)/want > 0.05 {
		t.Errorf("mean inter-arrival = %.5fs, want ~%.5fs (rate 100/s)", mean, want)
	}
	// The headline property.
	if cv < 0.9 || cv > 1.1 {
		t.Errorf("inter-arrival CV = %.3f, want ~1.0 (exponential); "+
			"a value near 0 means arrivals are a metronome again", cv)
	}
}

// TestDiurnalSpansPeakAndTrough checks the slow cycle actually reaches the
// configured extremes. A cycle that only ever returns values near 1 would look
// like it works while leaving the rate flat, which is the failure this whole
// change is about.
func TestDiurnalSpansPeakAndTrough(t *testing.T) {
	cfg := testArrivalCfg()
	cfg.Diurnal.Enabled = true
	start := time.Now()
	a := NewArrivalProcess(cfg, 100, rand.New(rand.NewSource(2)), start)

	minRate, maxRate := math.Inf(1), 0.0
	period := time.Duration(cfg.Diurnal.PeriodSeconds) * time.Second
	for i := 0; i <= 360; i++ {
		r := a.RateAt(start.Add(time.Duration(i) * period / 360))
		minRate = math.Min(minRate, r)
		maxRate = math.Max(maxRate, r)
	}

	// trough_multiplier 0.5 and peak_multiplier 4 on a base of 100.
	if math.Abs(minRate-50) > 1 {
		t.Errorf("diurnal trough rate = %.1f, want ~50 (0.5 x 100)", minRate)
	}
	if math.Abs(maxRate-400) > 1 {
		t.Errorf("diurnal peak rate = %.1f, want ~400 (4 x 100)", maxRate)
	}
	if maxRate/minRate < 3 {
		t.Errorf("peak/trough ratio = %.1f, want >= 3; the cycle is too flat to exercise scaling", maxRate/minRate)
	}
}

// TestDiurnalStartsAtTrough pins the phase convention. Without this, a change to
// the cosine could silently start every run at peak, which would mean a fresh
// pod ramps into the busiest moment -- the opposite of the ramp's purpose.
func TestDiurnalStartsAtTrough(t *testing.T) {
	cfg := testArrivalCfg()
	cfg.Diurnal.Enabled = true
	start := time.Now()
	a := NewArrivalProcess(cfg, 100, rand.New(rand.NewSource(3)), start)

	if r := a.RateAt(start); math.Abs(r-50) > 1 {
		t.Errorf("rate at phase 0 = %.1f, want ~50 (trough)", r)
	}
	half := time.Duration(cfg.Diurnal.PeriodSeconds/2) * time.Second
	if r := a.RateAt(start.Add(half)); math.Abs(r-400) > 1 {
		t.Errorf("rate at half period = %.1f, want ~400 (peak)", r)
	}
}

// TestBurstRaisesRateThenReleases verifies a burst both fires and ENDS. A burst
// that never cleared would be indistinguishable from a permanently higher base
// rate, quietly turning every run into a peak-load run.
func TestBurstRaisesRateThenReleases(t *testing.T) {
	cfg := ArrivalConfig{
		Burst: BurstConfig{
			Enabled:         true,
			MeanGapSeconds:  60,
			DurationSeconds: RangeSeconds{Min: 10, Max: 10},
			Multiplier:      RangeSeconds{Min: 5, Max: 5},
		},
	}
	start := time.Now()
	a := NewArrivalProcess(cfg, 100, rand.New(rand.NewSource(4)), start)

	// Walk forward a second at a time until a burst fires.
	var burstAt time.Time
	for i := 0; i < 3000; i++ {
		now := start.Add(time.Duration(i) * time.Second)
		if a.RateAt(now) > 400 {
			burstAt = now
			break
		}
	}
	if burstAt.IsZero() {
		t.Fatal("no burst fired within 3000s at mean_gap 60s: bursts are not scheduling")
	}
	// Duration is pinned to 10s, so the burst must be over by then.
	if r := a.RateAt(burstAt.Add(11 * time.Second)); r > 150 {
		t.Errorf("rate %.1f still elevated 11s after a 10s burst: bursts do not release", r)
	}
}

// TestShapesCompose guards the multiplicative composition. The shapes are
// independent knobs, and a run that isolates one must not be affected by the
// others being configured-but-disabled.
func TestShapesCompose(t *testing.T) {
	base := 100.0
	start := time.Now()

	off := NewArrivalProcess(ArrivalConfig{}, base, rand.New(rand.NewSource(5)), start)
	if r := off.RateAt(start.Add(time.Hour)); math.Abs(r-base) > 0.001 {
		t.Errorf("all shapes disabled: rate = %.3f, want exactly the base %.1f", r, base)
	}

	// Disabled-but-populated config must behave identically to an empty one.
	cfg := testArrivalCfg() // Enabled defaults to false on both shapes
	populated := NewArrivalProcess(cfg, base, rand.New(rand.NewSource(5)), start)
	if r := populated.RateAt(start.Add(time.Hour)); math.Abs(r-base) > 0.001 {
		t.Errorf("shapes present but disabled: rate = %.3f, want the base %.1f", r, base)
	}
}

// TestRateNeverZero is a liveness guard. RateAt feeds a division in
// NextInterval, so a zero rate yields an infinite sleep and the generator parks
// forever with no way back -- worse than any misshapen load.
func TestRateNeverZero(t *testing.T) {
	cfg := ArrivalConfig{
		RampSeconds: 60,
		Diurnal:     DiurnalConfig{Enabled: true, PeriodSeconds: 100, PeakMultiplier: 2, TroughMultiplier: 0},
	}
	start := time.Now()
	a := NewArrivalProcess(cfg, 100, rand.New(rand.NewSource(6)), start)

	for i := 0; i <= 400; i++ {
		now := start.Add(time.Duration(i) * 500 * time.Millisecond)
		r := a.RateAt(now)
		if r <= 0 || math.IsNaN(r) || math.IsInf(r, 0) {
			t.Fatalf("rate at t+%ds = %v, must stay finite and positive", i/2, r)
		}
		if d := a.NextInterval(now); d <= 0 || d > 5*time.Second {
			t.Fatalf("interval at t+%ds = %v, must be positive and capped", i/2, d)
		}
	}
}

// TestRampScalesFromZero checks the ramp reaches full rate and stays there,
// rather than capping the run below target forever.
func TestRampScalesFromZero(t *testing.T) {
	cfg := ArrivalConfig{RampSeconds: 100}
	start := time.Now()
	a := NewArrivalProcess(cfg, 100, rand.New(rand.NewSource(7)), start)

	if r := a.RateAt(start.Add(50 * time.Second)); math.Abs(r-50) > 1 {
		t.Errorf("rate at half ramp = %.1f, want ~50", r)
	}
	if r := a.RateAt(start.Add(200 * time.Second)); math.Abs(r-100) > 0.001 {
		t.Errorf("rate after ramp = %.1f, want the full base 100", r)
	}
}
