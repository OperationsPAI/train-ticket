package main

import (
	"crypto/rand"
	"encoding/binary"
	"fmt"
	mrand "math/rand"
	"strings"
	"time"
)

// UUID7 generates a time-ordered UUID (version 7 compatible).
func UUID7() string {
	ts := time.Now().UnixMilli()
	var b [16]byte
	binary.BigEndian.PutUint64(b[0:8], uint64(ts)<<16)
	var randomBytes [10]byte
	_, _ = rand.Read(randomBytes[:])
	copy(b[6:], randomBytes[:])
	// Set version 7
	b[6] = (b[6] & 0x0F) | 0x70
	// Set variant
	b[8] = (b[8] & 0x3F) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// opsCodeSuffix returns 8 uppercase hex chars drawn from the RANDOM tail of a
// UUID7, for use in short business codes that services index uniquely.
//
// Never front-slice UUID7() for this. The leading hex digits are the
// millisecond timestamp, so UUID7()[:8] is identical for ~65s and UUID7()[:5]
// for ~3 days; such a "unique" code collides against a UNIQUE index almost
// every time. The trailing group is 48 bits of randomness.
// Takes 8 uppercase hex chars from the random tail, never the leading
// timestamp bytes (those are constant for ~65s and would collide).
func opsCodeSuffix() string {
	u := UUID7()
	tail := u[strings.LastIndex(u, "-")+1:]
	return strings.ToUpper(tail[:8])
}

// NowISO returns the current time in ISO 8601 format (UTC).
func NowISO() string {
	return time.Now().UTC().Format("2006-01-02T15:04:05Z")
}

// ISO formats a time value as ISO 8601 UTC.
func ISO(t time.Time) string {
	return t.UTC().Format("2006-01-02T15:04:05Z")
}

// WeightedChoice selects a key from a map of weights.
func WeightedChoice(rng *mrand.Rand, weights map[string]float64) string {
	total := 0.0
	type entry struct {
		key    string
		weight float64
	}
	var items []entry
	for k, w := range weights {
		if w > 0 {
			items = append(items, entry{k, w})
			total += w
		}
	}
	if len(items) == 0 {
		for k := range weights {
			return k
		}
		return ""
	}
	x := rng.Float64() * total
	for _, item := range items {
		x -= item.weight
		if x <= 0 {
			return item.key
		}
	}
	return items[len(items)-1].key
}

var givenNames = []string{"Wei", "Fang", "Min", "Jing", "Lei", "Yan", "Hao", "Xin", "Tao", "Mei"}
var familyNames = []string{"Zhang", "Wang", "Li", "Zhao", "Chen", "Liu", "Yang", "Huang", "Zhou", "Wu"}

// RandName generates a random Chinese name pair.
func RandName(rng *mrand.Rand) (string, string) {
	given := givenNames[rng.Intn(len(givenNames))]
	family := familyNames[rng.Intn(len(familyNames))]
	suffix := make([]byte, 4)
	for i := range suffix {
		suffix[i] = byte('a' + rng.Intn(26))
	}
	return given, family + "-" + string(suffix)
}

// getString safely extracts a string from a map.
func getString(m map[string]interface{}, key string) string {
	if m == nil {
		return ""
	}
	v, ok := m[key]
	if !ok {
		return ""
	}
	s, _ := v.(string)
	return s
}

// getInt safely extracts an int from a map.
func getInt(m map[string]interface{}, key string, fallback int) int {
	if m == nil {
		return fallback
	}
	v, ok := m[key]
	if !ok {
		return fallback
	}
	switch t := v.(type) {
	case float64:
		return int(t)
	case int:
		return t
	default:
		return fallback
	}
}

// getNestedInt gets a nested int value from map["key"]["subkey"].
func getNestedInt(m map[string]interface{}, key, subkey string, fallback int) int {
	if m == nil {
		return fallback
	}
	inner, ok := m[key].(map[string]interface{})
	if !ok {
		return fallback
	}
	return getInt(inner, subkey, fallback)
}

// ComputeDepartureDates computes departure dates from config.
func ComputeDepartureDates(cfg *Config) []string {
	bs := cfg.Bootstrap
	if len(bs.DepartureDates) > 0 {
		return bs.DepartureDates
	}
	fromDays := bs.DepartureWindow.FromDays
	if fromDays == 0 {
		fromDays = 7
	}
	toDays := bs.DepartureWindow.ToDays
	if toDays == 0 {
		toDays = 21
	}
	today := time.Now().UTC().Truncate(24 * time.Hour)
	var dates []string
	for d := fromDays; d <= toDays; d++ {
		dates = append(dates, today.Add(time.Duration(d)*24*time.Hour).Format("2006-01-02"))
	}
	return dates
}

// ConvertTemplate converts the config's {service} placeholder to Go %s.
func ConvertTemplate(template string) string {
	return strings.Replace(template, "{service}", "%s", 1)
}
