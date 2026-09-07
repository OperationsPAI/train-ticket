package main

import (
	"bytes"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

// deployedConfigPath is the ConfigMap source that deploy/k8s/kustomization.yaml
// mounts into the loadgen pod at /etc/loadgen/config.yaml. It is the only
// config that runs in the cluster, so it is the schema contract for this
// module.
const deployedConfigPath = "../k8s/loadgen-config.yaml"

// TestDeployedConfigParses guards the highest-risk failure mode of the
// migration to this implementation: the deployed ConfigMap silently failing
// to satisfy this module's parser. The YAML decoder is non-strict, so a renamed or
// mistyped key does NOT error -- it lands as a zero value and the knob goes
// quietly dead. These assertions pin the values that must survive decoding.
func TestDeployedConfigParses(t *testing.T) {
	if _, err := os.Stat(deployedConfigPath); os.IsNotExist(err) {
		t.Skipf("%s not present", deployedConfigPath)
	}
	cfg, err := LoadConfig(deployedConfigPath)
	if err != nil {
		t.Fatalf("deployed ConfigMap does not parse: %v", err)
	}

	// run.* -- the concurrency contract
	if cfg.Run.Mode != "closed-loop" && cfg.Run.Mode != "open-loop" {
		t.Errorf("run.mode = %q, want closed-loop or open-loop", cfg.Run.Mode)
	}
	if cfg.Run.Workers <= 0 {
		t.Errorf("run.workers = %d, want > 0", cfg.Run.Workers)
	}
	if cfg.Run.StateFile == "" {
		t.Error("run.state_file empty: entity registry would not persist")
	}
	if cfg.Run.ThinkTime.Max <= 0 || cfg.Run.SessionPause.Max <= 0 {
		t.Error("think_time_seconds / session_pause_seconds did not decode")
	}

	// target.* -- without these every request fails
	if cfg.Target.BaseURLTemplate == "" || cfg.Target.RedisURL == "" {
		t.Error("target.base_url_template / redis_url did not decode")
	}
	if cfg.Target.RequestTimeoutSeconds <= 0 {
		t.Error("target.request_timeout_seconds did not decode")
	}

	// staff.* -- queue-draining side of the pipeline
	if cfg.Staff.Workers <= 0 {
		t.Errorf("staff.workers = %d, want > 0", cfg.Staff.Workers)
	}
	if cfg.Staff.PSeatPreferences <= 0 {
		t.Error("staff.p_seat_preferences dead: seatPreferences branch never fires")
	}
	if cfg.Staff.PRiskApprove <= 0 || cfg.Staff.PSupportReopenAfterClose <= 0 {
		t.Error("staff probability knobs did not decode")
	}

	// bootstrap.* -- searches find nothing if inventory is not seeded
	if cfg.Bootstrap.Enabled {
		if len(cfg.Bootstrap.Cities) < 2 {
			t.Errorf("bootstrap.cities = %d, want >= 2 for an origin/dest pair", len(cfg.Bootstrap.Cities))
		}
		if cfg.Bootstrap.ServicesPerDate <= 0 || cfg.Bootstrap.ServiceNumBase <= 0 {
			t.Error("bootstrap services_per_date / service_number_base did not decode")
		}
		if len(ComputeDepartureDates(cfg)) == 0 {
			t.Error("bootstrap departure window yields no dates")
		}
	}

	// scalper.* -- burst timing is read as ms and divided by 1000
	if cfg.Scalper.Enabled {
		if cfg.Scalper.Workers <= 0 || cfg.Scalper.AccountsPerWorker <= 0 {
			t.Error("scalper worker/account pool did not decode")
		}
		if cfg.Scalper.SlowdownMaxMs < cfg.Scalper.SlowdownMinMs {
			t.Error("scalper slowdown_max_ms < slowdown_min_ms")
		}
	}

	// polling / waitlist budgets
	if cfg.Polling.Attempts <= 0 || cfg.Polling.IntervalSeconds <= 0 {
		t.Error("polling.* did not decode")
	}
	if cfg.Waitlist.PollAttempts <= 0 || cfg.Waitlist.PollIntervalSeconds <= 0 {
		t.Error("waitlist poll budget did not decode (would fall back to global polling)")
	}

	// ops.*
	if cfg.Ops.Enabled {
		if cfg.Ops.DashboardID == "" {
			t.Error("ops.dashboard_id did not decode")
		}
		if cfg.Ops.IntervalSeconds.Max <= 0 {
			t.Error("ops.interval_seconds did not decode")
		}
		if cfg.Ops.WalletManualIssueMinorUnits <= 0 {
			t.Error("ops.wallet_manual_issue_minor_units did not decode")
		}
	}

	// defaults.* -- monetary values consumed by journeys
	if cfg.Currency() == "" {
		t.Error("defaults.currency did not decode")
	}
	for _, k := range []string{
		"insurance_premium_minor",
		"group_fare_minor",
		"group_discount_basis_points",
		"corporate_credit_limit_minor",
	} {
		if cfg.DefaultInt(k, 0) <= 0 {
			t.Errorf("defaults.%s did not decode", k)
		}
	}

	// behavior.* maps must decode as maps, not scalars
	for _, k := range []string{
		"channels", "seat_types", "traveler_types",
		"ride_branches", "disruption_option_mix", "transfer_contract_mix",
	} {
		if len(cfg.BehaviorMap(k, nil)) == 0 {
			t.Errorf("behavior.%s did not decode as a weight map", k)
		}
	}
	if cfg.BehaviorFloat("staff_wait_seconds", 0) <= 0 {
		t.Error("behavior.staff_wait_seconds did not decode")
	}

	// The optional purchase-journey branches. A probability knob that decodes
	// to 0 is indistinguishable from one that is not wired at all: the branch
	// simply never fires and the coverage loss is silent. These four are the
	// ONLY customer-journey traffic ancillary-service and invoicing receive,
	// and the only source of wallet-promotion reserve/redeem, so a zero here
	// is a functional coverage regression rather than a tuning choice.
	for _, k := range []string{"p_ancillary_purchase", "p_invoice_after_purchase",
		"p_payment_channel_read_probe"} {
		if cfg.BehaviorFloat(k, 0) <= 0 {
			t.Errorf("behavior.%s = 0: its branch never fires", k)
		}
	}
	if cfg.Wallet.PPurchaseReserveRedeem <= 0 {
		t.Error("wallet_promotion.p_purchase_reserve_redeem = 0: " +
			"the wallet issue/reserve/redeem branch never fires")
	}
	if cfg.Wallet.PurchaseBenefitMinorUnits <= 0 {
		t.Error("wallet_promotion.purchase_benefit_minor_units = 0: " +
			"wallet-promotion rejects a non-positive benefit amount")
	}

	// long_tail.* -- the real-id read probes.
	if !cfg.LongTailEnabled() {
		t.Error("long_tail.enabled is false: no read probes are generated at all")
	}
	for _, k := range []string{"p_read_probe_after_journey", "p_ancillary_read_probe"} {
		if cfg.LongTailFloat(k, 0) <= 0 {
			t.Errorf("long_tail.%s = 0: that probe never fires", k)
		}
	}
}

// TestDeployedJourneyMixIsDispatchable ensures every journey the deployed
// config asks for actually has an arm in executeJourney. A typo or a journey
// dropped in the migration would otherwise burn its share of the mix on the
// "unknown_journey" no-op instead of generating load.
func TestDeployedJourneyMixIsDispatchable(t *testing.T) {
	if _, err := os.Stat(deployedConfigPath); os.IsNotExist(err) {
		t.Skipf("%s not present", deployedConfigPath)
	}
	cfg, err := LoadConfig(deployedConfigPath)
	if err != nil {
		t.Fatalf("deployed ConfigMap does not parse: %v", err)
	}
	if len(cfg.Journey) == 0 {
		t.Fatal("journey_mix is empty")
	}

	// Scrape the dispatch arms out of executeJourney rather than calling it:
	// the journey funcs immediately perform network I/O on a nil Providers.
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("read main.go: %v", err)
	}
	arms := map[string]bool{}
	for _, m := range regexp.MustCompile(`case "([a-z_]+)":`).FindAllStringSubmatch(string(src), -1) {
		arms[m[1]] = true
	}
	for name, weight := range cfg.Journey {
		if weight <= 0 {
			continue
		}
		if !arms[name] {
			t.Errorf("journey_mix has %q (weight %v) but executeJourney has no arm for it", name, weight)
		}
	}
}

