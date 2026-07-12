package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
)

type Money struct {
	Currency   string `json:"currency"`
	MinorUnits int64  `json:"minorUnits"`
}

func (m Money) ValidatePositive() error {
	if strings.TrimSpace(m.Currency) == "" {
		return fmt.Errorf("currency is required")
	}
	if m.MinorUnits <= 0 {
		return fmt.Errorf("minorUnits must be positive")
	}
	return nil
}
func (m Money) Zero() Money { return Money{Currency: m.Currency, MinorUnits: 0} }

type FaultSeed struct {
	SeedVersion      string     `json:"seedVersion"`
	ScenarioCode     string     `json:"scenarioCode"`
	SeedMaterialHash string     `json:"seedMaterialHash"`
	EffectiveFrom    *time.Time `json:"effectiveFrom,omitempty"`
	EffectiveUntil   *time.Time `json:"effectiveUntil,omitempty"`
}

func (f *FaultSeed) Ref() string {
	if f == nil {
		return ""
	}
	return f.SeedVersion + ":" + f.ScenarioCode + ":" + f.SeedMaterialHash
}
func (f *FaultSeed) Validate() error {
	if f == nil {
		return nil
	}
	if strings.TrimSpace(f.SeedVersion) == "" || strings.TrimSpace(f.ScenarioCode) == "" || strings.TrimSpace(f.SeedMaterialHash) == "" {
		return fmt.Errorf("faultSeed seedVersion, scenarioCode and seedMaterialHash are required")
	}
	if !validScenario(f.ScenarioCode) {
		return fmt.Errorf("unsupported scenarioCode")
	}
	return nil
}

type ChannelAttempt struct {
	AttemptNo          int       `json:"attemptNo"`
	AttemptType        string    `json:"attemptType"`
	RequestFingerprint string    `json:"requestFingerprint"`
	SimStatus          string    `json:"simStatus"`
	ProviderErrorCode  string    `json:"providerErrorCode,omitempty"`
	Retryable          bool      `json:"retryable"`
	AttemptedAt        time.Time `json:"attemptedAt"`
}

type Event struct {
	EventType     string
	Payload       any
	OccurredAt    time.Time
	CausationID   string
	CorrelationID string
	AggregateID   string
	Version       int64
	MaterialHash  string
}

const Producer = "payment-channel"
const (
	ChannelAlipay   = "ALIPAY_SIM"
	ChannelWechat   = "WECHAT_SIM"
	ChannelUnion    = "UNIONPAY_SIM"
	StatusCreated   = "CREATED"
	StatusSubmitted = "SUBMITTED"
	StatusAccepted  = "ACCEPTED"
	StatusSucceeded = "SUCCEEDED"
	StatusFailed    = "FAILED"
	StatusMissed    = "MISSED"
)

func ValidChannel(c string) bool {
	switch c {
	case ChannelAlipay, ChannelWechat, ChannelUnion:
		return true
	}
	return false
}
func validScenario(c string) bool {
	switch c {
	case "NORMAL", "MISSED_ORDER", "MISSED_REFUND", "AMOUNT_MISMATCH", "STATUS_MISMATCH", "DUPLICATE_LINE", "REFUND_LAG", "STATEMENT_DELAY":
		return true
	}
	return false
}
func IsTerminal(s string) bool { return s == StatusSucceeded || s == StatusFailed || s == StatusMissed }

func NewID(prefix string) string    { return ids.NewPrefixed(prefix) }
func FormatTime(t time.Time) string { return ids.FormatUTC(t) }
func Hash(parts ...any) string {
	b, _ := json.Marshal(parts)
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
func DeterministicTxn(prefix, id string) string {
	h := Hash(prefix, id)
	if len(h) > 24 {
		h = h[:24]
	}
	return prefix + "-" + h
}

func DeterministicEventID(eventType, aggregateID string, version int64, materialHash string) string {
	seed := fmt.Sprintf("payment-channel:%s:%s:%d:%s", eventType, aggregateID, version, materialHash)
	sum := sha256.Sum256([]byte(seed))
	b := sum[:16]
	b[6] = (b[6] & 0x0f) | 0x70
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("evt-%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
