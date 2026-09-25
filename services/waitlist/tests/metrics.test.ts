import assert from "node:assert/strict";
import test from "node:test";

import {
  AggregationTemporality,
  InMemoryMetricExporter,
  MeterProvider,
  PeriodicExportingMetricReader,
  type MetricData,
} from "@opentelemetry/sdk-metrics";

import { InMemoryWaitlistRepository } from "../src/application.js";
import { WaitlistEntry, type FareClassInput, type WaitlistEntrySnapshot, type WaitlistStatus } from "../src/domain.js";
import {
  OpenTelemetryWaitlistMetrics,
  promotionReasonCode,
  travelClassLabel,
  waitlistIntentState,
} from "../src/metrics.js";

/**
 * Builds a fresh meter provider per call so a test can stand up a second
 * recorder over the same durable repository and prove the gauge is reconstructed
 * after a restart rather than carried in process memory.
 */
async function collect(recorder: (provider: MeterProvider) => void): Promise<Map<string, MetricData>> {
  const exporter = new InMemoryMetricExporter(AggregationTemporality.CUMULATIVE);
  const reader = new PeriodicExportingMetricReader({ exporter, exportIntervalMillis: 60_000 });
  const provider = new MeterProvider({ readers: [reader] });
  try {
    recorder(provider);
    await reader.forceFlush();
    const byName = new Map<string, MetricData>();
    for (const resource of exporter.getMetrics()) {
      for (const scope of resource.scopeMetrics) {
        for (const metric of scope.metrics) byName.set(metric.descriptor.name, metric);
      }
    }
    return byName;
  } finally {
    await provider.shutdown();
  }
}

function gaugeBuckets(metrics: Map<string, MetricData>, name: string): Map<string, number> {
  const metric = metrics.get(name);
  assert.ok(metric, `missing ${name}`);
  const byBucket = new Map<string, number>();
  for (const point of metric.dataPoints) {
    byBucket.set(`${point.attributes.state}|${point.attributes.travel_class}`, Number(point.value));
  }
  return byBucket;
}

function entryInState(
  entryId: string,
  travelerRef: string,
  seatClass: FareClassInput,
  status: WaitlistStatus,
  createdAt: Date,
): WaitlistEntry {
  const created = WaitlistEntry.create({
    entryId,
    accountId: `acc-${entryId}`,
    travelerRefs: [travelerRef],
    segmentRef: "seg",
    departureDate: "2026-07-20",
    seatClass,
    priority: { groupSize: 1, fareClass: seatClass },
    createdAt,
  });
  const snapshot: WaitlistEntrySnapshot = { ...created.toSnapshot(0), status, createdAt: createdAt.toISOString() };
  return WaitlistEntry.fromSnapshot(snapshot);
}

test("unknown statuses, travel classes and reasons collapse to a bounded fallback", () => {
  assert.equal(waitlistIntentState("QUEUED"), "queued");
  assert.equal(waitlistIntentState("something-new"), "other");
  assert.equal(travelClassLabel("SECOND_CLASS"), "second_class");
  assert.equal(travelClassLabel("SLEEPER_SOFT"), "sleeper_soft");
  assert.equal(travelClassLabel("HYPERLOOP"), "other");
  assert.equal(promotionReasonCode("offer_expired"), "offer_expired");
  assert.equal(promotionReasonCode("dynamic-provider-detail"), "other");
  assert.equal(promotionReasonCode(undefined), "other");
});

