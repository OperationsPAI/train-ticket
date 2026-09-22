package main

import (
	"os"

	"gopkg.in/yaml.v3"
)

// Config mirrors the YAML structure of
// deploy/helm/train-ticket/loadgen-config.yaml.
// NOTE: decoding is non-strict -- unknown keys are ignored, so a renamed or
// misspelled key silently becomes a zero value. deployed_config_test.go
// guards the deployed ConfigMap against that.
type Config struct {
	Target   TargetConfig              `yaml:"target"`
	Run      RunConfig                 `yaml:"run"`
	Journey  map[string]float64        `yaml:"journey_mix"`
	Behavior map[string]interface{}    `yaml:"behavior"`
	Staff    StaffConfig               `yaml:"staff"`
	LongTail map[string]interface{}    `yaml:"long_tail"`
	Ops      OpsConfig                 `yaml:"ops"`
	Bootstrap BootstrapConfig          `yaml:"bootstrap"`
	Defaults  map[string]interface{}   `yaml:"defaults"`
	Scalper   ScalperConfig            `yaml:"scalper"`
	Polling   PollingConfig            `yaml:"polling"`
	Wallet    WalletConfig             `yaml:"wallet_promotion"`
	Waitlist  WaitlistPollConfig       `yaml:"waitlist"`
	Personas  map[string]PersonaConfig `yaml:"personas"`
	Recording RecordingConfig          `yaml:"recording"`
}

// RecordingConfig controls the per-request outcome record file (issue #420).
// See recorder.go for the "must not change offered load" design constraint.
type RecordingConfig struct {
	Enabled bool `yaml:"enabled"`
	// Path of the JSON Lines record file. Deliberately configuration and not
	// a constant: in the cluster this must land under the /data volume from
	// deploy/helm/train-ticket/templates/loadgen.yaml so the file outlives the
	// run.
	Path string `yaml:"path"`
	// BufferRecords is the depth of the hand-off channel between request
	// goroutines and the writer goroutine. Sized for a burst, not a backlog:
	// once it is full, records are dropped and counted rather than allowed to
	// back-pressure into the request path and dent the offered load.
	BufferRecords int `yaml:"buffer_records"`
	// FlushIntervalSeconds bounds how stale the on-disk tail can be, so the
	// file is useful during a run and survives an unclean pod kill.
	FlushIntervalSeconds float64 `yaml:"flush_interval_seconds"`
	// MaxFileMegabytes caps the file; at the cap it is renamed to <path>.1 and
	// a fresh file starts, so at most 2x this is on disk. Absent/0 means the
	// 512 MiB default; a negative value disables rotation (unbounded growth,
	// only sensible for a short bounded run).
	MaxFileMegabytes int `yaml:"max_file_megabytes"`
	// TraceSampledRatio is the fraction of requests whose traceparent carries
	// the sampled flag (flags=01). Default 1.0: the loadgen originates these
	// traces, and an unsampled parent means the services likely record no span
	// at all, which would leave the recorded trace id pointing at nothing.
	// Lower it only to shed tracing-backend volume.
	//
	// Pointer because 0.0 is a meaningful setting ("never set the sampled
	// bit") that must be distinguishable from "key absent".
	TraceSampledRatio *float64 `yaml:"trace_sampled_ratio"`
	// Stdout writes each record to stdout as well as to the file, so the
	// collector's filelog receiver picks it up and the records land in the
	// same store as the traces they join to.
	//
	// The file is an emptyDir, so it dies with the pod -- and for a run whose
	// whole namespace is deleted when it ends, that is before anybody reads
	// it. Client outcomes are the one signal the server cannot reconstruct:
	// a request that never got a status leaves no server span at all.
	//
	// Off by default. A long-lived deployment has the file, and doubling the
	// log volume of the busiest pod on the node buys it nothing.
	Stdout bool `yaml:"stdout"`
}

