package main

import (
	"context"
	"fmt"
	"math/rand"
	"os"
	"os/signal"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

func main() {
	cfgPath := os.Getenv("LOADGEN_CONFIG")
	if cfgPath == "" {
		cfgPath = "config.yaml"
	}

	cfg, err := LoadConfig(cfgPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to load config: %v\n", err)
		os.Exit(1)
	}

	// The one setting an operator flips per deployment rather than per
	// profile, so it is an env var and not a key in the file. The Helm chart
	// ships loadgen-config.yaml verbatim through `.Files.Glob`, so a value
	// inside it cannot be overridden by `--set` at install time.
	if os.Getenv("LOADGEN_RECORD_STDOUT") == "1" {
		cfg.Recording.Stdout = true
	}
	// Same reason: a deployment that is measured in windows needs a period it
	// can cover, and one that is watched needs a period a person can sit
	// through. Both read the same file.
	if raw := os.Getenv("LOADGEN_DIURNAL_PERIOD_SECONDS"); raw != "" {
		seconds, err := strconv.ParseFloat(raw, 64)
		if err != nil || seconds <= 0 {
			fmt.Fprintf(os.Stderr, "LOADGEN_DIURNAL_PERIOD_SECONDS=%q is not a positive number\n", raw)
			os.Exit(1)
		}
		cfg.Run.Arrival.Diurnal.PeriodSeconds = seconds
	}

	// Convert the config's {service} placeholder to Go's %s
	cfg.Target.BaseURLTemplate = ConvertTemplate(cfg.Target.BaseURLTemplate)

	// Resolve the route topology before anything runs. A bootstrap.lines entry
	// naming no line is fatal rather than skipped: it would silently remove a
	// share of the offered demand while the run looked healthy.
	lines, err := ActiveLines(cfg.Bootstrap.Lines)
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(1)
	}
	fmt.Printf("[topology] %d lines, %d stations: %s\n",
		len(lines), len(Stations(lines)), lineNames(lines))
	// The per-persona demand model, on stdout at startup. An operator has to be
	// able to see that the personas differ in WHERE and WHEN they travel, not
	// only that the config file says so.
	for _, name := range sortedPersonaNames(cfg) {
		if summary := PersonaDemandSummary(cfg, name); summary != "" {
			fmt.Printf("[demand] %s\n", summary)
		}
	}

	var seed int64
	if cfg.Run.Seed != nil {
		seed = *cfg.Run.Seed
	} else {
		seed = time.Now().UnixNano()
	}
	rng := rand.New(rand.NewSource(seed))

	stats := NewStats()

	// Outcome recording: one record per HTTP request and one per journey
	// attempt. nil when disabled; every Recorder method is nil-safe. Opened
	// before any request is made so nothing escapes unrecorded, and closed
	// last so the buffer drains.
	rec, err := NewRecorder(cfg)
	if err != nil {
		// A misconfigured record path is a measurement bug, not a reason to
		// stop generating load: warn loudly and run without recording.
		fmt.Fprintf(os.Stderr, "[recorder] disabled: %v\n", err)
	}
	if rec.Enabled() {
		fmt.Printf("[recorder] request + journey records -> %s (buffer %d, flush %.1fs, sampled_ratio %.3g)\n",
			rec.Path(), cfg.Recording.BufferRecords,
			cfg.Recording.FlushIntervalSeconds, cfg.Recording.SampledRatio())
	}

	api := NewApiClientWithRecorder(cfg, stats, rec)
	reg := LoadRegistry(cfg.Run.StateFile)

	// Context with signal handling
	ctx, cancel := context.WithCancel(context.Background())
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		fmt.Println("\n[loadgen] shutting down...")
		cancel()
	}()

	// Duration-based stop
	if cfg.Run.DurationSeconds > 0 {
		go func() {
			select {
			case <-time.After(time.Duration(cfg.Run.DurationSeconds * float64(time.Second))):
				cancel()
			case <-ctx.Done():
			}
		}()
	}

	// Bootstrap. A failure here is why every later purchase fails on
	// available-train, so it gets a record of its own: the run continues with
	// whatever inventory exists, and that decision has to be visible in the
	// same stream as its consequences.
	//
	// Gated on the services it writes through being ready first. Bootstrap runs
	// once, so a service that is not up yet does not cost it a retry, it costs
	// the whole run its inventory: measured, the loadgen started 4 seconds
	// ahead of place-network, Bootstrap failed on its first request, and 78750
	// journeys over the next 27 minutes failed on `available-train: no routes
	// and fewer than 2 places`.
	if cfg.Bootstrap.Enabled {
		attempt := NewAttempt("bootstrap", "bootstrap_inventory", "")
		waiting := AwaitServices(ctx, cfg.Target.BaseURLTemplate, bootstrapServices,
			bootstrapWaitTimeout, bootstrapWaitInterval)
		if len(waiting) > 0 {
			// Recorded and then attempted anyway. The services may come up
			// during the attempt, and inventory that partly exists is worth
			// more to the run than none; what must not happen is the wait
			// expiring silently.
			fmt.Fprintf(os.Stderr,
				"[bootstrap] %s still not ready after %s; attempting anyway\n",
				describeWaiting(waiting), bootstrapWaitTimeout)
		}
		err := Bootstrap(WithAttempt(ctx, attempt), cfg, api, reg, rng, rec)
		reg.mu.Lock()
		routeCount := len(reg.Routes)
		reg.mu.Unlock()
		attempt.Record(rec, fmt.Sprintf("seeded_%d_routes", routeCount), err)
		// Said on stdout as well as in the record. Every journey that reads
		// this inventory reports its own failure, so a run with no routes
		// produces tens of thousands of rows that each name a symptom, and one
		// line here names the cause.
		if err != nil {
			fmt.Fprintf(os.Stderr, "[bootstrap] FAILED after %d routes: %v\n", routeCount, err)
		} else {
			fmt.Printf("[bootstrap] %d routes\n", routeCount)
		}
	}

	var wg sync.WaitGroup

	// Staff workers
	staffSim := NewStaffSimWithRecorder(cfg, api, reg, stats,
		rand.New(rand.NewSource(rng.Int63())), rec)
	staffCtx := WithChain(ctx, "staff")
	for i := 0; i < cfg.Staff.Workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			staffSim.Worker(staffCtx)
		}()
	}

	// Ops worker
	if cfg.Ops.Enabled {
		wg.Add(1)
		go func() {
			defer wg.Done()
			OpsWorker(WithChain(ctx, "ops"), cfg, api, reg, stats,
				rand.New(rand.NewSource(rng.Int63())), rec)
		}()
	}

	// Schedule publisher
	wg.Add(1)
	go func() {
		defer wg.Done()
		SchedulePublisher(WithChain(ctx, "schedule"), cfg, api, reg,
			rand.New(rand.NewSource(rng.Int63())), rec)
	}()

	// Scalper workers
	if cfg.Scalper.Enabled {
		scalperCtx := WithChain(ctx, "scalper")
		for i := 0; i < cfg.Scalper.Workers; i++ {
			wg.Add(1)
			scalperIdx := i
			scalperSim := NewScalperSim(cfg, api, reg, stats, rand.New(rand.NewSource(rng.Int63())), scalperIdx)
			go func() {
				defer wg.Done()
				ScalperWorker(scalperCtx, cfg, scalperSim, stats, rec)
				scalperSim.Close()
			}()
		}
	}

	// Customer workers via scheduler. The recorder is bound in here rather
	// than reached through the Providers, which has no field for it: a journey
	// record is written by the runner around the journey, not by the journey.
	customerRunner := func(jctx context.Context, p *Providers) {
		runJourney(jctx, p, rec)
	}
	scheduler := NewScheduler(cfg, api, reg, stats, rng)
	mode := strings.ToLower(cfg.Run.Mode)
	if mode == "open-loop" {
		scheduler.RunOpenLoop(&wg, customerRunner)
	} else {
		scheduler.RunClosedLoop(&wg, customerRunner)
	}

	// Stats reporter
	wg.Add(1)
	go func() {
		defer wg.Done()
		reporter(ctx, cfg, stats, reg, rec)
	}()

	// Wait for context cancellation then let all goroutines drain
	<-ctx.Done()
	// Give goroutines time to finish current work
	time.Sleep(2 * time.Second)
	scheduler.Stop()

	wg.Wait()

	// Final state save and stats
	reg.Save(cfg.Run.StateFile)
	fmt.Printf("[final] %s\n", stats.JSON())
	// Drain and close the record file last: the buffer may still hold records
	// from journeys that finished during shutdown.
	if rec.Enabled() {
		rec.Close()
		written, dropped := rec.Counters()
		fmt.Printf("[recorder] %s: %d records written, %d dropped\n", rec.Path(), written, dropped)
		if dropped > 0 {
			fmt.Fprintf(os.Stderr,
				"[recorder] WARNING %d records dropped (buffer full). Offered load was "+
					"unaffected -- drops are the deliberate trade -- but the record file is "+
					"incomplete; raise recording.buffer_records or use faster storage.\n", dropped)
		}
	}
	staffSim.Close()
}

