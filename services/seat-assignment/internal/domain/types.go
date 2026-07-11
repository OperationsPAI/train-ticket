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

const Producer = "seat-assignment"

const (
	ClassBusiness = "BUSINESS_CLASS"
	ClassFirst    = "FIRST_CLASS"
	ClassSecond   = "SECOND_CLASS"
)

const (
	PositionWindow = "WINDOW"
	PositionAisle  = "AISLE"
	PositionMiddle = "MIDDLE"
)

const (
	PreferenceWindow        = "WINDOW"
	PreferenceAisle         = "AISLE"
	PreferenceTogether      = "TOGETHER"
	PreferenceQuietCar      = "QUIET_CAR"
	PreferenceForwardFacing = "FORWARD_FACING"
	PreferenceNone          = "NO_PREFERENCE"
)

const (
	StatusHeld      = "HELD"
	StatusConfirmed = "CONFIRMED"
	StatusReleased  = "RELEASED"
)

const HoldDuration = 10 * time.Minute

var ErrSeatUnavailable = fmt.Errorf("seat unavailable")
var ErrInvalidTransition = fmt.Errorf("invalid seat assignment transition")

func NewID(prefix string) string    { return ids.NewPrefixed(prefix) }
func Trim(v string) string          { return strings.TrimSpace(v) }
func FormatDate(t time.Time) string { return t.UTC().Format("2006-01-02") }
func Hash(parts ...any) string {
	b, _ := json.Marshal(parts)
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
