package storage

import (
	"context"
	"sync/atomic"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// PoolName identifies the pool this kit creates, within one process. Every Go
// service runs exactly one, so a fixed name is sufficient and keeps the
// attribute comparable across services.
const PoolName = "platform-go-kit-postgres"

type acquireStartKey struct{}

// acquireTracer measures what pgxpool.Stat cannot.
//
// Stat reports cumulative totals (EmptyAcquireCount) and instant levels
// (AcquiredConns), but nothing that answers "how many callers are waiting for a
// connection right now" -- the quantity the database conventions name
// db.client.connection.pending_requests, and the one that shows a pool saturating
// before any request has failed. Counting between TraceAcquireStart and
// TraceAcquireEnd yields it exactly: pgx calls the first on entry to Acquire and
// the second once a connection has been handed over or the attempt failed, so the
// difference is the set of callers currently blocked.
//
// The same pair gives the wait-time distribution and the timeout count. Stat's
// EmptyAcquireWaitTime is a cumulative sum, from which only a mean can be
// derived, and a mean hides the tail that matters when a pool is contended. An
// acquisition that ends with its context expired waited until its deadline, which
// is what a pool timeout is from the caller's side.
type acquireTracer struct {
	pending  atomic.Int64
	timeouts atomic.Int64
	// Set once RegisterPoolMetrics has built it, which can happen after the pool
	// is already serving, so the read in TraceAcquireEnd and that write must not
	// race. Holding it in an atomic is what makes the ordering defined.
	waitTime  atomic.Pointer[metric.Float64Histogram]
	poolAttrs metric.RecordOption
}

func (tracer *acquireTracer) TraceAcquireStart(ctx context.Context, _ *pgxpool.Pool, _ pgxpool.TraceAcquireStartData) context.Context {
	tracer.pending.Add(1)
	// Monotonic reading carried on the context pgx threads through to the end
	// hook, which is the only place both ends of one acquisition are visible.
	return context.WithValue(ctx, acquireStartKey{}, time.Now())
}

func (tracer *acquireTracer) TraceAcquireEnd(ctx context.Context, _ *pgxpool.Pool, data pgxpool.TraceAcquireEndData) {
	tracer.pending.Add(-1)
	if data.Err != nil && ctx.Err() != nil {
		tracer.timeouts.Add(1)
	}
	waitTime := tracer.waitTime.Load()
	startedAt, ok := ctx.Value(acquireStartKey{}).(time.Time)
	if !ok || waitTime == nil {
		return
	}
	(*waitTime).Record(ctx, time.Since(startedAt).Seconds(), tracer.poolAttrs)
}

// The query hooks exist only so this type satisfies pgx.QueryTracer.
//
// pgxpool has no field for an acquire tracer: it type-asserts
// ConnConfig.Tracer to AcquireTracer at construction, so the only way to receive
// the acquire hooks is to be installed as the connection's query tracer. Nothing
// in this kit traced queries before, so that slot is free -- but a type placed
// there has to implement both interfaces, and per-query tracing is not wanted
// here: the spans already cover queries, and an instrument per statement would
// carry SQL text into metric attributes.
func (tracer *acquireTracer) TraceQueryStart(ctx context.Context, _ *pgx.Conn, _ pgx.TraceQueryStartData) context.Context {
	return ctx
}

func (tracer *acquireTracer) TraceQueryEnd(context.Context, *pgx.Conn, pgx.TraceQueryEndData) {}

// PoolMetrics binds a pool's state to a meter. NewPoolWithMetrics returns one;
// RegisterPoolMetrics publishes through it.
type PoolMetrics struct {
	pool   *pgxpool.Pool
	tracer *acquireTracer
}

// NewPoolWithMetrics creates the pool with acquisition tracing installed.
//
// Separate from NewPool because pgxpool reads AcquireTracer at construction, so
// it cannot be attached to a pool that already exists. A service that exports no
// metrics keeps calling NewPool and pays nothing.
func NewPoolWithMetrics(ctx context.Context, databaseURL string) (*pgxpool.Pool, *PoolMetrics, error) {
	tracer := &acquireTracer{
		poolAttrs: metric.WithAttributes(attribute.String("db.client.connection.pool.name", PoolName)),
	}
	pool, err := newPool(ctx, databaseURL, func(cfg *pgxpool.Config) {
		// Not a field of its own: pgxpool discovers the acquire tracer by
		// type-asserting the connection tracer. See the query hooks above.
		cfg.ConnConfig.Tracer = tracer
	})
	if err != nil {
		return nil, nil, err
	}
	return pool, &PoolMetrics{pool: pool, tracer: tracer}, nil
}

// RegisterPoolMetrics publishes the pool's state under the database client
// semantic conventions.
//
// The state instruments are observable: occupancy is a level to be sampled and
// the driver already maintains it, so a callback reading Stat is both cheaper and
// more accurate than mirroring every checkout. A saturated pool reads as
// db.client.connection.pending_requests rising while
// db.client.connection.count{state=used} sits at db.client.connection.max.
func (metrics *PoolMetrics) RegisterPoolMetrics(meter metric.Meter) (metric.Registration, error) {
	if metrics == nil {
		return nil, nil
	}
	pool := attribute.String("db.client.connection.pool.name", PoolName)
	used := metric.WithAttributes(pool, attribute.String("db.client.connection.state", "used"))
	idle := metric.WithAttributes(pool, attribute.String("db.client.connection.state", "idle"))
	poolOnly := metric.WithAttributes(pool)

	connectionCount, err := meter.Int64ObservableUpDownCounter(
		"db.client.connection.count",
		metric.WithDescription("The number of connections that are currently in state described by the state attribute."),
		metric.WithUnit("{connection}"),
	)
	if err != nil {
		return nil, err
	}
	connectionMax, err := meter.Int64ObservableUpDownCounter(
		"db.client.connection.max",
		metric.WithDescription("The maximum number of open connections allowed."),
		metric.WithUnit("{connection}"),
	)
	if err != nil {
		return nil, err
	}
	pendingRequests, err := meter.Int64ObservableUpDownCounter(
		"db.client.connection.pending_requests",
		metric.WithDescription("The number of current pending requests for an open connection."),
		metric.WithUnit("{request}"),
	)
	if err != nil {
		return nil, err
	}
	timeouts, err := meter.Int64ObservableCounter(
		"db.client.connection.timeouts",
		metric.WithDescription("The number of connection timeouts that have occurred trying to obtain a connection from the pool."),
		metric.WithUnit("{timeout}"),
	)
	if err != nil {
		return nil, err
	}
	// Synchronous: a wait time is a distribution over events, not a level.
	waitTime, err := meter.Float64Histogram(
		"db.client.connection.wait_time",
		metric.WithDescription("The time it took to obtain an open connection from the pool."),
		metric.WithUnit("s"),
	)
	if err != nil {
		return nil, err
	}
	metrics.tracer.waitTime.Store(&waitTime)

	return meter.RegisterCallback(func(_ context.Context, observer metric.Observer) error {
		stat := metrics.pool.Stat()
		observer.ObserveInt64(connectionCount, int64(stat.AcquiredConns()), used)
		observer.ObserveInt64(connectionCount, int64(stat.IdleConns()), idle)
		observer.ObserveInt64(connectionMax, int64(stat.MaxConns()), poolOnly)
		observer.ObserveInt64(pendingRequests, metrics.tracer.pending.Load(), poolOnly)
		observer.ObserveInt64(timeouts, metrics.tracer.timeouts.Load(), poolOnly)
		return nil
	}, connectionCount, connectionMax, pendingRequests, timeouts)
}