// runJourney executes one customer journey iteration and records its outcome.
//
// The record is written on EVERY path, including the ones that used to return
// early: a journey that fails, crashes or is cut off by shutdown is exactly
// the journey a complaint would be written about, and the printf that used to
// stand in for it carried no timestamp, no duration, no step and no trace id.
func runJourney(ctx context.Context, p *Providers, rec *Recorder) {
	personaName, persona := p.PickPersona()
	p.ApplyPersona(personaName, persona)

	name := WeightedChoice(p.Rng, p.JourneyMix)

	// Tag every request this journey makes with the journey name and the
	// attempt that owns it, so the per-request record file can be split by
	// journey and each request can report progress back to the attempt. The
	// ApiClient is a single shared object, so both ride the context instead.
	attempt := NewAttempt(name, name, personaName)
	ctx = WithAttempt(ctx, attempt)

	outcome, err := executeJourney(ctx, p, name)
	attempt.Record(rec, outcome, err)

	if err != nil {
		if se, ok := err.(*StepError); ok {
			p.Stats.RecordJourney(name + ":failed")
			p.Stats.RecordError("journey:" + name + ":" + se.Step)
		} else if err == context.Canceled {
			return
		} else {
			p.Stats.RecordJourney(name + ":crashed")
		}
		return
	}
	if outcome == "abandoned" {
		p.Stats.RecordJourney(name + ":abandoned")
	} else {
		p.Stats.RecordJourney(name + ":" + outcome)
	}
}