// TestDeployedRecordingLandsOnThePersistentVolume closes the loop between the
// two files that have to agree for the per-request record file to survive a
// run (issue #420): recording.path in the ConfigMap, and the volume mount in
// the Deployment. A path outside a mounted volume writes to the container's
// writable layer and silently dies with the container -- the generator keeps
// running and nothing looks wrong until you go looking for the file.
func TestDeployedRecordingLandsOnThePersistentVolume(t *testing.T) {
	if _, err := os.Stat(deployedConfigPath); os.IsNotExist(err) {
		t.Skipf("%s not present", deployedConfigPath)
	}
	cfg, err := LoadConfig(deployedConfigPath)
	if err != nil {
		t.Fatalf("deployed ConfigMap does not parse: %v", err)
	}
	if !cfg.Recording.Enabled {
		t.Skip("recording disabled in the deployed config")
	}
	if cfg.Recording.Path == "" {
		t.Fatal("recording.enabled is true but recording.path did not decode")
	}
	if cfg.Recording.BufferRecords <= 0 {
		t.Error("recording.buffer_records <= 0: every record would be dropped")
	}
	if cfg.Recording.FlushIntervalSeconds <= 0 {
		t.Error("recording.flush_interval_seconds <= 0: the tail would never flush")
	}
	if cfg.Recording.MaxFileMegabytes <= 0 {
		t.Error("recording.max_file_megabytes <= 0: the record file would grow unbounded " +
			"on an emptyDir, which is a node-disk-pressure risk")
	}
	// An unsampled parent means conformant services record no span, so the
	// recorded trace id would point at nothing in Jaeger.
	if r := cfg.Recording.SampledRatio(); r <= 0 {
		t.Errorf("recording.trace_sampled_ratio = %v: no server span is recorded, "+
			"so the recorded trace ids join nothing", r)
	}

	// Cross-check the path against the Deployment's volumeMounts.
	const deploymentPath = "../k8s/loadgen.yaml"
	data, err := os.ReadFile(deploymentPath)
	if err != nil {
		t.Skipf("%s not readable: %v", deploymentPath, err)
	}
	var dep struct {
		Spec struct {
			Template struct {
				Spec struct {
					Containers []struct {
						VolumeMounts []struct {
							Name      string `yaml:"name"`
							MountPath string `yaml:"mountPath"`
							ReadOnly  bool   `yaml:"readOnly"`
						} `yaml:"volumeMounts"`
					} `yaml:"containers"`
				} `yaml:"spec"`
			} `yaml:"template"`
		} `yaml:"spec"`
	}
	if err := yaml.Unmarshal(data, &dep); err != nil {
		t.Fatalf("%s does not parse: %v", deploymentPath, err)
	}
	if len(dep.Spec.Template.Spec.Containers) == 0 {
		t.Fatalf("%s declares no containers", deploymentPath)
	}

	var covering string
	for _, m := range dep.Spec.Template.Spec.Containers[0].VolumeMounts {
		if m.MountPath == "" || !strings.HasPrefix(cfg.Recording.Path, strings.TrimSuffix(m.MountPath, "/")+"/") {
			continue
		}
		if m.ReadOnly {
			t.Errorf("recording.path %q is under the READ-ONLY mount %q: the recorder cannot open it",
				cfg.Recording.Path, m.MountPath)
			continue
		}
		covering = m.MountPath
	}
	if covering == "" {
		t.Errorf("recording.path %q is not under any writable volumeMount in %s: "+
			"the record file would be written to the container's writable layer and "+
			"lost on restart", cfg.Recording.Path, deploymentPath)
	}

	// The registry and the record file share the volume; they must not be the
	// same file.
	if cfg.Recording.Path == cfg.Run.StateFile {
		t.Error("recording.path equals run.state_file: the records would clobber the registry")
	}
}

