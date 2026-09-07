package main

import (
	"context"
	"encoding/json"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

// recordingConfig returns a config pointed at a fake mesh with recording on.
func recordingConfig(t *testing.T, baseURL string) (*Config, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "requests.jsonl")
	cfg := &Config{}
	cfg.Target.BaseURLTemplate = baseURL + "/%s"
	cfg.Target.RequestTimeoutSeconds = 5
	cfg.Recording = RecordingConfig{
		Enabled: true,
		Path:    path,
		// Long flush interval: the tests below assert on *when* bytes reach
		// the file, so a background flush must not race them.
		FlushIntervalSeconds: 3600,
	}
	applyDefaults(cfg)
	return cfg, path
}

// readRecords parses the JSON Lines record file.
func readRecords(t *testing.T, path string) []recordLine {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read record file: %v", err)
	}
	var out []recordLine
	for _, line := range strings.Split(strings.TrimSpace(string(data)), "\n") {
		if line == "" {
			continue
		}
		var rl recordLine
		if err := json.Unmarshal([]byte(line), &rl); err != nil {
			t.Fatalf("record line is not valid JSON (%q): %v", line, err)
		}
		out = append(out, rl)
	}
	return out
}

// ---------------------------------------------------------------------------
// schema
// ---------------------------------------------------------------------------

// TestRecordSchemaHasEveryRequiredField pins the record schema against the
// field list issue #420 requires. A dropped or renamed field silently breaks
// every downstream analysis, and the aggregate snapshot cannot substitute for
// any of them.
func TestRecordSchemaHasEveryRequiredField(t *testing.T) {
	mesh := newFakeMesh(t, func(service, method, path string) (int, map[string]interface{}) {
		return 201, map[string]interface{}{"ok": true}
	})
	cfg, path := recordingConfig(t, mesh.srv.URL)

	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)

	ctx := WithChain(context.Background(), "purchase")
	_, _, err = api.Request(ctx, "POST", "order",
		"/api/v1/orders/0190f0ab-1111-7000-8000-000000000001/confirm?force=true",
		map[string]interface{}{"a": 1}, nil, []int{201}, "confirm-order")
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	rec.Close()

	recs := readRecords(t, path)
	if len(recs) != 1 {
		t.Fatalf("got %d records, want 1", len(recs))
	}
	r := recs[0]

	// request start timestamp
	ts, err := time.Parse(time.RFC3339Nano, r.TS)
	if err != nil {
		t.Errorf("ts %q is not RFC3339Nano: %v", r.TS, err)
	} else if time.Since(ts) > time.Minute || time.Since(ts) < -time.Minute {
		t.Errorf("ts %q is not near now", r.TS)
	}
	// journey / chain name
	if r.Chain != "purchase" {
		t.Errorf("chain = %q, want purchase", r.Chain)
	}
	if r.Step != "confirm-order" {
		t.Errorf("step = %q, want confirm-order", r.Step)
	}
	// target service
	if r.Service != "order" {
		t.Errorf("service = %q, want order", r.Service)
	}
	// HTTP method
	if r.Method != "POST" {
		t.Errorf("method = %q, want POST", r.Method)
	}
	// endpoint / route template -- the id must be collapsed and the query
	// string dropped, otherwise grouping by endpoint is impossible.
	if r.Route != "/api/v1/orders/{id}/confirm" {
		t.Errorf("route = %q, want /api/v1/orders/{id}/confirm", r.Route)
	}
	if !strings.HasPrefix(r.Path, "/api/v1/orders/0190f0ab-") {
		t.Errorf("path = %q, want the raw requested path", r.Path)
	}
	// status code
	if r.Status != 201 {
		t.Errorf("status = %d, want 201", r.Status)
	}
	// latency ms
	if r.LatencyMs <= 0 {
		t.Errorf("latency_ms = %v, want > 0", r.LatencyMs)
	}
	// transport-error marker: empty because a status WAS received
	if r.Error != "" {
		t.Errorf("error = %q, want empty for a request that got a status", r.Error)
	}
	// trace id the request carried
	if len(r.TraceID) != 32 || !isLowerHex(r.TraceID) {
		t.Errorf("trace_id = %q, want 32 lowercase hex chars", r.TraceID)
	}
	if len(r.SpanID) != 16 || !isLowerHex(r.SpanID) {
		t.Errorf("span_id = %q, want 16 lowercase hex chars", r.SpanID)
	}
	if !r.Sampled {
		t.Error("sampled = false, want true at the default trace_sampled_ratio of 1.0")
	}
}

