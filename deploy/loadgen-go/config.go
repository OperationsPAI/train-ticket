package main

import (
	"os"

	"gopkg.in/yaml.v3"
)

// Config mirrors the YAML structure of the Python loadgen config.yaml.
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
}

type BootstrapConfig struct {
	Enabled          bool             `yaml:"enabled"`
	Cities           []CityConfig     `yaml:"cities"`
	DepartureWindow  DepartureWindow  `yaml:"departure_window"`
	DepartureDates   []string         `yaml:"departure_dates"`
	ServicesPerDate  int              `yaml:"services_per_date"`
	ServiceNumBase   int              `yaml:"service_number_base"`
	MinRoutes        int              `yaml:"min_routes"`
}

type CityConfig struct {
	Name string `yaml:"name"`
	Code string `yaml:"code"`
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
	if cfg.Journey == nil {
		cfg.Journey = map[string]float64{
			"browse": 30, "purchase": 40, "refund": 8, "change": 5,
			"fulfillment": 7, "support": 4, "legacy": 6, "ride": 5,
			"disruption": 1, "transfer": 1, "loyalty": 8, "insurance": 6,
			"group_booking": 5, "corporate": 5, "campaign": 4,
		}
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
