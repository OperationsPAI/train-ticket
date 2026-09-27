package storage

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	miniredis "github.com/alicebob/miniredis/v2"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	redis "github.com/redis/go-redis/v9"
	"go.opentelemetry.io/otel/attribute"
	metricsdk "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

// The contract's names, units and descriptions are the Java platform kit's,
// word for word, so a rename in either kit fails here instead of forking the
// series silently.
const (
	unpublishedSampleInstrument = "train_ticket.outbox.unpublished.sample"
	oldestUnpublishedInstrument = "train_ticket.outbox.oldest_unpublished.age"
	publishedInstrument         = "train_ticket.outbox.published"
	outboxFailuresInstrument    = "train_ticket.outbox.failures"
)

func TestOutboxMetricsPublishContractNamesAndAttributes(t *testing.T) {
	reader := metricsdk.NewManualReader()
	provider := metricsdk.NewMeterProvider(metricsdk.WithReader(reader))
	t.Cleanup(func() { _ = provider.Shutdown(context.Background()) })

	metrics, err := NewOutboxMetrics(provider.Meter("go-kit-test"))
	if err != nil {
		t.Fatalf("build outbox metrics: %v", err)
	}
	metrics.ObserveUnpublishedSample(100, 3500*time.Millisecond)
	metrics.RecordPublished(7)
	metrics.RecordFailure(StageSelect)
	metrics.RecordFailure(StagePublish)
	metrics.RecordFailure(StageMarkPublished)

	collected := collectOutboxMetrics(t, reader)

	sampleMetric := outboxMetricFor(t, collected, unpublishedSampleInstrument)
	if sampleMetric.Unit != "{event}" || sampleMetric.Description != "Rows visible in the relay's bounded unpublished head sample." {
		t.Fatalf("sample instrument metadata drifted: unit=%q description=%q", sampleMetric.Unit, sampleMetric.Description)
	}
	sample, ok := sampleMetric.Data.(metricdata.Gauge[int64])
	if !ok {
		t.Fatalf("sample is %T, want an int64 gauge", sampleMetric.Data)
	}
	if len(sample.DataPoints) != 1 || sample.DataPoints[0].Value != 100 {
		t.Fatalf("expected one sample of 100 rows, got %#v", sample.DataPoints)
	}
	// The sample is a count of rows, never a count(*) of the table, so it must
	// stay bounded by the relay's batch size.
	if sample.DataPoints[0].Value > 100 {
		t.Fatalf("the head sample must stay bounded by the batch size, got %d", sample.DataPoints[0].Value)
	}

	ageMetric := outboxMetricFor(t, collected, oldestUnpublishedInstrument)
	if ageMetric.Unit != "s" || ageMetric.Description != "Age of the head-of-line unpublished outbox event." {
		t.Fatalf("age instrument metadata drifted: unit=%q description=%q", ageMetric.Unit, ageMetric.Description)
	}
	age, ok := ageMetric.Data.(metricdata.Gauge[float64])
	if !ok {
		t.Fatalf("age is %T, want a float64 gauge", ageMetric.Data)
	}
	if len(age.DataPoints) != 1 || age.DataPoints[0].Value != 3.5 {
		t.Fatalf("the age is reported in seconds, got %#v", age.DataPoints)
	}

	publishedMetric := outboxMetricFor(t, collected, publishedInstrument)
	if publishedMetric.Unit != "{event}" || publishedMetric.Description != "Outbox events whose bounded Redis publish pipeline completed successfully." {
		t.Fatalf("published instrument metadata drifted: unit=%q description=%q", publishedMetric.Unit, publishedMetric.Description)
	}
	if got := counterValue(t, publishedMetric); got != 7 {
		t.Fatalf("published = %d, want the 7 rows of the batch", got)
	}

	failuresMetric := outboxMetricFor(t, collected, outboxFailuresInstrument)
	if failuresMetric.Unit != "{failure}" || failuresMetric.Description != "Outbox relay failures by a bounded processing stage." {
		t.Fatalf("failures instrument metadata drifted: unit=%q description=%q", failuresMetric.Unit, failuresMetric.Description)
	}
	failures, ok := failuresMetric.Data.(metricdata.Sum[int64])
	if !ok {
		t.Fatalf("failures is %T, want an int64 sum", failuresMetric.Data)
	}
	for _, stage := range []string{"select", "publish", "mark_published"} {
		if got := sumByAttributes(t, failures, map[string]string{"stage": stage}); got != 1 {
			t.Errorf("failures{stage=%s} = %d, want 1", stage, got)
		}
	}
	if len(failures.DataPoints) != 3 {
		t.Errorf("failures must carry exactly the three closed stages, got %d series", len(failures.DataPoints))
	}
}