// TestRecordedTraceIDMatchesTheHeaderOnTheWire is the whole point of
// recording a trace id: if the recorded id is not the id the services saw,
// the record file cannot be joined to Jaeger and is no better than the
// aggregate snapshot.
func TestRecordedTraceIDMatchesTheHeaderOnTheWire(t *testing.T) {
	var mu sync.Mutex
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		seen = append(seen, r.Header.Get("traceparent"))
		mu.Unlock()
		w.WriteHeader(200)
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	cfg, path := recordingConfig(t, srv.URL)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)

	const n = 20
	for i := 0; i < n; i++ {
		if _, _, err := api.Request(context.Background(), "GET", "order", "/api/v1/orders",
			nil, nil, []int{200}, "list"); err != nil {
			t.Fatalf("request %d: %v", i, err)
		}
	}
	rec.Close()

	recs := readRecords(t, path)
	if len(recs) != n {
		t.Fatalf("got %d records, want %d", len(recs), n)
	}
	mu.Lock()
	headers := append([]string(nil), seen...)
	mu.Unlock()
	if len(headers) != n {
		t.Fatalf("server saw %d requests, want %d", len(headers), n)
	}

	unique := map[string]bool{}
	for i, h := range headers {
		if h == "" {
			t.Fatalf("request %d carried no traceparent header", i)
		}
		want := "00-" + recs[i].TraceID + "-" + recs[i].SpanID + "-01"
		if h != want {
			t.Errorf("request %d: header %q, record implies %q", i, h, want)
		}
		unique[recs[i].TraceID] = true
	}
	// Each request must originate its own trace, not reuse one.
	if len(unique) != n {
		t.Errorf("%d distinct trace ids across %d requests, want all distinct", len(unique), n)
	}
}

// TestCallerSuppliedTraceparentIsReadBackNotOverwritten covers the issue's
// "or reads back the one an instrumented client generated" alternative: if a
// traceparent is already on the request, that id is what gets recorded.
func TestCallerSuppliedTraceparentIsReadBackNotOverwritten(t *testing.T) {
	const supplied = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Get("traceparent")
		w.WriteHeader(200)
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	cfg, path := recordingConfig(t, srv.URL)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)
	_, _, err = api.Request(context.Background(), "GET", "order", "/api/v1/orders",
		nil, map[string]string{"traceparent": supplied}, []int{200}, "list")
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	rec.Close()

	if got != supplied {
		t.Errorf("header on the wire = %q, want the caller's %q", got, supplied)
	}
	recs := readRecords(t, path)
	if len(recs) != 1 {
		t.Fatalf("got %d records, want 1", len(recs))
	}
	if recs[0].TraceID != "4bf92f3577b34da6a3ce929d0e0e4736" {
		t.Errorf("trace_id = %q, want the caller's trace id", recs[0].TraceID)
	}
	if recs[0].SpanID != "00f067aa0ba902b7" {
		t.Errorf("span_id = %q, want the caller's span id", recs[0].SpanID)
	}
}

