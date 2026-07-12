package main

import (
	"context"
	"fmt"
	"math/rand"
	"os"
	"os/signal"
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

	// Convert Python-style {service} template to Go %s
	cfg.Target.BaseURLTemplate = ConvertTemplate(cfg.Target.BaseURLTemplate)

	var seed int64
	if cfg.Run.Seed != nil {
		seed = *cfg.Run.Seed
	} else {
		seed = time.Now().UnixNano()
	}
	rng := rand.New(rand.NewSource(seed))

	stats := NewStats()
	api := NewApiClient(cfg, stats)
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

	// Bootstrap
	if cfg.Bootstrap.Enabled {
		if err := Bootstrap(ctx, cfg, api, reg, rng); err != nil {
			reg.mu.Lock()
			routeCount := len(reg.Routes)
			reg.mu.Unlock()
			fmt.Printf("[bootstrap] failed (continuing with %d known routes): %v\n", routeCount, err)
		}
	}

	var wg sync.WaitGroup

	// Staff workers
	staffSim := NewStaffSim(cfg, api, reg, stats, rand.New(rand.NewSource(rng.Int63())))
	for i := 0; i < cfg.Staff.Workers; i++ {
		wg.Add(1)
		staffIdx := i
		go func() {
			defer wg.Done()
			staffSim.Worker(ctx, staffIdx)
		}()
	}

	// Ops worker
	if cfg.Ops.Enabled {
		wg.Add(1)
		go func() {
			defer wg.Done()
			OpsWorker(ctx, cfg, api, reg, stats, rand.New(rand.NewSource(rng.Int63())))
		}()
	}

	// Schedule publisher
	wg.Add(1)
	go func() {
		defer wg.Done()
		SchedulePublisher(ctx, cfg, api, reg, rand.New(rand.NewSource(rng.Int63())))
	}()

	// Scalper workers
	if cfg.Scalper.Enabled {
		for i := 0; i < cfg.Scalper.Workers; i++ {
			wg.Add(1)
			scalperIdx := i
			scalperSim := NewScalperSim(cfg, api, reg, stats, rand.New(rand.NewSource(rng.Int63())), scalperIdx)
			go func() {
				defer wg.Done()
				ScalperWorker(ctx, scalperIdx, cfg, scalperSim, stats)
				scalperSim.Close()
			}()
		}
	}

	// Customer workers via scheduler
	scheduler := NewScheduler(cfg, api, reg, stats, rng)
	mode := strings.ToLower(cfg.Run.Mode)
	if mode == "open-loop" {
		scheduler.RunOpenLoop(&wg, runJourney)
	} else {
		scheduler.RunClosedLoop(&wg, runJourney)
	}

	// Stats reporter
	wg.Add(1)
	go func() {
		defer wg.Done()
		reporter(ctx, cfg, stats, reg)
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
	staffSim.Close()
}

// runJourney executes one customer journey iteration.
func runJourney(ctx context.Context, p *Providers) {
	// Pick persona
	persona := p.PickPersona()
	p.ApplyPersona(persona)

	name := WeightedChoice(p.Rng, p.JourneyMix)

	outcome, err := executeJourney(ctx, p, name)
	if err != nil {
		if se, ok := err.(*StepError); ok {
			p.Stats.RecordJourney(name + ":failed")
			p.Stats.RecordError("journey:" + name + ":" + se.Step)
			fmt.Printf("[cust] %s failed - %v\n", name, err)
		} else if err == context.Canceled {
			return
		} else {
			p.Stats.RecordJourney(name + ":crashed")
			fmt.Printf("[cust] %s crashed - %v\n", name, err)
		}
		return
	}
	if outcome == "abandoned" {
		p.Stats.RecordJourney(name + ":abandoned")
	} else {
		p.Stats.RecordJourney(name + ":" + outcome)
	}
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
	case "campaign":
		return JourneyCampaign(ctx, p)
	default:
		return "unknown_journey", nil
	}
}

func reporter(ctx context.Context, cfg *Config, stats *Stats, reg *Registry) {
	interval := time.Duration(cfg.Run.StatsIntervalSecs * float64(time.Second))
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			fmt.Printf("[stats] %s\n", stats.JSON())
			reg.Save(cfg.Run.StateFile)
		}
	}
}