// SampledRatio resolves recording.trace_sampled_ratio, defaulting to 1.0
// (always sampled) when the key is absent, and clamping to [0,1].
func (rc RecordingConfig) SampledRatio() float64 {
	if rc.TraceSampledRatio == nil {
		return 1.0
	}
	switch v := *rc.TraceSampledRatio; {
	case v < 0:
		return 0
	case v > 1:
		return 1
	default:
		return v
	}
}

type TargetConfig struct {
	BaseURLTemplate       string  `yaml:"base_url_template"`
	RedisURL              string  `yaml:"redis_url"`
	RequestTimeoutSeconds float64 `yaml:"request_timeout_seconds"`
}

type RunConfig struct {
	Mode                string       `yaml:"mode"`
	Workers             int          `yaml:"workers"`
	DurationSeconds     float64      `yaml:"duration_seconds"`
	Seed                *int64       `yaml:"seed"`
	ThinkTime           RangeSeconds `yaml:"think_time_seconds"`
	SessionPause        RangeSeconds `yaml:"session_pause_seconds"`
	StatsIntervalSecs   float64      `yaml:"stats_interval_seconds"`
	StateFile           string       `yaml:"state_file"`
	TargetRPS           float64      `yaml:"target_rps"`
	RampDurationSeconds float64      `yaml:"ramp_duration_seconds"`
	Arrival             ArrivalConfig `yaml:"arrival"`
}

// ArrivalConfig shapes open-loop arrivals. Ignored in closed-loop mode, where
// the rate is whatever the worker pool and the server's response time produce.
// See arrival.go for why each shape exists and what it exercises.
type ArrivalConfig struct {
	// Poisson draws inter-arrival gaps from an exponential distribution instead
	// of emitting at fixed intervals. This is the one that changes the character
	// of the load rather than just its envelope: it takes the per-second
	// coefficient of variation from ~0.13 (metronome) to ~1.0 (independent
	// arrivals), which is what makes queues form at all.
	//
	// Pointer so that an explicit `false` is distinguishable from an absent key.
	// Absent defaults to TRUE: a fixed-interval generator is never the more
	// realistic choice, so the default should not be the one that silently
	// produces a flat line.
	Poisson *bool `yaml:"poisson"`

	// RampSeconds linearly scales the rate from 0 to full over this window, so a
	// restarted pod does not hit a cold cluster at full rate.
	RampSeconds float64 `yaml:"ramp_seconds"`

	Diurnal DiurnalConfig `yaml:"diurnal"`
	Burst   BurstConfig   `yaml:"burst"`
}

// DiurnalConfig is the slow peak/trough cycle. The period is compressed
// configuration rather than a wall-clock day so the shape is visible within a
// single observation window -- see arrival.go.
type DiurnalConfig struct {
	Enabled          bool    `yaml:"enabled"`
	PeriodSeconds    float64 `yaml:"period_seconds"`
	PeakMultiplier   float64 `yaml:"peak_multiplier"`
	TroughMultiplier float64 `yaml:"trough_multiplier"`
	// PhaseOffset in [0,1) shifts where in the cycle the run starts. Lets two
	// loadgen replicas be deliberately out of phase instead of all peaking
	// together.
	PhaseOffset float64 `yaml:"phase_offset"`
}

// BurstConfig is the short spike: a ticket release, a push notification, a
// scalper script waking up. Gaps between bursts are themselves exponential, so
// the spikes are not predictable.
type BurstConfig struct {
	Enabled         bool         `yaml:"enabled"`
	MeanGapSeconds  float64      `yaml:"mean_gap_seconds"`
	DurationSeconds RangeSeconds `yaml:"duration_seconds"`
	Multiplier      RangeSeconds `yaml:"multiplier"`
}

// PoissonEnabled resolves arrival.poisson, defaulting to true when absent.
func (ac ArrivalConfig) PoissonEnabled() bool {
	if ac.Poisson == nil {
		return true
	}
	return *ac.Poisson
}

type RangeSeconds struct {
	Min float64 `yaml:"min"`
	Max float64 `yaml:"max"`
}

