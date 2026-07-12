package ids

import (
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"strings"
	"time"
)

func NewUUIDv7() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	binary.BigEndian.PutUint64(b[0:8], uint64(time.Now().UTC().UnixMilli())<<16)
	b[6] = (b[6] & 0x0f) | 0x70
	b[8] = (b[8] & 0x3f) | 0x80
	return hex.EncodeToString(b[0:4]) + "-" + hex.EncodeToString(b[4:6]) + "-" + hex.EncodeToString(b[6:8]) + "-" + hex.EncodeToString(b[8:10]) + "-" + hex.EncodeToString(b[10:16])
}

func NewPrefixed(prefix string) string { return strings.TrimSpace(prefix) + "-" + NewUUIDv7() }
func NewEventID() string               { return NewPrefixed("evt") }
func NewCommandID() string             { return NewPrefixed("cmd") }
func NewCorrelationID() string         { return NewPrefixed("corr") }

func ValidUUIDv7(value string) bool {
	value = strings.TrimSpace(value)
	if len(value) != 36 || value[14] != '7' {
		return false
	}
	for i, ch := range value {
		switch i {
		case 8, 13, 18, 23:
			if ch != '-' {
				return false
			}
		default:
			if !((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')) {
				return false
			}
		}
	}
	return true
}

func ValidPrefixedUUIDv7(value, prefix string) bool {
	prefix = strings.TrimSpace(prefix) + "-"
	return strings.HasPrefix(strings.TrimSpace(value), prefix) && ValidUUIDv7(strings.TrimPrefix(strings.TrimSpace(value), prefix))
}

func CanonicalCorrelationID(value string) string {
	value = strings.TrimSpace(value)
	if strings.HasPrefix(value, "corr-") {
		return value
	}
	if ValidUUIDv7(value) {
		return "corr-" + value
	}
	return NewCorrelationID()
}

func CanonicalCausationID(value string) string {
	value = strings.TrimSpace(value)
	if strings.HasPrefix(value, "cmd-") || strings.HasPrefix(value, "evt-") {
		return value
	}
	if ValidUUIDv7(value) {
		return "cmd-" + value
	}
	return NewCommandID()
}

func FormatUTC(t time.Time) string { return t.UTC().Format("2006-01-02T15:04:05.000Z") }