// TestTransportFailureIsRecordedWithStatusZero: a request that never got a
// status is the single most interesting row in the file (it is invisible in
// the service:status counters), so it must be present and marked.
func TestTransportFailureIsRecordedWithStatusZero(t *testing.T) {
	// A closed listener address: dialling it fails without a status.
	dead := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := dead.URL
	dead.Close()

	cfg, path := recordingConfig(t, url)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)

	ctx := WithChain(context.Background(), "refund")
	if _, _, err := api.Request(ctx, "GET", "order", "/api/v1/orders",
		nil, nil, []int{200}, "list"); err == nil {
		t.Fatal("expected a transport error against a closed listener")
	}
	rec.Close()

	recs := readRecords(t, path)
	if len(recs) != 1 {
		t.Fatalf("got %d records, want 1 -- a failed request must still be recorded", len(recs))
	}
	r := recs[0]
	if r.Status != 0 {
		t.Errorf("status = %d, want 0 for a request that never got a status", r.Status)
	}
	if r.Error == "" {
		t.Error("error is empty: status 0 must always carry a transport-error marker")
	}
	if r.Chain != "refund" {
		t.Errorf("chain = %q, want refund", r.Chain)
	}
	if r.TraceID == "" {
		t.Error("trace_id is empty: a failed request is exactly the one you want to trace")
	}
}

// TestTimeoutIsClassifiedAsTimeout checks the marker distinguishes the
// failure modes rather than lumping them into one bucket.
func TestTimeoutIsClassifiedAsTimeout(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(500 * time.Millisecond)
	}))
	defer srv.Close()

	cfg, path := recordingConfig(t, srv.URL)
	cfg.Target.RequestTimeoutSeconds = 0.05
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)
	if _, _, err := api.Request(context.Background(), "GET", "order", "/slow",
		nil, nil, []int{200}, "slow"); err == nil {
		t.Fatal("expected a timeout")
	}
	rec.Close()

	recs := readRecords(t, path)
	if len(recs) != 1 {
		t.Fatalf("got %d records, want 1", len(recs))
	}
	if recs[0].Error != "timeout" {
		t.Errorf("error = %q, want timeout", recs[0].Error)
	}
}

// ---------------------------------------------------------------------------
// "recording does not change the offered load"
// ---------------------------------------------------------------------------

// TestRecordNeverBlocksAndDoesNoIOOnTheRequestPath is the structural proof of
// the load-neutrality constraint. The Recorder here has a channel and NO
// consumer goroutine and NO file at all, so if Record did any I/O, or blocked
// on a full buffer, this test could not pass.
//
// Overflow is DROPPED and counted on purpose: the alternative -- blocking the
// caller -- would convert disk pressure into reduced offered RPS, which is
// the exact failure the acceptance criterion forbids.
func TestRecordNeverBlocksAndDoesNoIOOnTheRequestPath(t *testing.T) {
	const cap = 8
	r := &Recorder{ch: make(chan ReqRecord, cap), done: make(chan struct{})}

	const n = 100000
	start := time.Now()
	for i := 0; i < n; i++ {
		r.Record(ReqRecord{Service: "order", Status: 200})
	}
	elapsed := time.Since(start)

	written, dropped := r.Counters()
	if written != 0 {
		t.Errorf("written = %d, want 0: Record must not write, only enqueue", written)
	}
	if dropped != n-cap {
		t.Errorf("dropped = %d, want %d (buffer %d)", dropped, n-cap, cap)
	}
	if len(r.ch) != cap {
		t.Errorf("channel depth = %d, want %d", len(r.ch), cap)
	}
	// With a permanently full buffer and no consumer, a blocking design would
	// deadlock here. A generous ceiling still fails a blocking regression by
	// many orders of magnitude.
	if elapsed > 5*time.Second {
		t.Errorf("%d Record calls on a full buffer took %v -- Record is blocking", n, elapsed)
	}
	t.Logf("%d Record calls into a full buffer: %v total, %v/call",
		n, elapsed, elapsed/time.Duration(n))
}

// TestRequestDoesNotWaitForTheRecordToReachDisk proves the write is off the
// request goroutine rather than merely fast: when Request returns, the record
// is not on disk yet. It appears only after the writer goroutine has been
// flushed by Close.
func TestRequestDoesNotWaitForTheRecordToReachDisk(t *testing.T) {
	mesh := newFakeMesh(t, nil)
	cfg, path := recordingConfig(t, mesh.srv.URL)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)

	if _, _, err := api.Request(context.Background(), "GET", "order", "/api/v1/orders",
		nil, nil, []int{200}, "list"); err != nil {
		t.Fatalf("request: %v", err)
	}

	// Synchronous-write regression check: a Record implementation that wrote
	// through to the file on the request goroutine would make this non-empty.
	if fi, err := os.Stat(path); err != nil {
		t.Fatalf("stat record file: %v", err)
	} else if fi.Size() != 0 {
		t.Fatalf("record file has %d bytes immediately after Request returned: "+
			"the write is on the request path", fi.Size())
	}

	rec.Close()
	if recs := readRecords(t, path); len(recs) != 1 {
		t.Fatalf("after Close: got %d records, want 1", len(recs))
	}
}