type StaffConfig struct {
	Workers                        int          `yaml:"workers"`
	ThinkTime                      RangeSeconds `yaml:"think_time_seconds"`
	QueuePollSeconds               float64      `yaml:"queue_poll_seconds"`
	PRiskApprove                   float64      `yaml:"p_risk_approve"`
	PSupportAssign                 float64      `yaml:"p_support_assign"`
	PSupportResolve                float64      `yaml:"p_support_resolve"`
	PSupportAssignResolveBranch    float64      `yaml:"p_support_assign_resolve_branch"`
	PSupportClassifyEscalateBranch float64      `yaml:"p_support_classify_escalate_branch"`
	PSupportClassifyCloseBranch    float64      `yaml:"p_support_classify_close_branch"`
	PSupportReopenAfterClose       float64      `yaml:"p_support_reopen_after_close"`
	PSeatPreferences               float64      `yaml:"p_seat_preferences"`
}

type OpsConfig struct {
	Enabled                     bool         `yaml:"enabled"`
	IntervalSeconds             RangeSeconds `yaml:"interval_seconds"`
	DashboardID                 string       `yaml:"dashboard_id"`
	PSupplierCatalogWrite       float64      `yaml:"p_supplier_catalog_write"`
	PWalletManualIssue          float64      `yaml:"p_wallet_manual_issue"`
	WalletManualIssueMinorUnits int          `yaml:"wallet_manual_issue_minor_units"`
	// PCampaignDraft is the chance one sweep drafts a marketing campaign.
	// Ops-side rather than a persona weight: no customer drafts a campaign,
	// and a customer weight would tie its rate to target_rps.
	PCampaignDraft float64 `yaml:"p_campaign_draft"`
}

type BootstrapConfig struct {
	Enabled bool `yaml:"enabled"`
	// Lines selects which lines of the route topology this run creates
	// inventory on, by name. Empty means the whole network. The stations
	// themselves are a Go table (topology.go): they are consumed both by
	// Bootstrap and by the persona demand model, and a list duplicated in
	// config and code would drift.
	Lines           []string        `yaml:"lines"`
	DepartureWindow DepartureWindow `yaml:"departure_window"`
	DepartureDates  []string        `yaml:"departure_dates"`
	ServicesPerDate int             `yaml:"services_per_date"`
	ServiceNumBase  int             `yaml:"service_number_base"`
	MinRoutes       int             `yaml:"min_routes"`

	EligibilityFares EligibilityFaresConfig `yaml:"eligibility_fares"`
}

// EligibilityFaresConfig is the fare rule set the generator publishes so that
// an eligibility certificate has something to discount.
//
// fare-pricing does not take an eligibility on a quote request. It derives the
// eligibility types a quote may discount from the `discount` rules of the rule
// set the request's productCode resolves to, and only then asks
// identity-verification whether a traveler holds a matching certificate
// (_active_discount_types and _discount_allowed in
// services/fare-pricing/src/fare_pricing/application/service.py and domain.py).
// The rule set published for rail-standard carries no discount rule at all, so
// against it a certificate can never change a price.
//
// The non-discount amounts mirror the rail-standard rule set deliberately. An
// eligible purchase must differ from an ordinary one by the discount and by
// nothing else, otherwise the two prices are not comparable. refund_fee and
// change_fee are carried for the same reason: post-sales prices a refund from
// the rule set the ORIGINAL quote's productCode resolves to, and a product code
// without a refund_fee rule yields a FAILED adjustment quote and a zero refund
// (assess_refund in services/fare-pricing/src/fare_pricing/domain.py).
type EligibilityFaresConfig struct {
	Enabled     bool   `yaml:"enabled"`
	ProductCode string `yaml:"product_code"`
	// Version prefix of the published rule set; a UTC timestamp is appended so
	// each run publishes its own version rather than colliding with the last.
	VersionPrefix  string `yaml:"version_prefix"`
	SupplierID     string `yaml:"supplier_id"`
	ContractID     string `yaml:"contract_id"`
	BaseFareMinor  int    `yaml:"base_fare_minor"`
	TaxMinor       int    `yaml:"tax_minor"`
	RefundFeeMinor int    `yaml:"refund_fee_minor"`
	ChangeFeeMinor int    `yaml:"change_fee_minor"`
	// Discounts maps an eligibility type to the amount it takes off the fare.
	// Only STUDENT, CHILD and MILITARY_DISABLED are accepted, by both
	// identity-verification (domain.py EligibilityCertificate.register) and
	// fare-pricing (domain.py ELIGIBILITY_TYPES).
	Discounts map[string]int `yaml:"discounts"`
	// AnnualUsageLimit is the certificate's yearly cap. A certificate whose
	// reserved plus confirmed usage reaches it stops being active.
	AnnualUsageLimit int `yaml:"annual_usage_limit"`
	// ValidityDays is how far ahead a registered certificate stays valid. It
	// must cover the whole booking window, since fare-pricing checks the
	// certificate against the journey date and not against today.
	ValidityDays int `yaml:"validity_days"`
}

