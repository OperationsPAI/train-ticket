package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Per-request outcome recording (issue #420).
//
// The aggregate Stats snapshot answers only the questions that were asked
// before the run started: a rolling 5000-sample latency buffer and a
// service:status counter cannot be re-sliced by endpoint, re-quantiled over a
// sub-window, or joined to a trace. This writes one JSON Lines record per
// client HTTP request so a completed run can be re-analysed offline.
//
// DESIGN CONSTRAINT: recording must not change the offered load.
//
// The request goroutine does exactly three things for a record: fill a
// value-typed struct out of data it already has in registers, and attempt one
// NON-BLOCKING channel send. It never touches the filesystem, never formats a
// timestamp, never marshals JSON, and never takes a lock that a slow disk
// could be holding. Everything expensive -- timestamp formatting, route
// templating, JSON encoding, buffered writes, fsync-by-the-OS, rotation --
// happens on a single dedicated writer goroutine.
//
// The send is non-blocking on purpose. A blocking send (or a mutex around the
// file) would couple request latency to disk latency: one stalled write would
// back-pressure into the journey goroutines and silently reduce offered RPS,
// which is exactly the failure this constraint forbids. If the writer cannot
// keep up, records are DROPPED and counted, and the drop count is reported --
// a visible, quantified gap in the record file is strictly better than an
// invisible dent in the load profile.

// ReqRecord is the in-flight form of one per-request record. It is a value
// type with no pointers so that handing it to the writer goroutine costs a
// single channel copy and produces no garbage on the request path.
//
// Fields hold raw data only. Anything that needs formatting (the timestamp)
// or derivation (the route template) is stored raw here and computed on the
// writer goroutine.
type ReqRecord struct {
	StartUnixNano int64   // request start, from time.Time.UnixNano()
	Chain         string  // journey / chain name (see WithChain)
	Step          string  // call-site step label
	Service       string  // target service
	Method        string  // HTTP method
	Path          string  // raw request path, including query string
	Status        int     // HTTP status; 0 when no response was received
	LatencyMs     float64 // wall time from just before Do() to just after
	TransportErr  string  // "" when a status was received, else a short class
	TraceID       string  // 32 hex chars of the traceparent that was sent
	SpanID        string  // 16 hex chars of the client span id
	Sampled       bool    // the traceparent sampled flag as sent
}

// recordLine is the on-disk JSON Lines schema. Field order here is the field
// order in the file. Every field is always present (no omitempty) so the file
// is a stable rectangle: it loads directly into pandas/DuckDB/jq without
// per-row key checks, and an empty `error` is meaningful ("got a status").
type recordLine struct {
	TS        string  `json:"ts"`         // RFC3339 with nanoseconds, UTC
	Chain     string  `json:"chain"`      // journey/chain that issued the request
	Step      string  `json:"step"`       // step label within the chain
	Service   string  `json:"service"`    // target service
	Method    string  `json:"method"`     // HTTP method
	Route     string  `json:"route"`      // route template, ids replaced by {id}
	Path      string  `json:"path"`       // raw path as requested
	Status    int     `json:"status"`     // 0 == never got a status
	LatencyMs float64 `json:"latency_ms"` // milliseconds, 3 decimals
	Error     string  `json:"error"`      // transport-error marker; "" if none
	TraceID   string  `json:"trace_id"`   // joins to server spans in Jaeger
	SpanID    string  `json:"span_id"`    // client-side span id
	Sampled   bool    `json:"sampled"`    // traceparent sampled flag as sent
}

// Recorder owns the record file and its writer goroutine. All methods are
// safe on a nil *Recorder, which is the "recording disabled" state, so
// callers never need a nil check.
type Recorder struct {
	ch   chan ReqRecord
	done chan struct{}

	path         string
	maxBytes     int64
	flushEvery   time.Duration
	sampledRatio float64

	written atomic.Int64
	dropped atomic.Int64

	closeOnce sync.Once
}

// NewRecorder opens the record file and starts the writer goroutine. It
// returns (nil, nil) when recording is disabled, so a nil Recorder is a valid
// and expected result.
func NewRecorder(cfg *Config) (*Recorder, error) {
	rc := cfg.Recording
	if !rc.Enabled {
		return nil, nil
	}
	if strings.TrimSpace(rc.Path) == "" {
		return nil, errors.New("recording.enabled is true but recording.path is empty")
	}

	// The path is configuration, never a constant: in the cluster it points
	// under the /data volume declared in deploy/k8s/loadgen.yaml, and locally
	// it points wherever the developer asked.
	if dir := filepath.Dir(rc.Path); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, fmt.Errorf("recording: create %s: %w", dir, err)
		}
	}
	// O_APPEND, not O_TRUNC: the record file outlives the run, and a
	// container restart must not erase the previous container's records.
	f, err := os.OpenFile(rc.Path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return nil, fmt.Errorf("recording: open %s: %w", rc.Path, err)
	}

	r := &Recorder{
		ch:           make(chan ReqRecord, rc.BufferRecords),
		done:         make(chan struct{}),
		path:         rc.Path,
		maxBytes:     int64(rc.MaxFileMegabytes) * 1024 * 1024,
		flushEvery:   time.Duration(rc.FlushIntervalSeconds * float64(time.Second)),
		sampledRatio: rc.SampledRatio(),
	}
	go r.writeLoop(f)
	return r, nil
}