// TestRecordingDoesNotReduceOfferedThroughput is the behavioural half of the
// acceptance criterion: the same closed-loop workload must reach the same
// effective RPS with recording on as with it off.
//
// Methodology matters here. A naive "measure off, then measure on" comparison
// is biased: each ApiClient owns its own Transport, so whichever measurement
// runs first pays the connection-dial cost and looks artificially slow (that
// version of this test reported recording as 20-60% FASTER than baseline,
// which is obvious nonsense). So both clients are built and warmed up front,
// then rounds ALTERNATE on/off and the comparison is between medians.
//
// The tolerance is still loose: this is wall-clock throughput on a shared
// machine, so it is a tripwire for a design regression (a synchronous write, a
// shared mutex) which costs orders of magnitude, not a precision instrument.
// The measured numbers are logged so a human can read the real ratio.
func TestRecordingDoesNotReduceOfferedThroughput(t *testing.T) {
	if testing.Short() {
		t.Skip("throughput comparison is timing-based")
	}
	mesh := newFakeMesh(t, nil)

	offCfg, _ := recordingConfig(t, mesh.srv.URL)
	offCfg.Recording.Enabled = false
	offRec, err := NewRecorder(offCfg)
	if err != nil {
		t.Fatalf("NewRecorder(disabled): %v", err)
	}
	if offRec != nil {
		t.Fatal("recording disabled should yield a nil Recorder")
	}

	onCfg, path := recordingConfig(t, mesh.srv.URL)
	onRec, err := NewRecorder(onCfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}

	// Build both clients ONCE, so neither pays another client's dial cost.
	offAPI := NewApiClientWithRecorder(offCfg, NewStats(), nil)
	onAPI := NewApiClientWithRecorder(onCfg, NewStats(), onRec)

	const (
		workers   = 16
		perWorker = 250
		rounds    = 5
	)
	drive := func(api *ApiClient) float64 {
		var wg sync.WaitGroup
		start := time.Now()
		for w := 0; w < workers; w++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				ctx := WithChain(context.Background(), "browse")
				for i := 0; i < perWorker; i++ {
					_, _, _ = api.Request(ctx, "GET", "order", "/api/v1/orders",
						nil, nil, []int{200}, "list")
				}
			}()
		}
		wg.Wait()
		return float64(workers*perWorker) / time.Since(start).Seconds()
	}

	// Warm both connection pools before measuring either.
	drive(offAPI)
	drive(onAPI)

	var offSamples, onSamples []float64
	for i := 0; i < rounds; i++ {
		// Alternate the order each round so any residual warm-up or GC drift
		// hits both arms equally.
		if i%2 == 0 {
			offSamples = append(offSamples, drive(offAPI))
			onSamples = append(onSamples, drive(onAPI))
		} else {
			onSamples = append(onSamples, drive(onAPI))
			offSamples = append(offSamples, drive(offAPI))
		}
	}
	onRec.Close()

	baseline := median(offSamples)
	recording := median(onSamples)
	written, dropped := onRec.Counters()
	ratio := recording / baseline
	t.Logf("effective RPS over %d alternating rounds (median): recording off %.0f, "+
		"recording on %.0f (ratio %.3f); off=%v on=%v; records written %d, dropped %d",
		rounds, baseline, recording, ratio, fmtRPS(offSamples), fmtRPS(onSamples),
		written, dropped)

	if ratio < 0.85 {
		t.Errorf("recording cut effective RPS to %.1f%% of baseline "+
			"(off %.0f rps, on %.0f rps) -- writes are on the request path",
			ratio*100, baseline, recording)
	}
	if written == 0 {
		t.Error("no records written: the comparison did not actually exercise recording")
	}
	if dropped != 0 {
		t.Errorf("%d records dropped: the default buffer should absorb this workload", dropped)
	}
	if recs := readRecords(t, path); len(recs) == 0 {
		t.Error("record file is empty")
	}
}