type DepartureWindow struct {
	FromDays int `yaml:"from_days"`
	ToDays   int `yaml:"to_days"`
}

type ScalperConfig struct {
	Enabled           bool    `yaml:"enabled"`
	Workers           int     `yaml:"workers"`
	AccountsPerWorker int     `yaml:"accounts_per_worker"`
	TargetSegments    int     `yaml:"target_segments"`
	RetryOnFailure    float64 `yaml:"retry_on_failure"`
	RetrySameKey      bool    `yaml:"retry_same_key"`
	PurchaseBatchSize int     `yaml:"purchase_batch_size"`
	BurstGapMs        float64 `yaml:"burst_gap_ms"`
	SlowdownProb      float64 `yaml:"slowdown_probability"`
	SlowdownMinMs     float64 `yaml:"slowdown_min_ms"`
	SlowdownMaxMs     float64 `yaml:"slowdown_max_ms"`
}

type PollingConfig struct {
	Attempts        int     `yaml:"attempts"`
	IntervalSeconds float64 `yaml:"interval_seconds"`
}

type WalletConfig struct {
	PPurchaseReserveRedeem    float64 `yaml:"p_purchase_reserve_redeem"`
	PurchaseBenefitMinorUnits int     `yaml:"purchase_benefit_minor_units"`
}

type WaitlistPollConfig struct {
	PollAttempts        int     `yaml:"poll_attempts"`
	PollIntervalSeconds float64 `yaml:"poll_interval_seconds"`
}

type PersonaConfig struct {
	Weight         float64                `yaml:"weight"`
	JourneyWeights map[string]float64     `yaml:"journey_weights"`
	Overrides      map[string]interface{} `yaml:"overrides"`
}

func LoadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	cfg := &Config{}
	if err := yaml.Unmarshal(data, cfg); err != nil {
		return nil, err
	}
	applyDefaults(cfg)
	return cfg, nil
}

