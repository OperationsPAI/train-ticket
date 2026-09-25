import { metrics, type Meter } from "@opentelemetry/api";
import { otelMetricsEnabled } from "@trainticket/ts-kit";

/**
 * Bounded state vocabulary for waitlist intent population.
 *
 * The wire contract (`docs/07-observability/business-metrics.md`) fixes the
 * label set at `state` and `travel_class`. Both are closed enums owned here;
 * anything the database cannot map collapses to `other` rather than being
 * copied through, so an unexpected status can never mint a new series.
 */
export type WaitlistIntentState =
  | "draft"
  | "queued"
  | "matching"
  | "fulfilled"
  | "expired"
  | "cancelled"
  | "suspended"
  | "closed"
  | "other";

/**
 * Bounded outcome vocabulary for waitlist promotion attempts and terminal
 * results. `claimed` and `fare_quoted`/`offer_created`/`order_created` are the
 * progressing transitions; `expired`/`suspended` are terminal for this attempt;
 * `failed` is a call that could not complete.
 */
export type WaitlistPromotionOutcome =
  | "claimed"
  | "fare_quoted"
  | "offer_created"
  | "order_created"
  | "expired"
  | "suspended"
  | "failed";

/**
 * Small owned reason vocabulary. `none` covers the happy path (no exceptional
 * reason), and every unmapped value falls back to `other`; exception text is
 * never used as a label.
 */
export type WaitlistPromotionReasonCode =
  | "none"
  | "capacity_claimed"
  | "offer_expired"
  | "deadline_passed"
  | "guarantee_insufficient"
  | "other";

/**
 * One durable population bucket: how many intents currently sit in a
 * (state, travel class) pair and how old the oldest of them is. It is derived
 * from persisted rows, never from process memory, so a restart reconstructs the
 * same series.
 */
export type WaitlistIntentMetricSample = Readonly<{
  state: WaitlistIntentState;
  travelClass: string;
  count: number;
  oldestCreatedAt: string | null;
}>;

export interface WaitlistIntentMetricSource {
  intentMetricSamples(): Promise<readonly WaitlistIntentMetricSample[]> | readonly WaitlistIntentMetricSample[];
}

/**
 * A waitlist intent is "actionable" while the promotion/expiry machinery may
 * still move it: queued entries wait for released capacity, matching entries
 * are mid-offer, suspended entries await an operator, and drafts are not yet
 * queued. Terminal states keep a population count but no age point.
 */
const ACTIONABLE_STATES: ReadonlySet<WaitlistIntentState> = new Set<WaitlistIntentState>([
  "draft",
  "queued",
  "matching",
  "suspended",
]);

const INTENT_STATES: Readonly<Record<string, WaitlistIntentState>> = {
  DRAFT: "draft",
  QUEUED: "queued",
  MATCHING: "matching",
  FULFILLED: "fulfilled",
  EXPIRED: "expired",
  CANCELLED: "cancelled",
  SUSPENDED: "suspended",
  CLOSED: "closed",
};

const TRAVEL_CLASSES: Readonly<Record<string, string>> = {
  BUSINESS_CLASS: "business_class",
  FIRST_CLASS: "first_class",
  SECOND_CLASS: "second_class",
  STANDING: "standing",
  SLEEPER_HARD: "sleeper_hard",
  SLEEPER_SOFT: "sleeper_soft",
};

const REASON_CODES: ReadonlySet<WaitlistPromotionReasonCode> = new Set<WaitlistPromotionReasonCode>([
  "none",
  "capacity_claimed",
  "offer_expired",
  "deadline_passed",
  "guarantee_insufficient",
  "other",
]);

export function waitlistIntentState(status: string): WaitlistIntentState {
  return INTENT_STATES[String(status).trim().toUpperCase()] ?? "other";
}

export function travelClassLabel(seatClass: string): string {
  return TRAVEL_CLASSES[String(seatClass).trim().toUpperCase()] ?? "other";
}

export function promotionReasonCode(value: string | undefined): WaitlistPromotionReasonCode {
  const normalized = value?.trim() as WaitlistPromotionReasonCode | undefined;
  return normalized && REASON_CODES.has(normalized) ? normalized : "other";
}

export interface WaitlistMetricRecorder {
  promotion(outcome: WaitlistPromotionOutcome, reasonCode: WaitlistPromotionReasonCode, travelClass: string): void;
}

export class NoopWaitlistMetrics implements WaitlistMetricRecorder {
  promotion(): void {}
}

export class OpenTelemetryWaitlistMetrics implements WaitlistMetricRecorder {
  private readonly promotions;

  constructor(meter: Meter, source: WaitlistIntentMetricSource) {
    const intents = meter.createObservableGauge("train_ticket.waitlist.intents", {
      unit: "{intent}",
      description: "Current waitlist intent population by bounded state and travel class.",
    });
    const oldestAge = meter.createObservableGauge("train_ticket.waitlist.oldest_age", {
      unit: "s",
      description: "Age in seconds of the oldest actionable waitlist intent by bounded state and travel class.",
    });
    // One durable read serves both gauges: the SDK invokes a batch callback once
    // per collection, so the population and the age cannot disagree about the
    // same snapshot or pay for two queries.
    meter.addBatchObservableCallback(async (result) => {
      const now = Date.now();
      for (const sample of await source.intentMetricSamples()) {
        result.observe(intents, sample.count, { state: sample.state, travel_class: sample.travelClass });
        if (!ACTIONABLE_STATES.has(sample.state) || !sample.oldestCreatedAt) continue;
        const created = Date.parse(sample.oldestCreatedAt);
        if (!Number.isFinite(created)) continue;
        result.observe(oldestAge, Math.max(0, (now - created) / 1000), { state: sample.state, travel_class: sample.travelClass });
      }
    }, [intents, oldestAge]);
    this.promotions = meter.createCounter("train_ticket.waitlist.promotions", {
      unit: "{promotion}",
      description: "Waitlist promotion attempts and terminal outcomes by bounded outcome and reason.",
    });
  }

  promotion(outcome: WaitlistPromotionOutcome, reasonCode: WaitlistPromotionReasonCode, travelClass: string): void {
    this.promotions.add(1, { outcome, reason_code: reasonCode, travel_class: travelClass });
  }
}

const noopMetrics = new NoopWaitlistMetrics();
let configuredMetrics: WaitlistMetricRecorder | undefined;

/**
 * Installs the process-wide recorder once, backed by the service's durable
 * intent source. Metrics stay off (noop) unless OTLP metrics are enabled, the
 * same gate every other TypeScript service uses.
 */
export function configureWaitlistMetricsFromEnvironment(source: WaitlistIntentMetricSource): WaitlistMetricRecorder {
  if (configuredMetrics) return configuredMetrics;
  configuredMetrics = otelMetricsEnabled()
    ? new OpenTelemetryWaitlistMetrics(metrics.getMeter(process.env.OTEL_SERVICE_NAME ?? "waitlist"), source)
    : new NoopWaitlistMetrics();
  return configuredMetrics;
}

/**
 * The recorder the promotion machinery uses. Defaults to noop so unit tests
 * that construct the application directly neither register instruments nor
 * require a configured meter.
 */
export function waitlistMetrics(): WaitlistMetricRecorder {
  return configuredMetrics ?? noopMetrics;
}
