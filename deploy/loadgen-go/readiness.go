package main

import (
	"context"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"
)

// Waiting for the services bootstrap writes through, before writing through
// them.
//
// The init container waits for Postgres and Redis and then says "loadgen will
// retry service connections internally", which the journey path does: a
// customer journey that fails is one iteration of a loop that runs again a
// second later. Bootstrap is not on that loop. It runs once, at startup, and
// the inventory it creates is what every later search reads.
//
// Measured on the resident deployment: the loadgen started 4 seconds before
// place-network accepted connections, Bootstrap's first request came back
// `dial tcp: connect refused`, it returned that error, and the run continued
// for 27 minutes with 8 places and no routes. 45420 purchase and 33330 browse
// journeys failed on `available-train: no routes and fewer than 2 places`, and
// the journey record named the cause on its first line while the aggregate
// statistics showed nothing but a wall of failures.
//
// So the gate is here rather than in the init container. The loadgen knows
// which services its own bootstrap depends on, and that list moves with the
// code that uses them.

// bootstrapServices are the services Bootstrap writes to or reads from, in the
// order it first touches each.
//
// Derived from bootstrap.go rather than from the chart's full service list: a
// gate on all 38 would wait for services this run does not need before it
// could create a single place, and one of them being slow to start is not a
// reason to have no inventory.
var bootstrapServices = []string{
	"place-network",
	"service-plan",
	"trip-planning",
}

// bootstrapWaitTimeout is how long the gate waits before attempting anyway.
//
// Sized against the Deployment's own readinessProbe, which has
// initialDelaySeconds 10 and periodSeconds 10, so a service that starts
// cleanly is ready within roughly 20 seconds and one still unready at two
// minutes is not merely slow. Waiting longer would delay every run for a
// service that is never coming up, and the attempt that follows records what
// happened either way.
const bootstrapWaitTimeout = 2 * time.Minute

// bootstrapWaitInterval is how often the gate asks, and also each request's own
// timeout.
//
// One second rather than the probe's ten: this runs once at startup and the
// cost of asking is a refused connection, while every second spent waiting
// after the services are up is a second of the run measuring a system that was
// ready.
const bootstrapWaitInterval = time.Second

// readinessPath is the endpoint every service in the chart exposes, and the
// same one the Deployment's readinessProbe reads.
//
// /readyz and not /healthz: a service answering /healthz has a process, while
// one answering /readyz has its database and its migrations. Bootstrap writes
// on its first call, so a process without a schema would fail it just as a
// refused connection does.
const readinessPath = "/readyz"

// AwaitServices blocks until every named service answers its readiness
// endpoint, or until the deadline passes.
//
// Returns the services still not ready when it gave up, so the caller can say
// which ones in the record it writes. An empty result means all of them
// answered.
//
// Polled rather than resolved once. A Service exists in DNS before any pod
// backs it, so name resolution succeeding says nothing about whether a request
// will be served.
func AwaitServices(ctx context.Context, template string, services []string,
	timeout, interval time.Duration) []string {
	client := &http.Client{Timeout: interval}
	deadline := time.Now().Add(timeout)
	waiting := make(map[string]bool, len(services))
	for _, service := range services {
		waiting[service] = true
	}

	for {
		for service := range waiting {
			if serviceReady(ctx, client, template, service) {
				delete(waiting, service)
			}
		}
		if len(waiting) == 0 {
			return nil
		}
		if time.Now().After(deadline) {
			return stillWaiting(waiting)
		}
		select {
		case <-ctx.Done():
			return stillWaiting(waiting)
		case <-time.After(interval):
		}
	}
}

// serviceReady reports whether one service answers its readiness endpoint with
// a success status.
//
// Every failure is the same answer here, and none is recorded. A refused
// connection, an unresolvable name and a 503 all mean "not yet", and putting
// them in the statistics would report a startup wait as a wall of errors.
func serviceReady(ctx context.Context, client *http.Client, template, service string) bool {
	address := fmt.Sprintf(template, service) + readinessPath
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return false
	}
	response, err := client.Do(request)
	if err != nil {
		return false
	}
	// Drained and closed so the connection returns to the pool. A gate polling
	// three services every second for two minutes would otherwise leave 360
	// sockets in CLOSE_WAIT for the run that follows.
	defer response.Body.Close()
	return response.StatusCode >= 200 && response.StatusCode < 300
}

// stillWaiting returns the names a gate has not yet seen answer, in a stable
// order, so the services named in a timeout message do not reorder between
// runs.
func stillWaiting(set map[string]bool) []string {
	out := make([]string, 0, len(set))
	for key := range set {
		out = append(out, key)
	}
	sort.Strings(out)
	return out
}

// describeWaiting renders the services a gate gave up on, for one line of
// output.
func describeWaiting(services []string) string {
	return strings.Join(services, ", ")
}