func median(xs []float64) float64 {
	s := append([]float64(nil), xs...)
	sort.Float64s(s)
	n := len(s)
	if n == 0 {
		return 0
	}
	if n%2 == 1 {
		return s[n/2]
	}
	return (s[n/2-1] + s[n/2]) / 2
}

func fmtRPS(xs []float64) []string {
	out := make([]string, len(xs))
	for i, x := range xs {
		out[i] = strconv.FormatFloat(x, 'f', 0, 64)
	}
	return out
}

// BenchmarkRecordOnRequestPath measures the only recorder work a request
// goroutine performs. Compare against BenchmarkRecordDisabled to see the
// cost that recording adds to a request.
func BenchmarkRecordOnRequestPath(b *testing.B) {
	dir := b.TempDir()
	cfg := &Config{Recording: RecordingConfig{
		Enabled: true, Path: filepath.Join(dir, "r.jsonl"), FlushIntervalSeconds: 3600,
	}}
	applyDefaults(cfg)
	rec, err := NewRecorder(cfg)
	if err != nil {
		b.Fatal(err)
	}
	defer rec.Close()

	rr := ReqRecord{
		StartUnixNano: time.Now().UnixNano(), Chain: "purchase", Step: "confirm-order",
		Service: "order", Method: "POST", Path: "/api/v1/orders/0190f0ab-1111-7000-8000-x/confirm",
		Status: 201, LatencyMs: 12.5, TraceID: "4bf92f3577b34da6a3ce929d0e0e4736",
		SpanID: "00f067aa0ba902b7", Sampled: true,
	}
	b.ReportAllocs()
	b.RunParallel(func(pb *testing.PB) {
		for pb.Next() {
			rec.Record(rr)
		}
	})
}

// BenchmarkRecordDisabled is the nil-Recorder baseline: what a request pays
// when recording is off.
func BenchmarkRecordDisabled(b *testing.B) {
	var rec *Recorder
	rr := ReqRecord{Service: "order", Status: 200}
	b.ReportAllocs()
	b.RunParallel(func(pb *testing.PB) {
		for pb.Next() {
			rec.Record(rr)
		}
	})
}

// BenchmarkTraceContext measures the per-request traceparent mint, which is
// the other piece of new work on the request path (and is paid whether or not
// recording is enabled, since the header must go on the wire either way).
func BenchmarkTraceContext(b *testing.B) {
	b.ReportAllocs()
	b.RunParallel(func(pb *testing.PB) {
		for pb.Next() {
			tc := newTraceContext(true)
			if len(tc.Header) != 55 {
				b.Fatal("bad header")
			}
		}
	})
}

// ---------------------------------------------------------------------------
// durability, rotation, config
// ---------------------------------------------------------------------------

// TestRecordFileOutlivesTheRunAndAppends: a restart must not erase the
// previous run's records, which is what O_APPEND (not O_TRUNC) buys.
func TestRecordFileOutlivesTheRunAndAppends(t *testing.T) {
	mesh := newFakeMesh(t, nil)
	cfg, path := recordingConfig(t, mesh.srv.URL)

	for run := 0; run < 2; run++ {
		rec, err := NewRecorder(cfg)
		if err != nil {
			t.Fatalf("run %d: NewRecorder: %v", run, err)
		}
		api := NewApiClientWithRecorder(cfg, NewStats(), rec)
		if _, _, err := api.Request(context.Background(), "GET", "order", "/api/v1/orders",
			nil, nil, []int{200}, "list"); err != nil {
			t.Fatalf("run %d: request: %v", run, err)
		}
		rec.Close()
	}

	if recs := readRecords(t, path); len(recs) != 2 {
		t.Fatalf("got %d records after two runs, want 2 (the file was truncated)", len(recs))
	}
}

