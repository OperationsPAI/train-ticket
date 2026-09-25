import { metrics, type Meter } from "@opentelemetry/api";
import { otelMetricsEnabled } from "@trainticket/ts-kit";

import type { ChannelType, DeliveryReceiptOutcome } from "./domain.js";

export type NotificationDeliveryMetricOutcome =
  | "delivered"
  | "failed"
  | "unknown"
  | "retry_scheduled"
  | "fallback_scheduled";
export type NotificationRetryMetricOutcome = "scheduled" | "deferred" | "exhausted";
export type NotificationPolicyMetricOutcome = "cancelled" | "kept" | "error";
export type NotificationPolicyAction = "expire" | "supersede";
export type NotificationQueueObservation = Readonly<{
  state: string;
  channel: string;
  tasks: number;
  oldestAgeSeconds: number;
}>;
export type NotificationReasonCode =
  | "none"
  | "recipient_quota"
  | "provider_rejected"
  | "provider_timeout"
  | "send_lease_expired"
  | "claim_expired"
  | "ttl_action_required"
  | "ttl_order_state"
  | "ttl_operational"
  | "ttl_financial"
  | "ttl_default"
  | "newer_terminal_state"
  | "other";

export interface NotificationMetricRecorder {
  deliveryResult(channel: ChannelType, outcome: NotificationDeliveryMetricOutcome, reasonCode: NotificationReasonCode): void;
  retry(channel: ChannelType, outcome: NotificationRetryMetricOutcome, reasonCode: NotificationReasonCode): void;
  policyAction(action: NotificationPolicyAction, channel: ChannelType, outcome: NotificationPolicyMetricOutcome, reasonCode: NotificationReasonCode): void;
  /**
   * Replace the queue depth snapshot the observable gauges report.
   *
   * A level, not a delta: the queue depth is durable state owned by the
   * database, so the value survives a process restart and cannot be mistaken
   * for a counter reset. One sample per state and channel; a state that stops
   * being observed goes stale in Prometheus rather than reading as zero.
   */
  queueDepth(samples: readonly NotificationQueueObservation[]): void;
}

export class NoopNotificationMetrics implements NotificationMetricRecorder {
  deliveryResult(): void {}
  retry(): void {}
  policyAction(): void {}
  queueDepth(): void {}
}

export class OpenTelemetryNotificationMetrics implements NotificationMetricRecorder {
  private readonly deliveryResults;
  private readonly retries;
  private readonly policyActions;
  private readonly queueTasks;
  private readonly queueOldestAge;
  private queueSamples: readonly NotificationQueueObservation[] = [];

  constructor(meter: Meter) {
    this.deliveryResults = meter.createCounter("train_ticket.notification.delivery_results", {
      unit: "{result}",
      description: "Durable notification delivery results by bounded channel, outcome, and reason.",
    });
    this.retries = meter.createCounter("train_ticket.notification.retries", {
      unit: "{retry}",
      description: "Notification retries and quota deferrals by bounded channel, outcome, and reason.",
    });
    this.policyActions = meter.createCounter("train_ticket.notification.policy_actions", {
      unit: "{action}",
      description: "Notification expiry and supersession policy decisions.",
    });
    this.queueTasks = meter.createObservableGauge("train_ticket.notification.queue.tasks", {
      unit: "{task}",
      description: "Durable notification delivery queue depth by state and channel.",
    });
    this.queueOldestAge = meter.createObservableGauge("train_ticket.notification.queue.oldest_age", {
      unit: "s",
      description: "Age of the oldest due notification task, the primary user-delay signal.",
    });
    this.queueTasks.addCallback(result => {
      for (const sample of this.queueSamples) result.observe(sample.tasks, { state: sample.state, channel: sample.channel });
    });
    this.queueOldestAge.addCallback(result => {
      for (const sample of this.queueSamples) result.observe(sample.oldestAgeSeconds, { state: sample.state, channel: sample.channel });
    });
  }

  deliveryResult(channel: ChannelType, outcome: NotificationDeliveryMetricOutcome, reasonCode: NotificationReasonCode): void {
    this.deliveryResults.add(1, { channel, outcome, reason_code: reasonCode });
  }

  retry(channel: ChannelType, outcome: NotificationRetryMetricOutcome, reasonCode: NotificationReasonCode): void {
    this.retries.add(1, { channel, outcome, reason_code: reasonCode });
  }

  policyAction(action: NotificationPolicyAction, channel: ChannelType, outcome: NotificationPolicyMetricOutcome, reasonCode: NotificationReasonCode): void {
    this.policyActions.add(1, { action, channel, outcome, reason_code: reasonCode });
  }

  queueDepth(samples: readonly NotificationQueueObservation[]): void {
    this.queueSamples = samples.map(sample => ({ ...sample }));
  }
}

let configuredMetrics: NotificationMetricRecorder | undefined;

export function notificationMetricsFromEnvironment(): NotificationMetricRecorder {
  if (configuredMetrics) return configuredMetrics;
  configuredMetrics = otelMetricsEnabled()
    ? new OpenTelemetryNotificationMetrics(metrics.getMeter(process.env.OTEL_SERVICE_NAME ?? "notification"))
    : new NoopNotificationMetrics();
  return configuredMetrics;
}

export function deliveryReasonCode(outcome: DeliveryReceiptOutcome, providerCode?: string): NotificationReasonCode {
  const normalized = providerCode?.trim().toUpperCase();
  if (normalized === "SEND_LEASE_EXPIRED") return "send_lease_expired";
  if (outcome === "Rejected") return "provider_rejected";
  if (outcome === "Timeout") return "provider_timeout";
  if (outcome === "Delivered") return "none";
  return "other";
}
