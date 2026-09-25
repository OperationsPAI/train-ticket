// Package collector reads the transport state of every Train Ticket event
// stream and publishes it as bounded business metrics.
//
// It exists as one process on purpose. Every subscriber used to answer "am I
// keeping up" for itself, which meant N copies of the same XINFO call per poll
// and a different answer per replica; the transport state belongs to Redis, so
// one reporter reads it once and everyone queries the metric instead.
package collector

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

// EventStreamPrefix is the namespace every business stream shares.
const EventStreamPrefix = "events:"

// DLQSuffix is what a dead-letter stream appends to its source stream.
const DLQSuffix = ":dlq"

// UnknownLabel is the value used when a bounded dimension cannot be attributed.
// It is deliberately a literal rather than an empty string so the series exists
// and its meaning is explicit.
const UnknownLabel = "unknown"

// GroupState is one consumer group's progress on one stream.
type GroupState struct {
	Stream        string
	ConsumerGroup string
	Lag           int64
	Pending       int64
	OldestAge     time.Duration
	HasOldest     bool
}

// DeadLetterState is the current dead-letter population of one event type,
// reported by one consumer group.
type DeadLetterState struct {
	Stream        string
	ConsumerGroup string
	EventType     string
	Entries       int64
}

// Snapshot is one complete read of the transport state.
type Snapshot struct {
	Groups    []GroupState
	DeadLetters []DeadLetterState
}

// StreamKind separates a business stream from its dead-letter companion.
type StreamKind int

const (
	BusinessStream StreamKind = iota
	DeadLetterStream
)

// ClassifyStream decides whether a Redis key is a business stream or a
// dead-letter stream, and returns the business stream name either way.
//
// It returns false for anything outside the `events:` namespace, so a key that
// some other component created cannot become a metric label.
func ClassifyStream(key string) (stream string, kind StreamKind, ok bool) {
	if !strings.HasPrefix(key, EventStreamPrefix) || len(key) == len(EventStreamPrefix) {
		return "", BusinessStream, false
	}
	if strings.HasSuffix(key, DLQSuffix) {
		source := strings.TrimSuffix(key, DLQSuffix)
		if len(source) == len(EventStreamPrefix) {
			return "", BusinessStream, false
		}
		return source, DeadLetterStream, true
	}
	return key, BusinessStream, true
}

// EntryIDMillis extracts the millisecond timestamp Redis encodes into a stream
// entry id (`<milliseconds>-<sequence>`). It returns false for anything that is
// not a well-formed id, so a malformed id is skipped rather than reported as an
// age of fifty years.
func EntryIDMillis(id string) (int64, bool) {
	dash := strings.IndexByte(id, '-')
	if dash <= 0 {
		return 0, false
	}
	millis, err := strconv.ParseInt(id[:dash], 10, 64)
	if err != nil || millis <= 0 {
		return 0, false
	}
	return millis, true
}

// EntryAge is how long ago the entry was appended, measured from its id.
func EntryAge(id string, now time.Time) (time.Duration, bool) {
	millis, ok := EntryIDMillis(id)
	if !ok {
		return 0, false
	}
	age := now.Sub(time.UnixMilli(millis))
	if age < 0 {
		age = 0
	}
	return age, true
}

// nextEntryAfter builds the exclusive lower bound that asks Redis for the
// entries a group has not delivered yet.
func nextEntryAfter(lastDeliveredID string) string {
	if lastDeliveredID == "" {
		lastDeliveredID = "0-0"
	}
	return "(" + lastDeliveredID
}

// Options bound what one poll may read.
type Options struct {
	// DeadLetterScanLimit caps how many dead-letter entries are read to
	// attribute them to an event type and consumer group. Anything beyond it is
	// counted as unattributed instead of read, so a pathological dead-letter
	// backlog cannot turn a poll into a full stream read.
	DeadLetterScanLimit int64
}

// DefaultOptions are the poll bounds the chart runs with.
func DefaultOptions() Options {
	return Options{DeadLetterScanLimit: 500}
}

// Collect reads every stream's consumer-group state and dead-letter population.
//
// A single stream that fails to answer does not fail the poll: the transport is
// shared, and one dropped stream must not blind the other thirty-nine. The
// errors are returned so the caller can log them, but the snapshot is still
// usable.
func Collect(ctx context.Context, client redis.UniversalClient, options Options) (Snapshot, error) {
	if options.DeadLetterScanLimit <= 0 {
		options = DefaultOptions()
	}
	keys, err := scanStreamKeys(ctx, client)
	if err != nil {
		return Snapshot{}, err
	}
	now := time.Now()
	var snapshot Snapshot
	var failures []error
	for _, key := range keys {
		stream, kind, ok := ClassifyStream(key)
		if !ok {
			continue
		}
		if kind == DeadLetterStream {
			deadLetters, err := readDeadLetters(ctx, client, stream, key, options)
			if err != nil {
				failures = append(failures, fmt.Errorf("dead letters for %s: %w", stream, err))
				continue
			}
			snapshot.DeadLetters = append(snapshot.DeadLetters, deadLetters...)
			continue
		}
		groups, err := readGroups(ctx, client, stream, now)
		if err != nil {
			failures = append(failures, fmt.Errorf("groups for %s: %w", stream, err))
			continue
		}
		snapshot.Groups = append(snapshot.Groups, groups...)
	}
	sortSnapshot(&snapshot)
	return snapshot, errors.Join(failures...)
}