func applyDefaults(cfg *Config) {
	if cfg.Target.RequestTimeoutSeconds == 0 {
		cfg.Target.RequestTimeoutSeconds = 10
	}
	if cfg.Run.Workers == 0 {
		cfg.Run.Workers = 6
	}
	if cfg.Run.StatsIntervalSecs == 0 {
		cfg.Run.StatsIntervalSecs = 30
	}
	if cfg.Run.ThinkTime.Max == 0 {
		cfg.Run.ThinkTime = RangeSeconds{Min: 0.5, Max: 3.0}
	}
	if cfg.Run.SessionPause.Max == 0 {
		cfg.Run.SessionPause = RangeSeconds{Min: 1.0, Max: 5.0}
	}
	if cfg.Staff.Workers == 0 {
		cfg.Staff.Workers = 2
	}
	if cfg.Staff.ThinkTime.Max == 0 {
		cfg.Staff.ThinkTime = RangeSeconds{Min: 0.5, Max: 2.0}
	}
	if cfg.Staff.QueuePollSeconds == 0 {
		cfg.Staff.QueuePollSeconds = 0.5
	}
	if cfg.Staff.PRiskApprove == 0 {
		cfg.Staff.PRiskApprove = 0.50
	}
	if cfg.Staff.PSupportAssign == 0 {
		cfg.Staff.PSupportAssign = 0.80
	}
	if cfg.Staff.PSupportResolve == 0 {
		cfg.Staff.PSupportResolve = 0.60
	}
	if cfg.Staff.PSupportAssignResolveBranch == 0 {
		cfg.Staff.PSupportAssignResolveBranch = 0.70
	}
	if cfg.Staff.PSupportClassifyEscalateBranch == 0 {
		cfg.Staff.PSupportClassifyEscalateBranch = 0.15
	}
	if cfg.Staff.PSupportClassifyCloseBranch == 0 {
		cfg.Staff.PSupportClassifyCloseBranch = 0.15
	}
	// Seat preferences are applied by the seat assignment request.
	if cfg.Staff.PSeatPreferences == 0 {
		cfg.Staff.PSeatPreferences = 0.03
	}
	// wallet_promotion.* drives the purchase-journey issue -> reserve -> redeem
	// branch. Without these defaults a missing/renamed key decodes to 0 and the
	// branch never fires -- indistinguishable from the knob not being wired at
	// all. These are the values the previous implementation defaulted to.
	if cfg.Wallet.PPurchaseReserveRedeem == 0 {
		cfg.Wallet.PPurchaseReserveRedeem = 0.02
	}
	if cfg.Wallet.PurchaseBenefitMinorUnits == 0 {
		cfg.Wallet.PurchaseBenefitMinorUnits = 100
	}
	if cfg.Polling.Attempts == 0 {
		cfg.Polling.Attempts = 20
	}
	if cfg.Polling.IntervalSeconds == 0 {
		cfg.Polling.IntervalSeconds = 4
	}
	if cfg.Bootstrap.ServicesPerDate == 0 {
		cfg.Bootstrap.ServicesPerDate = 3
	}
	if cfg.Bootstrap.ServiceNumBase == 0 {
		cfg.Bootstrap.ServiceNumBase = 5000
	}
	if cfg.Bootstrap.DepartureWindow.FromDays == 0 && cfg.Bootstrap.DepartureWindow.ToDays == 0 {
		cfg.Bootstrap.DepartureWindow = DepartureWindow{FromDays: 7, ToDays: 21}
	}
	applyEligibilityFareDefaults(&cfg.Bootstrap.EligibilityFares)
	if cfg.Scalper.Workers == 0 {
		cfg.Scalper.Workers = 3
	}
	if cfg.Scalper.AccountsPerWorker == 0 {
		cfg.Scalper.AccountsPerWorker = 5
	}
	if cfg.Scalper.TargetSegments == 0 {
		cfg.Scalper.TargetSegments = 2
	}
	if cfg.Scalper.PurchaseBatchSize == 0 {
		cfg.Scalper.PurchaseBatchSize = 4
	}
	if cfg.Defaults == nil {
		cfg.Defaults = map[string]interface{}{"currency": "CNY"}
	}
	// recording.* -- per-request record file. Only the path is mandatory when
	// enabled; the rest get operational defaults so a bare `enabled: true`
	// plus a path is a working configuration.
	if cfg.Recording.BufferRecords <= 0 {
		cfg.Recording.BufferRecords = 65536
	}
	if cfg.Recording.FlushIntervalSeconds <= 0 {
		cfg.Recording.FlushIntervalSeconds = 2
	}
	if cfg.Recording.MaxFileMegabytes == 0 {
		cfg.Recording.MaxFileMegabytes = 512
	}
	if cfg.Journey == nil {
		cfg.Journey = map[string]float64{
			"browse": 30, "purchase": 40, "refund": 8, "change": 5,
			"fulfillment": 7, "support": 4, "legacy": 6, "ride": 5,
			"disruption": 1, "transfer": 1, "loyalty": 8, "insurance": 6,
			"group_booking": 5, "corporate": 5, "campaign": 4,
		}
	}
}