// TestPeriodicFlushMakesTheTailReadableDuringARun: the file must be useful
// while the generator is still running, and must survive an unclean kill.
func TestPeriodicFlushMakesTheTailReadableDuringARun(t *testing.T) {
	mesh := newFakeMesh(t, nil)
	cfg, path := recordingConfig(t, mesh.srv.URL)
	cfg.Recording.FlushIntervalSeconds = 0.05
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	defer rec.Close()
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)
	if _, _, err := api.Request(context.Background(), "GET", "order", "/api/v1/orders",
		nil, nil, []int{200}, "list"); err != nil {
		t.Fatalf("request: %v", err)
	}

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if fi, err := os.Stat(path); err == nil && fi.Size() > 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("record file still empty after 5s: the periodic flush is not running")
}

// TestRotationBoundsDiskUse: the deployed generator runs indefinitely against
// an emptyDir, so an uncapped record file is a node-disk-pressure incident.
func TestRotationBoundsDiskUse(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "requests.jsonl")
	// max_file_megabytes is an int, so drive rotation through the internal
	// byte cap directly -- writing 1 MiB of records would make this slow.
	r := &Recorder{
		ch: make(chan ReqRecord, 1024), done: make(chan struct{}),
		path: path, maxBytes: 2048, flushEvery: time.Hour,
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	go r.writeLoop(f)

	for i := 0; i < 500; i++ {
		r.Record(ReqRecord{
			StartUnixNano: time.Now().UnixNano(), Chain: "browse", Service: "order",
			Method: "GET", Path: "/api/v1/orders", Status: 200, LatencyMs: 1,
			TraceID: "4bf92f3577b34da6a3ce929d0e0e4736", SpanID: "00f067aa0ba902b7",
		})
	}
	r.Close()

	cur, err := os.Stat(path)
	if err != nil {
		t.Fatalf("current record file missing after rotation: %v", err)
	}
	if cur.Size() > 4096 {
		t.Errorf("current file is %d bytes, want <= 2x the 2048-byte cap", cur.Size())
	}
	if _, err := os.Stat(path + ".1"); err != nil {
		t.Errorf("rotated predecessor %s.1 missing: %v", path, err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) > 2 {
		t.Errorf("%d files in the record dir, want at most 2 (current + .1)", len(entries))
	}
}

// TestRecordingEnabledWithoutAPathIsAnError: silently recording nowhere is
// worse than a startup complaint.
func TestRecordingEnabledWithoutAPathIsAnError(t *testing.T) {
	cfg := &Config{Recording: RecordingConfig{Enabled: true, Path: "  "}}
	applyDefaults(cfg)
	rec, err := NewRecorder(cfg)
	if err == nil {
		t.Error("want an error when recording.enabled is true but path is empty")
	}
	if rec != nil {
		t.Error("want a nil Recorder on error")
	}
}

// TestDisabledRecordingIsFullyInert: the nil Recorder must be safe on every
// method, since api.go calls it without a nil check.
func TestDisabledRecordingIsFullyInert(t *testing.T) {
	cfg := &Config{}
	applyDefaults(cfg)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	if rec != nil {
		t.Fatal("want nil Recorder when recording is disabled")
	}
	if rec.Enabled() {
		t.Error("Enabled() on a nil Recorder must be false")
	}
	if rec.Path() != "" {
		t.Error("Path() on a nil Recorder must be empty")
	}
	if !rec.TraceSampled() {
		t.Error("TraceSampled() on a nil Recorder must be true: traceparent " +
			"propagation is independent of whether records are written")
	}
	rec.Record(ReqRecord{})
	rec.Close()
	if w, d := rec.Counters(); w != 0 || d != 0 {
		t.Errorf("nil Recorder counters = (%d, %d), want (0, 0)", w, d)
	}
}

// TestRecordingIsIndependentOfTraceparentPropagation: turning recording off
// must not stop the loadgen setting a traceparent, or the services lose their
// trace parent for no reason.
func TestRecordingIsIndependentOfTraceparentPropagation(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Get("traceparent")
		w.WriteHeader(200)
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	cfg := &Config{}
	cfg.Target.BaseURLTemplate = srv.URL + "/%s"
	cfg.Target.RequestTimeoutSeconds = 5
	applyDefaults(cfg)

	api := NewApiClient(cfg, NewStats()) // no recorder at all
	if _, _, err := api.Request(context.Background(), "GET", "order", "/api/v1/orders",
		nil, nil, []int{200}, "list"); err != nil {
		t.Fatalf("request: %v", err)
	}
	if got == "" {
		t.Fatal("no traceparent sent when recording is disabled")
	}
	tc := parseTraceparent(got)
	if len(tc.TraceID) != 32 || !tc.Sampled {
		t.Errorf("traceparent %q did not parse as a sampled trace", got)
	}
}

// TestTraceSampledRatioZeroClearsTheFlag: the knob must actually reach the
// wire, and the record must say what was sent so an analysis can tell
// "no span exists" from "the span is missing".
func TestTraceSampledRatioZeroClearsTheFlag(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Get("traceparent")
		w.WriteHeader(200)
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	cfg, path := recordingConfig(t, srv.URL)
	zero := 0.0
	cfg.Recording.TraceSampledRatio = &zero
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)
	if _, _, err := api.Request(context.Background(), "GET", "order", "/api/v1/orders",
		nil, nil, []int{200}, "list"); err != nil {
		t.Fatalf("request: %v", err)
	}
	rec.Close()

	if !strings.HasSuffix(got, "-00") {
		t.Errorf("traceparent = %q, want flags 00 at trace_sampled_ratio 0", got)
	}
	recs := readRecords(t, path)
	if len(recs) != 1 {
		t.Fatalf("got %d records, want 1", len(recs))
	}
	if recs[0].Sampled {
		t.Error("record says sampled=true but flags 00 went on the wire")
	}
}