// A relay built without a meter must record nothing rather than panic.
func TestOutboxMetricsWithoutMeterIsInert(t *testing.T) {
	metrics, err := NewOutboxMetrics(nil)
	if err != nil {
		t.Fatalf("a nil meter is a normal configuration: %v", err)
	}
	if metrics != nil {
		t.Fatalf("a nil meter must not produce an instrument set, got %#v", metrics)
	}
	var nilMetrics *OutboxMetrics
	nilMetrics.ObserveUnpublishedSample(5, time.Second)
	nilMetrics.RecordPublished(5)
	nilMetrics.RecordFailure(StagePublish)
}

// A successful batch reports the bounded head sample and the rows it really
// published, which is what makes the backlog readable from a sample rather than
// from a count over the table.
func TestOutboxRelayRecordsBoundedSampleAndPublishedRows(t *testing.T) {
	harness := newOutboxMetricsHarness(t)
	harness.db.rows = []outboxTestRow{{
		seq:      1,
		stream:   "events:place-network",
		envelope: json.RawMessage(`{"eventId":"evt-1"}`),
		created:  time.Now().Add(-2 * time.Second),
	}}

	if err := harness.relay.PublishBatch(context.Background()); err != nil {
		t.Fatalf("publish batch: %v", err)
	}

	collected := collectOutboxMetrics(t, harness.reader)
	sample := outboxMetricFor(t, collected, unpublishedSampleInstrument).Data.(metricdata.Gauge[int64])
	if len(sample.DataPoints) != 1 || sample.DataPoints[0].Value != 1 {
		t.Fatalf("expected the one row of the head sample, got %#v", sample.DataPoints)
	}
	age := outboxMetricFor(t, collected, oldestUnpublishedInstrument).Data.(metricdata.Gauge[float64])
	if len(age.DataPoints) != 1 || age.DataPoints[0].Value < 1.5 || age.DataPoints[0].Value > 5 {
		t.Fatalf("expected the head row's age in seconds, got %#v", age.DataPoints)
	}
	if got := counterValue(t, outboxMetricFor(t, collected, publishedInstrument)); got != 1 {
		t.Fatalf("published = %d, want 1", got)
	}
	if _, present := collected[outboxFailuresInstrument]; present {
		t.Errorf("a clean batch is not a failure, got %v", outboxKeys(collected))
	}
}

// A drained outbox reports zero rather than leaving the previous poll's peak on
// the gauge: the sample is a level, not a high-water mark.
func TestOutboxRelayRecordsAnEmptyHeadSampleAfterDraining(t *testing.T) {
	harness := newOutboxMetricsHarness(t)
	harness.db.rows = []outboxTestRow{{seq: 1, stream: "events:place-network", envelope: json.RawMessage(`{"eventId":"evt-1"}`), created: time.Now()}}
	if err := harness.relay.PublishBatch(context.Background()); err != nil {
		t.Fatalf("publish batch: %v", err)
	}
	harness.db.rows = nil
	if err := harness.relay.PublishBatch(context.Background()); err != nil {
		t.Fatalf("publish drained batch: %v", err)
	}

	collected := collectOutboxMetrics(t, harness.reader)
	sample := outboxMetricFor(t, collected, unpublishedSampleInstrument).Data.(metricdata.Gauge[int64])
	if len(sample.DataPoints) != 1 || sample.DataPoints[0].Value != 0 {
		t.Fatalf("a drained outbox must read zero, got %#v", sample.DataPoints)
	}
	age := outboxMetricFor(t, collected, oldestUnpublishedInstrument).Data.(metricdata.Gauge[float64])
	if len(age.DataPoints) != 1 || age.DataPoints[0].Value != 0 {
		t.Fatalf("a drained outbox has no head-of-line age, got %#v", age.DataPoints)
	}
	if got := counterValue(t, outboxMetricFor(t, collected, publishedInstrument)); got != 1 {
		t.Fatalf("only the first batch published rows, got %d", got)
	}
}