// Enabled reports whether records are being written.
func (r *Recorder) Enabled() bool { return r != nil }

// Path returns the record file path ("" when disabled).
func (r *Recorder) Path() string {
	if r == nil {
		return ""
	}
	return r.path
}

// Record hands one record to the writer goroutine.
//
// This is the ONLY recorder function that runs on a request goroutine, and it
// is deliberately trivial: a non-blocking channel send. It cannot block, so
// the offered load is independent of how fast (or whether) the disk accepts
// writes. A full buffer costs a counter increment, not a stalled request.
func (r *Recorder) Record(rec ReqRecord) {
	if r == nil {
		return
	}
	select {
	case r.ch <- rec:
	default:
		r.dropped.Add(1)
	}
}

// TraceSampled returns the sampled flag to put on the next traceparent.
// Cheap enough (one PRNG draw, and none at all in the default all-sampled
// case) to sit on the request path.
func (r *Recorder) TraceSampled() bool {
	if r == nil {
		return true
	}
	if r.sampledRatio >= 1 {
		return true
	}
	if r.sampledRatio <= 0 {
		return false
	}
	return rand.Float64() < r.sampledRatio
}

// Counters returns records written and records dropped for want of buffer.
func (r *Recorder) Counters() (written, dropped int64) {
	if r == nil {
		return 0, 0
	}
	return r.written.Load(), r.dropped.Load()
}

// Close drains the buffer, flushes and closes the file. Safe to call twice.
func (r *Recorder) Close() {
	if r == nil {
		return
	}
	r.closeOnce.Do(func() {
		close(r.ch)
		<-r.done
	})
}

// writeLoop is the single consumer. Everything costly lives here.
func (r *Recorder) writeLoop(f *os.File) {
	defer close(r.done)

	// 256 KiB of userspace buffering: at the ~1-5k req/s this generator
	// produces, a syscall per record would be the dominant cost of the
	// feature. This turns thousands of small writes into a handful.
	bw := newSizedWriter(f, 256*1024)

	size, _ := f.Seek(0, io.SeekEnd) // current end offset, for rotation accounting

	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)

	flushEvery := r.flushEvery
	if flushEvery <= 0 {
		flushEvery = 2 * time.Second
	}
	ticker := time.NewTicker(flushEvery)
	defer ticker.Stop()

	emit := func(rec ReqRecord) {
		buf.Reset()
		line := recordLine{
			TS:        time.Unix(0, rec.StartUnixNano).UTC().Format(time.RFC3339Nano),
			Chain:     rec.Chain,
			Step:      rec.Step,
			Service:   rec.Service,
			Method:    rec.Method,
			Route:     RouteTemplate(rec.Path),
			Path:      rec.Path,
			Status:    rec.Status,
			LatencyMs: roundTo(rec.LatencyMs, 3),
			Error:     rec.TransportErr,
			TraceID:   rec.TraceID,
			SpanID:    rec.SpanID,
			Sampled:   rec.Sampled,
		}
		if err := enc.Encode(&line); err != nil {
			return
		}
		n, err := bw.Write(buf.Bytes())
		size += int64(n)
		if err == nil {
			r.written.Add(1)
		}
		// Bounded disk: the deployed generator runs indefinitely against an
		// emptyDir, so an uncapped file is a node-disk-pressure incident
		// waiting to happen. Keep at most the current file plus one previous.
		if r.maxBytes > 0 && size >= r.maxBytes {
			if nf, err := rotate(bw, r.path); err == nil {
				bw.Reset(nf)
				size = 0
			} else if nf != nil {
				// Rotation failed but the file was reopened: keep recording
				// into it rather than writing into a closed descriptor for
				// the rest of the run.
				bw.Reset(nf)
				size, _ = nf.Seek(0, io.SeekEnd)
			}
		}
	}

	for {
		select {
		case rec, ok := <-r.ch:
			if !ok {
				_ = bw.Flush()
				_ = bw.Close()
				return
			}
			emit(rec)
		case <-ticker.C:
			// Periodic flush is what makes the file readable *during* a run
			// and survivable across a SIGKILL, not just at clean shutdown.
			_ = bw.Flush()
		}
	}
}

