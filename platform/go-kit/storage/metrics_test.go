package storage

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	metricsdk "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

// The acquisition hooks are exercised directly rather than through a pool: the
// numbers they maintain are the point, and reaching them through pgxpool would
// need a live Postgres, which no test in this kit requires.
func TestAcquireTracerCountsPendingAndTimeouts(t *testing.T) {
	tracer := &acquireTracer{}

	first := tracer.TraceAcquireStart(context.Background(), nil, pgxpool.TraceAcquireStartData{})
	second := tracer.TraceAcquireStart(context.Background(), nil, pgxpool.TraceAcquireStartData{})
	if got := tracer.pending.Load(); got != 2 {
		t.Fatalf("expected two pending acquisitions, got %d", got)
	}

	tracer.TraceAcquireEnd(first, nil, pgxpool.TraceAcquireEndData{})
	if got := tracer.pending.Load(); got != 1 {
		t.Fatalf("expected one pending acquisition after a success, got %d", got)
	}
	if got := tracer.timeouts.Load(); got != 0 {
		t.Fatalf("a successful acquisition counted as a timeout: %d", got)
	}

	// A failure whose context expired is what a pool timeout looks like from the
	// caller's side. A failure with a live context is a different fault and must
	// not be counted here.
	expired, cancel := context.WithDeadline(second, time.Now().Add(-time.Second))
	defer cancel()
	tracer.TraceAcquireEnd(expired, nil, pgxpool.TraceAcquireEndData{Err: errors.New("timeout")})
	if got := tracer.timeouts.Load(); got != 1 {
		t.Fatalf("expected one timeout, got %d", got)
	}
	if got := tracer.pending.Load(); got != 0 {
		t.Fatalf("expected no pending acquisitions, got %d", got)
	}

	tracer.TraceAcquireStart(context.Background(), nil, pgxpool.TraceAcquireStartData{})
	tracer.TraceAcquireEnd(context.Background(), nil, pgxpool.TraceAcquireEndData{Err: errors.New("dial failed")})
	if got := tracer.timeouts.Load(); got != 1 {
		t.Fatalf("a non-timeout failure was counted as a timeout: %d", got)
	}
}

// The missing case: a pool bound has to be readable from the pool's own numbers,
// under the names the database conventions define.
func TestPoolMetricsPublishConventionNames(t *testing.T) {
	reader := metricsdk.NewManualReader()
	provider := metricsdk.NewMeterProvider(metricsdk.WithReader(reader))
	defer func() { _ = provider.Shutdown(context.Background()) }()

	tracer := &acquireTracer{
		poolAttrs: metric.WithAttributes(attribute.String("db.client.connection.pool.name", PoolName)),
	}
	metrics := &PoolMetrics{pool: unconnectedPool(t), tracer: tracer}
	registration, err := metrics.RegisterPoolMetrics(provider.Meter("test"))
	if err != nil {
		t.Fatalf("register pool metrics: %v", err)
	}
	defer func() { _ = registration.Unregister() }()

	tracer.TraceAcquireStart(context.Background(), nil, pgxpool.TraceAcquireStartData{})

	collected := collectMetrics(t, reader)
	for _, name := range []string{
		"db.client.connection.count",
		"db.client.connection.max",
		"db.client.connection.pending_requests",
		"db.client.connection.timeouts",
	} {
		if _, present := collected[name]; !present {
			t.Fatalf("missing %s; collected %v", name, keys(collected))
		}
	}

	pending := collected["db.client.connection.pending_requests"]
	sum, ok := pending.Data.(metricdata.Sum[int64])
	if !ok {
		t.Fatalf("expected an int sum, got %T", pending.Data)
	}
	if len(sum.DataPoints) != 1 || sum.DataPoints[0].Value != 1 {
		t.Fatalf("expected one waiting acquisition, got %#v", sum.DataPoints)
	}

	// The ceiling a saturated pool is read against.
	max := collected["db.client.connection.max"].Data.(metricdata.Sum[int64])
	if len(max.DataPoints) != 1 || max.DataPoints[0].Value != 7 {
		t.Fatalf("expected the configured max of 7, got %#v", max.DataPoints)
	}
}

// unconnectedPool builds a pool that has opened nothing.
//
// pgxpool.NewWithConfig does not dial: connections are created on first
// acquisition or by the background health check, so Stat is readable with no
// Postgres present. MinConns stays at its default of zero so no idle connection
// is created either.
func unconnectedPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	config, err := pgxpool.ParseConfig("postgresql://unused:unused@127.0.0.1:1/unused")
	if err != nil {
		t.Fatalf("parse config: %v", err)
	}
	config.MaxConns = 7
	pool, err := pgxpool.NewWithConfig(context.Background(), config)
	if err != nil {
		t.Fatalf("create pool: %v", err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func collectMetrics(t *testing.T, reader metricsdk.Reader) map[string]metricdata.Metrics {
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

func keys(byName map[string]metricdata.Metrics) []string {
	names := make([]string, 0, len(byName))
	for name := range byName {
		names = append(names, name)
	}
	return names
}