func TestOutboxRelayRecordsEachFailureStage(t *testing.T) {
	t.Run("select", func(t *testing.T) {
		harness := newOutboxMetricsHarness(t)
		harness.db.queryErr = errors.New("connection reset by peer")
		if err := harness.relay.PublishBatch(context.Background()); err == nil {
			t.Fatal("a failing select must be reported to the caller")
		}
		harness.assertStage(t, "select")
	})

	t.Run("publish", func(t *testing.T) {
		harness := newOutboxMetricsHarness(t)
		harness.db.rows = []outboxTestRow{{seq: 1, stream: "events:place-network", envelope: json.RawMessage(`{"eventId":"evt-1"}`), created: time.Now()}}
		// The Redis the relay was handed is gone, so the pipeline cannot land.
		harness.server.Close()
		if err := harness.relay.PublishBatch(context.Background()); err == nil {
			t.Fatal("a failing publish must be reported to the caller")
		}
		harness.assertStage(t, "publish")
	})

	t.Run("mark_published", func(t *testing.T) {
		harness := newOutboxMetricsHarness(t)
		harness.db.rows = []outboxTestRow{{seq: 1, stream: "events:place-network", envelope: json.RawMessage(`{"eventId":"evt-1"}`), created: time.Now()}}
		harness.db.execErr = errors.New("deadlock detected")
		if err := harness.relay.PublishBatch(context.Background()); err == nil {
			t.Fatal("a failing retirement must be reported to the caller")
		}
		collected := collectOutboxMetrics(t, harness.reader)
		failures := outboxMetricFor(t, collected, outboxFailuresInstrument).Data.(metricdata.Sum[int64])
		if got := sumByAttributes(t, failures, map[string]string{"stage": "mark_published"}); got != 1 {
			t.Errorf("failures{stage=mark_published} = %d, want 1", got)
		}
		if got := sumByAttributes(t, failures, map[string]string{"stage": "publish"}); got != 0 {
			t.Errorf("the publish itself succeeded, got %d", got)
		}
		// The batch reached Redis but could not be retired, so it is not
		// published: the next poll must publish it again.
		if metric, present := collected[publishedInstrument]; present {
			if got := counterValue(t, metric); got != 0 {
				t.Errorf("an unretired batch must not count as published, got %d", got)
			}
		}
	})
}

type outboxTestRow struct {
	seq      int64
	stream   string
	envelope json.RawMessage
	created  time.Time
}

type outboxTestDB struct {
	rows     []outboxTestRow
	queryErr error
	execErr  error
}

func (db *outboxTestDB) Query(context.Context, string, ...any) (pgx.Rows, error) {
	if db.queryErr != nil {
		return nil, db.queryErr
	}
	return &outboxTestRows{rows: db.rows}, nil
}

func (db *outboxTestDB) QueryRow(context.Context, string, ...any) pgx.Row { return nil }

func (db *outboxTestDB) Exec(context.Context, string, ...any) (pgconn.CommandTag, error) {
	if db.execErr != nil {
		return pgconn.CommandTag{}, db.execErr
	}
	return pgconn.NewCommandTag("UPDATE 1"), nil
}

type outboxTestRows struct {
	rows  []outboxTestRow
	index int
}

func (r *outboxTestRows) Close()                                       {}
func (r *outboxTestRows) Err() error                                   { return nil }
func (r *outboxTestRows) CommandTag() pgconn.CommandTag                { return pgconn.NewCommandTag("SELECT 1") }
func (r *outboxTestRows) FieldDescriptions() []pgconn.FieldDescription { return nil }
func (r *outboxTestRows) Values() ([]any, error)                       { return nil, nil }
func (r *outboxTestRows) RawValues() [][]byte                          { return nil }
func (r *outboxTestRows) Conn() *pgx.Conn                              { return nil }