test("two different waitlist intents collapse to one bounded label set", async () => {
  const repository = new InMemoryWaitlistRepository();
  const createdAt = new Date(Date.now() - 30_000);
  await repository.add(entryInState("wlr-1", "tvl-1", "SECOND_CLASS", "QUEUED", createdAt));
  await repository.add(entryInState("wlr-2", "tvl-2", "SECOND_CLASS", "QUEUED", createdAt));

  const travelClass = travelClassLabel("SECOND_CLASS");
  const metrics = await collect((provider) => {
    const recorder = new OpenTelemetryWaitlistMetrics(provider.getMeter("waitlist-test"), repository);
    // Two promotions for two different intents must not create a second series.
    recorder.promotion("order_created", "none", travelClass);
    recorder.promotion("order_created", "none", travelClass);
  });

  const intents = metrics.get("train_ticket.waitlist.intents");
  assert.ok(intents);
  const points = [...intents.dataPoints];
  assert.equal(points.length, 1, "two intents in the same bucket must share one series");
  assert.equal(Number(points[0]?.value), 2);
  assert.deepEqual(Object.keys(points[0]!.attributes).sort(), ["state", "travel_class"]);
  assert.equal(points[0]!.attributes.state, "queued");
  assert.equal(points[0]!.attributes.travel_class, "second_class");
  for (const value of Object.values(points[0]!.attributes)) {
    assert.ok(!String(value).includes("wlr-") && !String(value).includes("tvl-"), "label leaked a business identifier");
  }

  const promotions = metrics.get("train_ticket.waitlist.promotions");
  assert.ok(promotions);
  const promotionPoints = [...promotions.dataPoints];
  assert.equal(promotionPoints.length, 1, "identical labels must aggregate into one counter series");
  assert.equal(Number(promotionPoints[0]?.value), 2);
  assert.deepEqual(Object.keys(promotionPoints[0]!.attributes).sort(), ["outcome", "reason_code", "travel_class"]);
});

test("intent population gauge is reconstructed from durable state after a restart", async () => {
  const repository = new InMemoryWaitlistRepository();
  const createdAt = new Date("2026-01-01T00:00:00.000Z");
  await repository.add(entryInState("wlr-a", "tvl-a", "SECOND_CLASS", "QUEUED", createdAt));
  await repository.add(entryInState("wlr-b", "tvl-b", "SECOND_CLASS", "QUEUED", createdAt));
  await repository.add(entryInState("wlr-c", "tvl-c", "SLEEPER_HARD", "MATCHING", createdAt));
  await repository.add(entryInState("wlr-d", "tvl-d", "SLEEPER_HARD", "EXPIRED", createdAt));

  // First process: a recorder that only reads the durable repository.
  const first = await collect((provider) => void new OpenTelemetryWaitlistMetrics(provider.getMeter("waitlist-test"), repository));
  // Second process: a brand-new recorder and meter over the same repository.
  const restarted = await collect((provider) => void new OpenTelemetryWaitlistMetrics(provider.getMeter("waitlist-test"), repository));

  const expected = new Map([
    ["queued|second_class", 2],
    ["matching|sleeper_hard", 1],
    ["expired|sleeper_hard", 1],
  ]);
  assert.deepEqual(gaugeBuckets(first, "train_ticket.waitlist.intents"), expected);
  assert.deepEqual(gaugeBuckets(restarted, "train_ticket.waitlist.intents"), expected);
});

test("oldest actionable intent age is reconstructed from durable state after a restart", async () => {
  const repository = new InMemoryWaitlistRepository();
  const now = Date.now();
  const oldest = new Date(now - 120_000);
  const newer = new Date(now - 10_000);
  await repository.add(entryInState("wlr-old", "tvl-old", "SECOND_CLASS", "QUEUED", oldest));
  await repository.add(entryInState("wlr-new", "tvl-new", "SECOND_CLASS", "QUEUED", newer));
  await repository.add(entryInState("wlr-done", "tvl-done", "SECOND_CLASS", "FULFILLED", oldest));

  for (const collected of [
    await collect((provider) => void new OpenTelemetryWaitlistMetrics(provider.getMeter("waitlist-test"), repository)),
    await collect((provider) => void new OpenTelemetryWaitlistMetrics(provider.getMeter("waitlist-test"), repository)),
  ]) {
    const ages = gaugeBuckets(collected, "train_ticket.waitlist.oldest_age");
    assert.equal(ages.size, 1, "only actionable states expose an age point");
    const age = ages.get("queued|second_class");
    assert.ok(age !== undefined && age >= 119 && age <= 135, `unexpected oldest age ${age}`);
    assert.equal(ages.has("fulfilled|second_class"), false, "a terminal state must not expose an actionable age");
  }
});