// TestSampledRatioDefaultsToOne pins the deliberate default: absent key means
// always-sampled, and 0.0 is distinguishable from absent.
func TestSampledRatioDefaultsToOne(t *testing.T) {
	if got := (RecordingConfig{}).SampledRatio(); got != 1.0 {
		t.Errorf("absent trace_sampled_ratio = %v, want 1.0", got)
	}
	for _, tc := range []struct{ in, want float64 }{
		{0, 0}, {0.25, 0.25}, {1, 1}, {-3, 0}, {7, 1},
	} {
		v := tc.in
		if got := (RecordingConfig{TraceSampledRatio: &v}).SampledRatio(); got != tc.want {
			t.Errorf("SampledRatio(%v) = %v, want %v", tc.in, got, tc.want)
		}
	}
}

// TestRecordingDefaultsAreOperational: a config that sets only enabled+path
// must not end up with a zero-capacity channel (which would drop every
// record) or a zero flush interval.
func TestRecordingDefaultsAreOperational(t *testing.T) {
	cfg := &Config{Recording: RecordingConfig{Enabled: true, Path: "/tmp/x.jsonl"}}
	applyDefaults(cfg)
	if cfg.Recording.BufferRecords <= 0 {
		t.Errorf("buffer_records = %d, want a positive default", cfg.Recording.BufferRecords)
	}
	if cfg.Recording.FlushIntervalSeconds <= 0 {
		t.Errorf("flush_interval_seconds = %v, want a positive default", cfg.Recording.FlushIntervalSeconds)
	}
	if cfg.Recording.MaxFileMegabytes <= 0 {
		t.Errorf("max_file_megabytes = %d, want a positive default", cfg.Recording.MaxFileMegabytes)
	}
}

// ---------------------------------------------------------------------------
// chain labelling and route templating
// ---------------------------------------------------------------------------

