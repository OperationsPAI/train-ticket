package main

import (
	"encoding/binary"
	"encoding/hex"
	"math/rand/v2"
	"strconv"
	"strings"
)

// W3C trace-context propagation.
//
// The loadgen is the ORIGIN of every request it makes: there is no inbound
// traceparent to continue, so it mints a fresh trace per request. That id is
// both sent on the wire (`traceparent` header) and written to the per-request
// record file, which is what makes a client-observed slow/failed request
// joinable to the server spans in Jaeger.
//
// Sampling flag: the loadgen sets the `sampled` bit by default (flags = 01).
// A W3C-compliant SDK honours the caller's decision, so flags = 00 means the
// server-side span is likely never recorded -- the trace id in the record file
// would then point at nothing in Jaeger, which defeats the whole point of
// recording it. The sampling decision therefore belongs at the origin, and the
// origin says "yes" unless an operator lowers recording.trace_sampled_ratio to
// shed backend volume. When the ratio is < 1 the per-request flag is written to
// the record as `sampled`, so an analysis can tell "no span exists" apart from
// "the span is missing".

// traceContext is one originated W3C trace context.
type traceContext struct {
	TraceID string // 32 lowercase hex chars
	SpanID  string // 16 lowercase hex chars
	Sampled bool
	Header  string // the traceparent header value
}

// newTraceContext mints a spec-compliant traceparent:
//
//	version "00" - trace-id (16 bytes) - parent-id (8 bytes) - flags
//
// Randomness comes from math/rand/v2's top-level generator, which is seeded
// per process and is lock-free per-P. Trace ids need to be unique, not
// unpredictable, so crypto/rand's cost (and its global lock on some
// platforms) is not warranted on the request path.
func newTraceContext(sampled bool) traceContext {
	var b [24]byte
	hi, lo := rand.Uint64(), rand.Uint64()
	// An all-zero trace-id or span-id is invalid per the spec and is dropped
	// by conformant receivers, so force a non-zero value.
	if hi == 0 && lo == 0 {
		lo = 1
	}
	span := rand.Uint64()
	if span == 0 {
		span = 1
	}
	binary.BigEndian.PutUint64(b[0:8], hi)
	binary.BigEndian.PutUint64(b[8:16], lo)
	binary.BigEndian.PutUint64(b[16:24], span)

	var hexBuf [48]byte
	hex.Encode(hexBuf[:], b[:])
	traceID := string(hexBuf[0:32])
	spanID := string(hexBuf[32:48])

	flags := "00"
	if sampled {
		flags = "01"
	}

	var sb strings.Builder
	sb.Grow(55)
	sb.WriteString("00-")
	sb.WriteString(traceID)
	sb.WriteByte('-')
	sb.WriteString(spanID)
	sb.WriteByte('-')
	sb.WriteString(flags)

	return traceContext{TraceID: traceID, SpanID: spanID, Sampled: sampled, Header: sb.String()}
}

// parseTraceparent reads back a traceparent that a caller (or an instrumented
// client) already put on the request, so the recorded trace id is the one
// actually on the wire rather than a second one this code invented.
// A malformed header yields a zero traceContext with the header preserved.
func parseTraceparent(h string) traceContext {
	tc := traceContext{Header: h}
	parts := strings.Split(h, "-")
	if len(parts) != 4 || len(parts[1]) != 32 || len(parts[2]) != 16 {
		return tc
	}
	if !isLowerHex(parts[1]) || !isLowerHex(parts[2]) {
		return tc
	}
	tc.TraceID = parts[1]
	tc.SpanID = parts[2]
	// Sampled is the low bit of the flags byte.
	if len(parts[3]) == 2 {
		if v, err := strconv.ParseUint(parts[3], 16, 8); err == nil {
			tc.Sampled = v&0x01 != 0
		}
	}
	return tc
}

func isLowerHex(s string) bool {
	for i := 0; i < len(s); i++ {
		c := s[i]
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}