// rotate flushes and closes the current file, moves it aside to <path>.1
// (replacing any previous .1) and opens a fresh <path>.
//
// On failure it still tries to hand back a usable file: the current file has
// already been closed by then, so returning nothing would silently kill
// recording for the rest of the run. A non-nil *os.File with a non-nil error
// means "rotation did not happen, but keep writing here".
func rotate(bw *sizedWriter, path string) (*os.File, error) {
	if err := bw.Flush(); err != nil {
		return nil, err
	}
	if err := bw.Close(); err != nil {
		return nil, err
	}
	if err := os.Rename(path, path+".1"); err != nil {
		// Reopen the original so recording continues, unrotated.
		f, reopenErr := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
		if reopenErr != nil {
			return nil, err
		}
		return f, err
	}
	return os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
}

// sizedWriter is a bufio.Writer that also owns the underlying file, so the
// writer goroutine can flush, close and swap files in one place.
type sizedWriter struct {
	bw *bufio.Writer
	f  *os.File
}

func newSizedWriter(f *os.File, size int) *sizedWriter {
	return &sizedWriter{bw: bufio.NewWriterSize(f, size), f: f}
}

func (w *sizedWriter) Write(p []byte) (int, error) { return w.bw.Write(p) }
func (w *sizedWriter) Flush() error                { return w.bw.Flush() }
func (w *sizedWriter) Close() error                { return w.f.Close() }

// Reset points the writer at a new file, discarding nothing (the caller
// flushes first).
func (w *sizedWriter) Reset(f *os.File) {
	w.f = f
	w.bw.Reset(f)
}

func roundTo(v float64, places int) float64 {
	p := 1.0
	for i := 0; i < places; i++ {
		p *= 10
	}
	rounded := float64(int64(v*p+0.5)) / p
	return rounded
}

// RouteTemplate collapses a concrete request path into a route template by
// replacing identifier-looking segments with {id} and dropping the query
// string. Without this, grouping by endpoint is impossible: every
// /api/v1/orders/<uuid> is its own key.
//
// Runs on the writer goroutine, never on a request goroutine.
func RouteTemplate(path string) string {
	if i := strings.IndexByte(path, '?'); i >= 0 {
		path = path[:i]
	}
	if path == "" {
		return ""
	}
	segs := strings.Split(path, "/")
	for i, s := range segs {
		if looksLikeID(s) {
			segs[i] = "{id}"
		}
	}
	return strings.Join(segs, "/")
}

// looksLikeID reports whether a path segment is a concrete identifier rather
// than a fixed route element. Deliberately conservative: it only fires on
// segments that contain a digit, so word-only segments such as "orders" or
// "payment-channel" are never collapsed.
func looksLikeID(s string) bool {
	if len(s) < 2 {
		return false
	}
	hasDigit := false
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c >= '0' && c <= '9':
			hasDigit = true
		case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c == '-', c == '_':
			// allowed
		default:
			return false
		}
	}
	if !hasDigit {
		return false
	}
	// A UUID, a numeric id, or a hex/base-ish token. Short mixed segments like
	// "v1" or "s2" are route elements, not ids.
	if len(s) >= 8 {
		return true
	}
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true // all digits
}

// ---------------------------------------------------------------------------
// chain labelling
// ---------------------------------------------------------------------------

type chainCtxKey struct{}

// WithChain tags a context with the journey / chain name that owns the
// requests made under it. The ApiClient is a single shared object, so the
// chain name cannot live on the client; it rides the context that already
// threads through every journey, staff action and probe.
func WithChain(ctx context.Context, name string) context.Context {
	if ctx == nil {
		return nil
	}
	return context.WithValue(ctx, chainCtxKey{}, name)
}

// ChainFromContext returns the chain name, or "unlabelled".
func ChainFromContext(ctx context.Context) string {
	if ctx == nil {
		return "unlabelled"
	}
	if v, ok := ctx.Value(chainCtxKey{}).(string); ok && v != "" {
		return v
	}
	return "unlabelled"
}

// ---------------------------------------------------------------------------
// transport error classification
// ---------------------------------------------------------------------------

// classifyTransportError maps a client-side failure to a short, low-cardinality
// marker. This is the "never got a status" marker in the record: a row with
// status 0 always carries a non-empty error here.
//
// Only reached on the failure path, so the errors.Is walks cost nothing in the
// common case.
func classifyTransportError(err error) string {
	if err == nil {
		return ""
	}
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		return "timeout"
	case errors.Is(err, context.Canceled):
		return "canceled"
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return "timeout"
	}
	var oe *net.OpError
	if errors.As(err, &oe) {
		switch oe.Op {
		case "dial":
			return "connect_failed"
		case "read":
			return "read_failed"
		case "write":
			return "write_failed"
		}
	}
	if strings.Contains(err.Error(), "connection reset") {
		return "connection_reset"
	}
	return "transport"
}