// TestChainLabelSplitsTrafficByJourney: without this the record file cannot be
// split by journey, which is one of the stated reasons for the feature.
func TestChainLabelSplitsTrafficByJourney(t *testing.T) {
	mesh := newFakeMesh(t, nil)
	cfg, path := recordingConfig(t, mesh.srv.URL)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)

	for _, chain := range []string{"purchase", "refund", "staff", "ops", "scalper", "bootstrap"} {
		ctx := WithChain(context.Background(), chain)
		if _, _, err := api.Request(ctx, "GET", "order", "/api/v1/orders",
			nil, nil, []int{200}, "list"); err != nil {
			t.Fatalf("%s: %v", chain, err)
		}
	}
	// An unlabelled context must still produce a usable row.
	if _, _, err := api.Request(context.Background(), "GET", "order", "/api/v1/orders",
		nil, nil, []int{200}, "list"); err != nil {
		t.Fatal(err)
	}
	rec.Close()

	seen := map[string]bool{}
	for _, r := range readRecords(t, path) {
		seen[r.Chain] = true
	}
	for _, want := range []string{"purchase", "refund", "staff", "ops", "scalper", "bootstrap", "unlabelled"} {
		if !seen[want] {
			t.Errorf("no record with chain %q", want)
		}
	}
}

func TestChainFromContextFallback(t *testing.T) {
	if got := ChainFromContext(context.Background()); got != "unlabelled" {
		t.Errorf("bare context chain = %q, want unlabelled", got)
	}
	//nolint:staticcheck // deliberately exercising the nil-context guard
	if got := ChainFromContext(nil); got != "unlabelled" {
		t.Errorf("nil context chain = %q, want unlabelled", got)
	}
	ctx := WithChain(WithChain(context.Background(), "outer"), "inner")
	if got := ChainFromContext(ctx); got != "inner" {
		t.Errorf("nested chain = %q, want inner (innermost wins)", got)
	}
}

func TestRouteTemplate(t *testing.T) {
	cases := []struct{ in, want string }{
		{"/api/v1/orders", "/api/v1/orders"},
		{"/api/v1/orders?limit=100&offset=0", "/api/v1/orders"},
		{"/api/v1/orders/0190f0ab-1111-7000-8000-000000000001", "/api/v1/orders/{id}"},
		{"/api/v1/orders/0190f0ab-1111-7000-8000-000000000001/confirm", "/api/v1/orders/{id}/confirm"},
		{"/api/v1/accounts/12345/wallet", "/api/v1/accounts/{id}/wallet"},
		// route elements that merely contain a digit must survive
		{"/api/v1/places", "/api/v1/places"},
		{"/api/v2/payment-channel/status", "/api/v2/payment-channel/status"},
		{"", ""},
	}
	for _, c := range cases {
		if got := RouteTemplate(c.in); got != c.want {
			t.Errorf("RouteTemplate(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

// TestConcurrentRecordingIsRaceFreeAndComplete: with the buffer sized for the
// burst nothing should be dropped, and the file must contain every row.
// Run with -race this also covers the hand-off itself.
func TestConcurrentRecordingIsRaceFreeAndComplete(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "requests.jsonl")
	cfg := &Config{Recording: RecordingConfig{
		Enabled: true, Path: path, BufferRecords: 200000, FlushIntervalSeconds: 3600,
	}}
	applyDefaults(cfg)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}

	const workers, each = 32, 500
	var wg sync.WaitGroup
	var sent atomic.Int64
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			rng := rand.New(rand.NewSource(int64(id)))
			for i := 0; i < each; i++ {
				tc := newTraceContext(true)
				rec.Record(ReqRecord{
					StartUnixNano: time.Now().UnixNano(),
					Chain:         "purchase", Step: "step", Service: "order",
					Method: "GET", Path: "/api/v1/orders", Status: 200,
					LatencyMs: rng.Float64() * 10,
					TraceID:   tc.TraceID, SpanID: tc.SpanID, Sampled: true,
				})
				sent.Add(1)
			}
		}(w)
	}
	wg.Wait()
	rec.Close()

	written, dropped := rec.Counters()
	if dropped != 0 {
		t.Errorf("dropped %d of %d records despite a buffer of %d",
			dropped, sent.Load(), cfg.Recording.BufferRecords)
	}
	if written != sent.Load() {
		t.Errorf("written = %d, sent = %d", written, sent.Load())
	}
	if got := len(readRecords(t, path)); int64(got) != written {
		t.Errorf("%d lines in the file, %d recorded as written", got, written)
	}
}