// applyEligibilityFareDefaults fills the eligibility rule set with values that
// reproduce the rail-standard price exactly, so an ineligible purchase costs
// the same under either product code and the discount is the only difference.
//
// A zero amount is not a usable setting for any of these: a rule set with a
// zero base fare prices every trip at nothing, and a zero refund_fee is a rule
// post-sales would read as a free refund. Absent therefore means the
// rail-standard value rather than zero.
func applyEligibilityFareDefaults(ef *EligibilityFaresConfig) {
	if ef.ProductCode == "" {
		ef.ProductCode = "rail-eligible"
	}
	if ef.VersionPrefix == "" {
		ef.VersionPrefix = "loadgen-eligible"
	}
	if ef.SupplierID == "" {
		ef.SupplierID = "supplier-loadgen-eligible"
	}
	if ef.ContractID == "" {
		ef.ContractID = "contract-loadgen-eligible"
	}
	if ef.BaseFareMinor <= 0 {
		ef.BaseFareMinor = 10000
	}
	if ef.TaxMinor <= 0 {
		ef.TaxMinor = 750
	}
	if ef.RefundFeeMinor <= 0 {
		ef.RefundFeeMinor = 2000
	}
	if ef.ChangeFeeMinor <= 0 {
		ef.ChangeFeeMinor = 1500
	}
	if len(ef.Discounts) == 0 {
		ef.Discounts = map[string]int{"STUDENT": 2500, "CHILD": 5000, "MILITARY_DISABLED": 5000}
	}
	if ef.AnnualUsageLimit <= 0 {
		ef.AnnualUsageLimit = 1000
	}
	if ef.ValidityDays <= 0 {
		ef.ValidityDays = 365
	}
}

// BehaviorFloat extracts a float64 from the behavior map with a fallback.
func (c *Config) BehaviorFloat(key string, fallback float64) float64 {
	if c.Behavior == nil {
		return fallback
	}
	v, ok := c.Behavior[key]
	if !ok {
		return fallback
	}
	switch t := v.(type) {
	case float64:
		return t
	case int:
		return float64(t)
	default:
		return fallback
	}
}

// BehaviorMap extracts a map[string]float64 from behavior.
func (c *Config) BehaviorMap(key string, fallback map[string]float64) map[string]float64 {
	if c.Behavior == nil {
		return fallback
	}
	v, ok := c.Behavior[key]
	if !ok {
		return fallback
	}
	switch m := v.(type) {
	case map[string]interface{}:
		out := make(map[string]float64, len(m))
		for k, val := range m {
			switch n := val.(type) {
			case float64:
				out[k] = n
			case int:
				out[k] = float64(n)
			}
		}
		return out
	default:
		return fallback
	}
}

// DefaultInt returns an int from the defaults map.
func (c *Config) DefaultInt(key string, fallback int) int {
	if c.Defaults == nil {
		return fallback
	}
	v, ok := c.Defaults[key]
	if !ok {
		return fallback
	}
	switch t := v.(type) {
	case int:
		return t
	case float64:
		return int(t)
	default:
		return fallback
	}
}

// LongTailEnabled reports whether the long_tail read-probe section is active.
// Absent means enabled: the section is opt-out, matching the deployed config's
// documented "Disable with enabled: false" contract.
func (c *Config) LongTailEnabled() bool {
	if c.LongTail == nil {
		return true
	}
	v, ok := c.LongTail["enabled"]
	if !ok {
		return true
	}
	b, isBool := v.(bool)
	if !isBool {
		return true
	}
	return b
}

// LongTailFloat extracts a probability from the long_tail map with a fallback.
// The fallbacks passed by callers are the previous implementation's tuned
// defaults, so a key that fails to decode degrades to the historical rate
// rather than silently to zero.
func (c *Config) LongTailFloat(key string, fallback float64) float64 {
	if c.LongTail == nil {
		return fallback
	}
	v, ok := c.LongTail[key]
	if !ok {
		return fallback
	}
	switch t := v.(type) {
	case float64:
		return t
	case int:
		return float64(t)
	default:
		return fallback
	}
}

// Currency returns the configured currency.
func (c *Config) Currency() string {
	if c.Defaults == nil {
		return "CNY"
	}
	v, ok := c.Defaults["currency"]
	if !ok {
		return "CNY"
	}
	s, _ := v.(string)
	if s == "" {
		return "CNY"
	}
	return s
}