// in the deployed ConfigMap that no Go struct tag claims. Because the decoder
// is non-strict such a key is silently ignored, so an operator can "tune" a
// setting that does nothing. Any hit here is either a typo or a genuine gap
// that must be wired up or removed -- see the GAP comments in the config.
func TestDeployedConfigHasNoUnknownKeys(t *testing.T) {
	if _, err := os.Stat(deployedConfigPath); os.IsNotExist(err) {
		t.Skipf("%s not present", deployedConfigPath)
	}
	data, err := os.ReadFile(deployedConfigPath)
	if err != nil {
		t.Fatal(err)
	}
	// Strict decode: KnownFields makes yaml.v3 reject unmapped keys.
	dec := yaml.NewDecoder(bytes.NewReader(data))
	dec.KnownFields(true)
	var strict Config
	if err := dec.Decode(&strict); err != nil {
		t.Errorf("deployed ConfigMap has keys no Go field maps to (silently ignored at runtime): %v", err)
	}

	// Free-form sections are map[string]interface{} so KnownFields cannot
	// police them. Pin their keys against the accessors the code uses.
	var raw struct {
		Behavior map[string]interface{} `yaml:"behavior"`
		Defaults map[string]interface{} `yaml:"defaults"`
		LongTail map[string]interface{} `yaml:"long_tail"`
	}
	if err := yaml.Unmarshal(data, &raw); err != nil {
		t.Fatal(err)
	}
	// Every behavior key in the deployed config must now be read by some Go
	// file. There is deliberately no knownInert escape hatch: the three keys
	// that used to live here (p_ancillary_purchase, p_invoice_after_purchase,
	// p_payment_channel_read_probe) are wired up, and an empty allowlist is
	// what keeps a future migration from quietly parking a knob here again.
	consumed := map[string]bool{}
	sources, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range sources {
		if strings.HasSuffix(f, "_test.go") {
			continue
		}
		src, err := os.ReadFile(filepath.Clean(f))
		if err != nil {
			continue
		}
		re := regexp.MustCompile(`(?:Chance|OptionalChance|CtxFloat|CtxMap|BehaviorFloat|BehaviorMap|DefaultInt|DefaultAmount|LongTailChance|LongTailFloat)\("([a-z_]+)"`)
		for _, m := range re.FindAllStringSubmatch(string(src), -1) {
			consumed[m[1]] = true
		}
	}
	for k := range raw.Behavior {
		if !consumed[k] {
			t.Errorf("behavior.%s is in the deployed config but no Go code reads it "+
				"(silently inert -- wire it up or drop it)", k)
		}
	}
	// long_tail.* is a free-form map too, so the same check applies: a probe
	// probability nobody reads is a dead knob.
	for k := range raw.LongTail {
		if k == "enabled" {
			continue // read via cfg.LongTailEnabled()
		}
		if !consumed[k] {
			t.Errorf("long_tail.%s is in the deployed config but no Go code reads it", k)
		}
	}
	for k := range raw.Defaults {
		if k == "currency" {
			continue // read via cfg.Currency()
		}
		// Pre-existing inert knob, NOT a migration regression: the previous
		// implementation also read this key into a local and then never put it
		// in the travel-insurance request body. travel-insurance
		// /api/v1/policies takes no premium field, so nothing consumes it in
		// either implementation. Kept as config-level documentation of intent.
		if k == "insurance_premium_minor" {
			continue
		}
		if !consumed[k] {
			t.Errorf("defaults.%s is in the deployed config but no Go code reads it", k)
		}
	}
}