func scanStreamKeys(ctx context.Context, client redis.UniversalClient) ([]string, error) {
	var keys []string
	var cursor uint64
	for {
		batch, next, err := client.Scan(ctx, cursor, EventStreamPrefix+"*", 500).Result()
		if err != nil {
			return nil, err
		}
		keys = append(keys, batch...)
		cursor = next
		if cursor == 0 {
			break
		}
	}
	sort.Strings(keys)
	return keys, nil
}

func readGroups(ctx context.Context, client redis.UniversalClient, stream string, now time.Time) ([]GroupState, error) {
	groups, err := client.XInfoGroups(ctx, stream).Result()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			return nil, nil
		}
		return nil, err
	}
	states := make([]GroupState, 0, len(groups))
	for _, group := range groups {
		state := GroupState{
			Stream:        stream,
			ConsumerGroup: group.Name,
			Lag:           group.Lag,
			Pending:       group.Pending,
		}
		if state.Lag < 0 {
			state.Lag = 0
		}
		oldest, ok, err := oldestUndelivered(ctx, client, stream, group)
		if err != nil {
			return nil, err
		}
		if ok {
			if age, ok := EntryAge(oldest, now); ok {
				state.OldestAge = age
				state.HasOldest = true
			}
		}
		states = append(states, state)
	}
	return states, nil
}

// oldestUndelivered returns the id of the oldest entry this group still owes.
//
// Two things can be owed: an entry the group has not been handed at all (after
// its last delivered id) and an entry that was handed out and never
// acknowledged (its pending list). Both are bounded reads: one entry from each
// end, never a range over the stream.
func oldestUndelivered(ctx context.Context, client redis.UniversalClient, stream string, group redis.XInfoGroup) (string, bool, error) {
	oldest := ""
	if group.Pending > 0 {
		pending, err := client.XPending(ctx, stream, group.Name).Result()
		if err != nil && !errors.Is(err, redis.Nil) {
			return "", false, err
		}
		if pending != nil && pending.Lower != "" {
			oldest = pending.Lower
		}
	}
	if group.Lag > 0 || (group.Lag == 0 && oldest == "") {
		entries, err := client.XRangeN(ctx, stream, nextEntryAfter(group.LastDeliveredID), "+", 1).Result()
		if err != nil && !errors.Is(err, redis.Nil) {
			return "", false, err
		}
		if len(entries) > 0 {
			if oldest == "" || earlier(entries[0].ID, oldest) {
				oldest = entries[0].ID
			}
		}
	}
	return oldest, oldest != "", nil
}

func earlier(left, right string) bool {
	leftMillis, leftOK := EntryIDMillis(left)
	rightMillis, rightOK := EntryIDMillis(right)
	if !leftOK || !rightOK {
		return left < right
	}
	return leftMillis < rightMillis
}

func readDeadLetters(ctx context.Context, client redis.UniversalClient, stream, key string, options Options) ([]DeadLetterState, error) {
	total, err := client.XLen(ctx, key).Result()
	if err != nil {
		return nil, err
	}
	if total == 0 {
		return nil, nil
	}
	scanned := options.DeadLetterScanLimit
	if scanned > total {
		scanned = total
	}
	entries, err := client.XRangeN(ctx, key, "-", "+", scanned).Result()
	if err != nil {
		return nil, err
	}
	counts := map[DeadLetterState]int64{}
	for _, entry := range entries {
		counts[deadLetterLabel(stream, entry.Values)]++
	}
	if unattributed := total - scanned; unattributed > 0 {
		counts[DeadLetterState{Stream: stream, ConsumerGroup: UnknownLabel, EventType: UnknownLabel}] += unattributed
	}
	states := make([]DeadLetterState, 0, len(counts))
	for state, count := range counts {
		state.Entries = count
		states = append(states, state)
	}
	return states, nil
}

// deadLetterLabel reads the two bounded dimensions a dead-letter entry carries.
// Both come from closed vocabularies -- the consumer group that failed and the
// event type of the envelope -- and anything unreadable collapses to "unknown"
// rather than becoming a label of its own.
func deadLetterLabel(stream string, values map[string]any) DeadLetterState {
	group := UnknownLabel
	if raw, ok := values["consumerGroup"].(string); ok && strings.TrimSpace(raw) != "" {
		group = strings.TrimSpace(raw)
	}
	eventType := UnknownLabel
	if raw, ok := values["envelope"].(string); ok {
		var envelope struct {
			EventType string `json:"eventType"`
		}
		if err := json.Unmarshal([]byte(raw), &envelope); err == nil && strings.TrimSpace(envelope.EventType) != "" {
			eventType = strings.TrimSpace(envelope.EventType)
		}
	}
	return DeadLetterState{Stream: stream, ConsumerGroup: group, EventType: eventType}
}

func sortSnapshot(snapshot *Snapshot) {
	sort.Slice(snapshot.Groups, func(i, j int) bool {
		if snapshot.Groups[i].Stream != snapshot.Groups[j].Stream {
			return snapshot.Groups[i].Stream < snapshot.Groups[j].Stream
		}
		return snapshot.Groups[i].ConsumerGroup < snapshot.Groups[j].ConsumerGroup
	})
	sort.Slice(snapshot.DeadLetters, func(i, j int) bool {
		left, right := snapshot.DeadLetters[i], snapshot.DeadLetters[j]
		if left.Stream != right.Stream {
			return left.Stream < right.Stream
		}
		if left.ConsumerGroup != right.ConsumerGroup {
			return left.ConsumerGroup < right.ConsumerGroup
		}
		return left.EventType < right.EventType
	})
}