// lineNames renders the active line names for the startup banner.
func lineNames(lines []Line) string {
	names := make([]string, 0, len(lines))
	for _, line := range lines {
		names = append(names, line.Name)
	}
	return strings.Join(names, ", ")
}

// sortedPersonaNames returns the configured persona names in a stable order,
// so the startup banner does not reshuffle between restarts.
func sortedPersonaNames(cfg *Config) []string {
	names := make([]string, 0, len(cfg.Personas))
	for name := range cfg.Personas {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

// dispatchableJourneys names every journey executeJourney has a case for.
//
// Beside the switch so the two are read and edited together. What it exists
// for is a test that asks whether a journey the configuration weights can
// actually be dispatched, and that question cannot be asked by calling
// executeJourney, which would run the journey against a cluster.
//
// `campaign` is deliberately absent. Drafting a marketing campaign is ops-side
// work and runs on the ops sweep under `ops.p_campaign_draft`, so no customer
// draw should select it.
var dispatchableJourneys = map[string]bool{
	"browse":        true,
	"purchase":      true,
	"refund":        true,
	"change":        true,
	"fulfillment":   true,
	"support":       true,
	"legacy":        true,
	"ride":          true,
	"disruption":    true,
	"transfer":      true,
	"loyalty":       true,
	"insurance":     true,
	"group_booking": true,
	"corporate":     true,
}

func executeJourney(ctx context.Context, p *Providers, name string) (string, error) {
	switch name {
	case "browse":
		return JourneyBrowse(ctx, p)
	case "purchase":
		return JourneyPurchase(ctx, p)
	case "refund":
		return JourneyRefund(ctx, p)
	case "change":
		return JourneyChange(ctx, p)
	case "fulfillment":
		return JourneyFulfillment(ctx, p)
	case "support":
		return JourneySupport(ctx, p)
	case "legacy":
		return JourneyLegacy(ctx, p)
	case "ride":
		return JourneyRide(ctx, p)
	case "disruption":
		return JourneyDisruption(ctx, p)
	case "transfer":
		return JourneyTransfer(ctx, p)
	case "loyalty":
		return JourneyLoyalty(ctx, p)
	case "insurance":
		return JourneyInsurance(ctx, p)
	case "group_booking":
		return JourneyGroupBooking(ctx, p)
	case "corporate":
		return JourneyCorporate(ctx, p)
	default:
		return "unknown_journey", nil
	}
}

func reporter(ctx context.Context, cfg *Config, stats *Stats, reg *Registry, rec *Recorder) {
	interval := time.Duration(cfg.Run.StatsIntervalSecs * float64(time.Second))
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			// The aggregate snapshot line is unchanged, on purpose: existing
			// tooling parses it. Recorder health goes on its own line.
			fmt.Printf("[stats] %s\n", stats.JSON())
			if rec.Enabled() {
				written, dropped := rec.Counters()
				fmt.Printf("[recorder] written=%d dropped=%d path=%s\n", written, dropped, rec.Path())
			}
			reg.Save(cfg.Run.StateFile)
		}
	}
}