func (r *outboxTestRows) Next() bool {
	if r.index >= len(r.rows) {
		return false
	}
	r.index++
	return true
}

func (r *outboxTestRows) Scan(dest ...any) error {
	row := r.rows[r.index-1]
	*(dest[0].(*int64)) = row.seq
	*(dest[1].(*string)) = row.stream
	*(dest[2].(*json.RawMessage)) = row.envelope
	*(dest[3].(*time.Time)) = row.created
	return nil
}

type outboxMetricsHarness struct {
	t      *testing.T
	relay  *OutboxRelay
	db     *outboxTestDB
	server *miniredis.Miniredis
	reader metricsdk.Reader
}

func newOutboxMetricsHarness(t *testing.T) *outboxMetricsHarness {
	t.Helper()
	reader := metricsdk.NewManualReader()
	provider := metricsdk.NewMeterProvider(metricsdk.WithReader(reader))
	t.Cleanup(func() { _ = provider.Shutdown(context.Background()) })
	metrics, err := NewOutboxMetrics(provider.Meter("go-kit-test"))
	if err != nil {
		t.Fatalf("build outbox metrics: %v", err)
	}
	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr(), MaxRetries: -1, DialTimeout: 250 * time.Millisecond})
	t.Cleanup(func() { _ = client.Close() })
	db := &outboxTestDB{}
	return &outboxMetricsHarness{
		t:      t,
		relay:  NewOutboxRelay(db, client).WithMetrics(metrics),
		db:     db,
		server: server,
		reader: reader,
	}
}

func (h *outboxMetricsHarness) assertStage(t *testing.T, stage string) {
	t.Helper()
	collected := collectOutboxMetrics(t, h.reader)
	failures := outboxMetricFor(t, collected, outboxFailuresInstrument).Data.(metricdata.Sum[int64])
	if got := sumByAttributes(t, failures, map[string]string{"stage": stage}); got != 1 {
		t.Fatalf("failures{stage=%s} = %d, want 1", stage, got)
	}
}

func collectOutboxMetrics(t *testing.T, reader metricsdk.Reader) map[string]metricdata.Metrics {
	t.Helper()
	var collected metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &collected); err != nil {
		t.Fatalf("collect: %v", err)
	}
	byName := map[string]metricdata.Metrics{}
	for _, scope := range collected.ScopeMetrics {
		for _, metric := range scope.Metrics {
			byName[metric.Name] = metric
		}
	}
	return byName
}

func outboxMetricFor(t *testing.T, collected map[string]metricdata.Metrics, name string) metricdata.Metrics {
	t.Helper()
	metric, present := collected[name]
	if !present {
		t.Fatalf("missing instrument %s; collected %v", name, outboxKeys(collected))
	}
	return metric
}

func counterValue(t *testing.T, metric metricdata.Metrics) int64 {
	t.Helper()
	sum, ok := metric.Data.(metricdata.Sum[int64])
	if !ok {
		t.Fatalf("%s is %T, want an int64 sum", metric.Name, metric.Data)
	}
	if len(sum.DataPoints) == 0 {
		return 0
	}
	return sum.DataPoints[0].Value
}

func sumByAttributes(t *testing.T, sum metricdata.Sum[int64], attributes map[string]string) int64 {
	t.Helper()
	for _, point := range sum.DataPoints {
		if outboxAttributesMatch(point.Attributes, attributes) {
			return point.Value
		}
	}
	return 0
}

func outboxAttributesMatch(set attribute.Set, want map[string]string) bool {
	if set.Len() != len(want) {
		return false
	}
	for key, value := range want {
		got, present := set.Value(attribute.Key(key))
		if !present || got.AsString() != value {
			return false
		}
	}
	return true
}

func outboxKeys(byName map[string]metricdata.Metrics) []string {
	names := make([]string, 0, len(byName))
	for name := range byName {
		names = append(names, name)
	}
	return names
}
