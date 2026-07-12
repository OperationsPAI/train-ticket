/**
 * Notification domain foundation — TypeScript
 *
 * Aggregates:
 *   NotificationTask  — lifecycle of a single notification send
 *   Template          — versioned notification template with variable schema and channel adaptations
 *   RecipientPolicy   — per-intent rules: allowed channels, fallback order, throttle, bypass-consent
 *   DeliveryReceipt   — immutable appended fact recording a delivery outcome
 *
 * Key invariants:
 *   1. Notification failure never rolls back business state.
 *   2. Transaction-required notifications bypass ordinary user notification preferences.
 *   3. Same trigger event + recipient + template yields at most one task (send idempotency).
 *   4. Delivery receipts are appended facts; a receipt never mutates the task's request payload.
 */

import { newEventId, uuidV7 } from "@trainticket/ts-kit";

// ─── Error ────────────────────────────────────────────────────────────────────

export class DomainError extends Error {
  public readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.name = "DomainError";
    this.code = code;
  }
}

// ─── ID types ──────────────────────────────────────────────────────────────────

export type NotificationTaskId = string; // "nt-<uuid>"
export type TemplateId = string;         // "tpl-<uuid>"
export type TemplateCode = string;       // human-readable key, e.g. "order_confirmed"
export type RecipientRef = string;       // "tvl-<uuid>" or "usr-<uuid>"
export type ChannelType = "EMAIL" | "SMS" | "PUSH" | "IN_APP";
export type NotificationChannel = Exclude<ChannelType, "IN_APP">;
export type IntentType = string;         // e.g. "PAYMENT_RESULT", "TICKET_ISSUED", "REFUND_SETTLED"
export type EventId = string;            // "evt-<uuid>"
export type ReceiptId = string;          // "rct-<uuid>"
export type CausationId = string;        // "cmd-<uuid>" or "evt-<uuid>"
export type CorrelationId = string;      // "corr-<uuid>"

// ─── Enums ─────────────────────────────────────────────────────────────────────

export type NotificationTaskStatus =
  | "Planned"
  | "Authorized"
  | "Delivering"
  | "Delivered"
  | "Failed"
  | "Cancelled";

export type TemplateStatus =
  | "Draft"
  | "Validated"
  | "Published"
  | "Retired";

export type DeliveryReceiptOutcome =
  | "Delivered"
  | "Bounced"
  | "Rejected"
  | "Timeout"
  | "Expired";

export type DeliveryAttemptStatus = "SENT" | "DELIVERED" | "FAILED" | "BOUNCED";

export type NotificationStatus = "QUEUED" | "SENDING" | "SENT" | "DELIVERED" | "FAILED";

export type NotificationTemplateType =
  | "ORDER_CONFIRMED"
  | "PAYMENT_REMINDER"
  | "TICKET_ISSUED"
  | "DELAY_ALERT"
  | "REFUND_COMPLETED"
  | "WAITLIST_PROMOTED"
  | "DISRUPTION_REBOOK";

// ─── Value Objects ─────────────────────────────────────────────────────────────

export type Money = Readonly<{
  currency: string;
  minorUnits: number;
}>;

export type TemplateVariableSchema = Readonly<{
  required: readonly string[];
  optional: readonly string[];
}>;

export type ChannelAdaptation = Readonly<{
  channel: ChannelType;
  subjectTemplate?: string;
  bodyTemplate: string;
  preHeader?: string;
}>;

// ─── Commands ──────────────────────────────────────────────────────────────────

export type ScheduleNotification = Readonly<{
  notificationTaskId: NotificationTaskId;
  triggerEventId: EventId;
  triggerEventType: string;
  correlationId: CorrelationId;
  causationId?: CausationId;
  recipientRef: RecipientRef;
  templateCode: TemplateCode;
  channel: ChannelType;
  intent: IntentType;
  transactionRequired: boolean;
  variables: Readonly<Record<string, string>>;
  scheduledAt: Date;
  triggerBusinessRef?: string;
}>;

export type DispatchNotification = Readonly<{
  notificationTaskId: NotificationTaskId;
  dispatchedAt: Date;
}>;

export type RecordDeliveryReceipt = Readonly<{
  receiptId: ReceiptId;
  notificationTaskId: NotificationTaskId;
  channel: ChannelType;
  outcome: DeliveryReceiptOutcome;
  providerCode?: string;
  providerMessage?: string;
  recordedAt: Date;
  finalFailure?: boolean;
}>;

export type CancelNotification = Readonly<{
  notificationTaskId: NotificationTaskId;
  reason: string;
  cancelledAt: Date;
}>;

export type RetryNotification = Readonly<{
  notificationTaskId: NotificationTaskId;
  retriedAt: Date;
}>;

// ─── Domain Events ─────────────────────────────────────────────────────────────

export type NotificationScheduled = Readonly<{
  type: "NotificationScheduled";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  notificationTaskId: NotificationTaskId;
  templateCode: TemplateCode;
  recipientRef: RecipientRef;
  channel: ChannelType;
  intent: IntentType;
  transactionRequired: boolean;
  scheduledAt: Date;
}>;

export type NotificationDispatched = Readonly<{
  type: "NotificationDispatched";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  notificationTaskId: NotificationTaskId;
  channel: ChannelType;
  dispatchedAt: Date;
}>;

export type NotificationDelivered = Readonly<{
  type: "NotificationDelivered";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  notificationTaskId: NotificationTaskId;
  channel: ChannelType;
  receiptId: ReceiptId;
  outcome: DeliveryReceiptOutcome;
  deliveredAt: Date;
}>;

export type NotificationFailed = Readonly<{
  type: "NotificationFailed";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  notificationTaskId: NotificationTaskId;
  channel: ChannelType;
  receiptId: ReceiptId;
  outcome: DeliveryReceiptOutcome;
  providerCode?: string;
  providerMessage?: string;
  failedAt: Date;
}>;

export type NotificationCancelled = Readonly<{
  type: "NotificationCancelled";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  notificationTaskId: NotificationTaskId;
  reason: string;
  cancelledAt: Date;
}>;

export type NotificationDomainEvent =
  | NotificationScheduled
  | NotificationDispatched
  | NotificationDelivered
  | NotificationFailed
  | NotificationCancelled;

// ─── NotificationTask Aggregate ────────────────────────────────────────────────

export type NotificationTaskSnapshot = Readonly<{
  notificationTaskId: NotificationTaskId;
  status: NotificationTaskStatus;
  triggerEventId: EventId;
  triggerEventType: string;
  correlationId: CorrelationId;
  causationId?: CausationId;
  recipientRef: RecipientRef;
  templateCode: TemplateCode;
  channel: ChannelType;
  intent: IntentType;
  transactionRequired: boolean;
  variables: Readonly<Record<string, string>>;
  scheduledAt: Date;
  triggerBusinessRef?: string;
  dispatchedAt?: Date;
  cancelledAt?: Date;
  cancelReason?: string;
  receipts: readonly DeliveryReceiptSnapshot[];
}>;

export class NotificationTask {
  private constructor(private readonly snapshot: NotificationTaskSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static schedule(
    command: ScheduleNotification,
  ): { task: NotificationTask; event: NotificationScheduled } {
    validateScheduleCommand(command);

    const snapshot: NotificationTaskSnapshot = deepFreeze({
      notificationTaskId: command.notificationTaskId,
      status: "Planned" as const,
      triggerEventId: command.triggerEventId,
      triggerEventType: command.triggerEventType,
      correlationId: command.correlationId,
      causationId: command.causationId,
      recipientRef: command.recipientRef,
      templateCode: command.templateCode,
      channel: command.channel,
      intent: command.intent,
      transactionRequired: command.transactionRequired,
      variables: Object.freeze({ ...command.variables }),
      scheduledAt: new Date(command.scheduledAt),
      ...(command.triggerBusinessRef ? { triggerBusinessRef: command.triggerBusinessRef } : {}),
      receipts: Object.freeze([]),
    });

    const task = new NotificationTask(snapshot);

    const event: NotificationScheduled = deepFreeze({
      type: "NotificationScheduled" as const,
      eventId: newEventId(),
      eventType: "NotificationScheduled",
      schemaVersion: 1,
      occurredAt: new Date(command.scheduledAt),
      correlationId: command.correlationId,
      causationId: command.causationId,
      producer: "notification",
      notificationTaskId: snapshot.notificationTaskId,
      templateCode: snapshot.templateCode,
      recipientRef: snapshot.recipientRef,
      channel: snapshot.channel,
      intent: snapshot.intent,
      transactionRequired: snapshot.transactionRequired,
      scheduledAt: new Date(snapshot.scheduledAt),
    });

    return { task, event };
  }

  dispatch(
    command: DispatchNotification,
  ): { task: NotificationTask; event: NotificationDispatched } {
    if (this.snapshot.status !== "Planned" && this.snapshot.status !== "Authorized") {
      throw new DomainError(
        "TASK_NOT_DISPATCHABLE",
        `NotificationTask ${this.id} in ${this.snapshot.status} cannot be dispatched`,
      );
    }

    const newSnapshot: NotificationTaskSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Delivering" as const,
      dispatchedAt: new Date(command.dispatchedAt),
    });

    const event: NotificationDispatched = deepFreeze({
      type: "NotificationDispatched" as const,
      eventId: newEventId(),
      eventType: "NotificationDispatched",
      schemaVersion: 1,
      occurredAt: new Date(command.dispatchedAt),
      correlationId: this.snapshot.correlationId,
      causationId: this.snapshot.causationId,
      producer: "notification",
      notificationTaskId: this.snapshot.notificationTaskId,
      channel: this.snapshot.channel,
      dispatchedAt: new Date(command.dispatchedAt),
    });

    return { task: new NotificationTask(newSnapshot), event };
  }

  recordReceipt(
    command: RecordDeliveryReceipt,
  ): { task: NotificationTask; event: NotificationDelivered | NotificationFailed } {
    if (this.snapshot.status === "Delivered" || this.snapshot.status === "Cancelled") {
      throw new DomainError(
        "TASK_IN_TERMINAL_STATE",
        `NotificationTask ${this.id} is in terminal state ${this.snapshot.status}`,
      );
    }

    // Receipts are appended facts; they never mutate the request payload
    const receipt = DeliveryReceipt.record(command);
    const receipts = [...this.snapshot.receipts, receipt.toSnapshot()];

    const isDelivered = command.outcome === "Delivered";
    const newStatus: NotificationTaskStatus = isDelivered
      ? "Delivered"
      : command.finalFailure === false
        ? "Delivering"
        : "Failed";

    const newSnapshot: NotificationTaskSnapshot = deepFreeze({
      ...this.snapshot,
      status: newStatus,
      receipts: Object.freeze(receipts),
    });

    if (isDelivered) {
      const event: NotificationDelivered = deepFreeze({
        type: "NotificationDelivered" as const,
        eventId: newEventId(),
        eventType: "NotificationDelivered",
        schemaVersion: 1,
        occurredAt: new Date(command.recordedAt),
        correlationId: this.snapshot.correlationId,
        causationId: this.snapshot.causationId,
        producer: "notification",
        notificationTaskId: this.snapshot.notificationTaskId,
        channel: command.channel,
        receiptId: command.receiptId,
        outcome: command.outcome,
        deliveredAt: new Date(command.recordedAt),
      });
      return { task: new NotificationTask(newSnapshot), event };
    }

    const event: NotificationFailed = deepFreeze({
      type: "NotificationFailed" as const,
      eventId: newEventId(),
      eventType: "NotificationFailed",
      schemaVersion: 1,
      occurredAt: new Date(command.recordedAt),
      correlationId: this.snapshot.correlationId,
      causationId: this.snapshot.causationId,
      producer: "notification",
      notificationTaskId: this.snapshot.notificationTaskId,
      channel: command.channel,
      receiptId: command.receiptId,
      outcome: command.outcome,
      providerCode: command.providerCode,
      providerMessage: command.providerMessage,
      failedAt: new Date(command.recordedAt),
    });
    return { task: new NotificationTask(newSnapshot), event };
  }

  cancel(
    command: CancelNotification,
  ): { task: NotificationTask; event: NotificationCancelled } {
    if (this.snapshot.status === "Delivered" || this.snapshot.status === "Cancelled") {
      throw new DomainError(
        "TASK_NOT_CANCELLABLE",
        `NotificationTask ${this.id} in ${this.snapshot.status} cannot be cancelled`,
      );
    }

    const newSnapshot: NotificationTaskSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Cancelled" as const,
      cancelledAt: new Date(command.cancelledAt),
      cancelReason: command.reason,
    });

    const event: NotificationCancelled = deepFreeze({
      type: "NotificationCancelled" as const,
      eventId: newEventId(),
      eventType: "NotificationCancelled",
      schemaVersion: 1,
      occurredAt: new Date(command.cancelledAt),
      correlationId: this.snapshot.correlationId,
      causationId: this.snapshot.causationId,
      producer: "notification",
      notificationTaskId: this.snapshot.notificationTaskId,
      reason: command.reason,
      cancelledAt: new Date(command.cancelledAt),
    });

    return { task: new NotificationTask(newSnapshot), event };
  }

  get id(): NotificationTaskId {
    return this.snapshot.notificationTaskId;
  }

  get status(): NotificationTaskStatus {
    return this.snapshot.status;
  }

  get transactionRequired(): boolean {
    return this.snapshot.transactionRequired;
  }

  get recipientRef(): RecipientRef {
    return this.snapshot.recipientRef;
  }

  get templateCode(): TemplateCode {
    return this.snapshot.templateCode;
  }

  get triggerEventId(): EventId {
    return this.snapshot.triggerEventId;
  }

  toSnapshot(): NotificationTaskSnapshot {
    return deepFreeze(cloneForSnapshot(this.snapshot));
  }
}

// ─── Template Aggregate ────────────────────────────────────────────────────────

export type TemplateSnapshot = Readonly<{
  templateId: TemplateId;
  templateCode: TemplateCode;
  version: number;
  status: TemplateStatus;
  variableSchema: TemplateVariableSchema;
  channelAdaptations: readonly ChannelAdaptation[];
  intent: IntentType;
  transactionRequiredDefault: boolean;
  publishedAt?: Date;
  retiredAt?: Date;
}>;

export class Template {
  private constructor(private readonly snapshot: TemplateSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static draft(
    templateId: TemplateId,
    templateCode: TemplateCode,
    variableSchema: TemplateVariableSchema,
    channelAdaptations: ChannelAdaptation[],
    intent: IntentType,
    transactionRequiredDefault: boolean,
  ): Template {
    requireNonBlank(templateId, "templateId");
    requireNonBlank(templateCode, "templateCode");
    if (channelAdaptations.length === 0) {
      throw new DomainError("MISSING_CHANNEL_ADAPTATIONS", "Template must have at least one channel adaptation");
    }
    for (const adaptation of channelAdaptations) {
      requireNonBlank(adaptation.bodyTemplate, `bodyTemplate for ${adaptation.channel}`);
    }
    if (variableSchema.required.length === 0 && variableSchema.optional.length === 0) {
      throw new DomainError("MISSING_VARIABLE_SCHEMA", "Template must declare at least one variable");
    }

    const snapshot: TemplateSnapshot = deepFreeze({
      templateId,
      templateCode,
      version: 1,
      status: "Draft" as const,
      variableSchema: Object.freeze({
        required: Object.freeze([...variableSchema.required]),
        optional: Object.freeze([...variableSchema.optional]),
      }),
      channelAdaptations: Object.freeze(
        channelAdaptations.map((a) =>
          Object.freeze({ ...a }),
        ),
      ),
      intent,
      transactionRequiredDefault,
    });

    return new Template(snapshot);
  }

  publish(at: Date): Template {
    if (this.snapshot.status !== "Draft" && this.snapshot.status !== "Validated") {
      throw new DomainError(
        "TEMPLATE_NOT_PUBLISHABLE",
        `Template ${this.snapshot.templateCode} in ${this.snapshot.status} cannot be published`,
      );
    }
    const snapshot: TemplateSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Published" as const,
      publishedAt: new Date(at),
    });
    return new Template(snapshot);
  }

  retire(at: Date): Template {
    if (this.snapshot.status !== "Published") {
      throw new DomainError(
        "TEMPLATE_NOT_RETIRABLE",
        `Template ${this.snapshot.templateCode} in ${this.snapshot.status} cannot be retired`,
      );
    }
    const snapshot: TemplateSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Retired" as const,
      retiredAt: new Date(at),
    });
    return new Template(snapshot);
  }

  get id(): TemplateId {
    return this.snapshot.templateId;
  }

  get code(): TemplateCode {
    return this.snapshot.templateCode;
  }

  get status(): TemplateStatus {
    return this.snapshot.status;
  }

  get intent(): IntentType {
    return this.snapshot.intent;
  }

  toSnapshot(): TemplateSnapshot {
    return deepFreeze(cloneForSnapshot(this.snapshot));
  }
}

// ─── RecipientPolicy Aggregate ────────────────────────────────────────────────

export type ChannelPolicyEntry = Readonly<{
  channel: ChannelType;
  priority: number;
  fallbackChannels: readonly ChannelType[];
}>;

export type RecipientPolicySnapshot = Readonly<{
  policyId: string;
  intent: IntentType;
  allowedChannels: readonly ChannelPolicyEntry[];
  bypassConsent: boolean;
  transactionRequiredBypass: boolean;
  maxRetries: number;
  throttlePerMinute: number;
  active: boolean;
}>;

export class RecipientPolicy {
  private constructor(private readonly snapshot: RecipientPolicySnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static define(
    policyId: string,
    intent: IntentType,
    allowedChannels: ChannelPolicyEntry[],
    bypassConsent: boolean,
    transactionRequiredBypass: boolean,
    maxRetries: number,
    throttlePerMinute: number,
  ): RecipientPolicy {
    requireNonBlank(policyId, "policyId");
    requireNonBlank(intent, "intent");
    if (allowedChannels.length === 0) {
      throw new DomainError("MISSING_ALLOWED_CHANNELS", "Policy must define at least one allowed channel");
    }
    if (maxRetries < 0) {
      throw new DomainError("INVALID_MAX_RETRIES", "maxRetries must be non-negative");
    }
    if (throttlePerMinute < 1) {
      throw new DomainError("INVALID_THROTTLE", "throttlePerMinute must be at least 1");
    }

    const snapshot: RecipientPolicySnapshot = deepFreeze({
      policyId,
      intent,
      allowedChannels: Object.freeze(
        allowedChannels
          .sort((a, b) => a.priority - b.priority)
          .map((c) =>
            Object.freeze({ ...c, fallbackChannels: Object.freeze([...c.fallbackChannels]) }),
          ),
      ),
      bypassConsent,
      transactionRequiredBypass,
      maxRetries,
      throttlePerMinute,
      active: true,
    });

    return new RecipientPolicy(snapshot);
  }

  disable(): RecipientPolicy {
    const snapshot: RecipientPolicySnapshot = deepFreeze({
      ...this.snapshot,
      active: false,
    });
    return new RecipientPolicy(snapshot);
  }

  get intent(): IntentType {
    return this.snapshot.intent;
  }

  get active(): boolean {
    return this.snapshot.active;
  }

  toSnapshot(): RecipientPolicySnapshot {
    return deepFreeze(cloneForSnapshot(this.snapshot));
  }
}

// ─── DeliveryReceipt Aggregate (appended fact) ─────────────────────────────────

export type DeliveryReceiptSnapshot = Readonly<{
  receiptId: ReceiptId;
  notificationTaskId: NotificationTaskId;
  channel: ChannelType;
  outcome: DeliveryReceiptOutcome;
  providerCode?: string;
  providerMessage?: string;
  recordedAt: Date;
}>;

export class DeliveryReceipt {
  private constructor(private readonly snapshot: DeliveryReceiptSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static record(command: RecordDeliveryReceipt): DeliveryReceipt {
    requireNonBlank(command.receiptId, "receiptId");
    requireNonBlank(command.notificationTaskId, "notificationTaskId");

    const snapshot: DeliveryReceiptSnapshot = deepFreeze({
      receiptId: command.receiptId,
      notificationTaskId: command.notificationTaskId,
      channel: command.channel,
      outcome: command.outcome,
      providerCode: command.providerCode,
      providerMessage: command.providerMessage,
      recordedAt: new Date(command.recordedAt),
    });

    return new DeliveryReceipt(snapshot);
  }

  get id(): ReceiptId {
    return this.snapshot.receiptId;
  }

  get outcome(): DeliveryReceiptOutcome {
    return this.snapshot.outcome;
  }

  toSnapshot(): DeliveryReceiptSnapshot {
    return deepFreeze(cloneForSnapshot(this.snapshot));
  }
}

// ─── REQ-311 Multi-channel delivery, templates, rate limits ─────────────────────

export type DeliveryAttempt = Readonly<{
  channelUsed: NotificationChannel;
  attemptedAt: Date;
  status: DeliveryAttemptStatus;
  reason?: string;
}>;

export class ChannelFallbackChain {
  private static readonly priority: readonly NotificationChannel[] = Object.freeze(["PUSH", "SMS", "EMAIL"]);
  private readonly channels: readonly NotificationChannel[];

  constructor(primary: NotificationChannel = "PUSH", availableChannels: readonly NotificationChannel[] = ChannelFallbackChain.priority) {
    const unique = new Set<NotificationChannel>();
    unique.add(primary);
    for (const channel of ChannelFallbackChain.priority) {
      unique.add(channel);
    }
    this.channels = Object.freeze([...unique].filter((channel) => availableChannels.includes(channel)));
    if (this.channels.length === 0) {
      throw new DomainError("NO_AVAILABLE_CHANNELS", "At least one notification channel must be available");
    }
    Object.freeze(this);
  }

  toArray(): readonly NotificationChannel[] {
    return this.channels;
  }
}

export type NotificationTemplate = Readonly<{
  templateId: string;
  templateType: NotificationTemplateType;
  channel: NotificationChannel;
  subjectTemplate?: string;
  bodyTemplate: string;
  pushTitleTemplate?: string;
}>;

export type RenderedNotification = Readonly<{
  subject?: string;
  title?: string;
  body: string;
}>;

export class TemplateRenderer {
  constructor(private readonly templates: readonly NotificationTemplate[] = builtInNotificationTemplates()) {}

  render(templateType: NotificationTemplateType, channel: NotificationChannel, variables: Readonly<Record<string, string>>): RenderedNotification {
    const template = this.templates.find((candidate) => candidate.templateType === templateType && candidate.channel === channel);
    if (!template) {
      throw new DomainError("TEMPLATE_NOT_FOUND", `Template ${templateType} for ${channel} is not registered`);
    }
    return deepFreeze({
      ...(template.subjectTemplate ? { subject: renderTemplate(template.subjectTemplate, variables) } : {}),
      ...(template.pushTitleTemplate ? { title: renderTemplate(template.pushTitleTemplate, variables) } : {}),
      body: renderTemplate(template.bodyTemplate, variables),
    });
  }
}

export class RateLimitExceeded extends Error {
  constructor(
    public readonly recipientRef: string,
    public readonly channel: NotificationChannel,
    public readonly retryAfter: Date,
    message = `Rate limit exceeded for ${recipientRef} on ${channel}`,
  ) {
    super(message);
    this.name = "RateLimitExceeded";
  }
}

export type RateLimitDecision = Readonly<{ allowed: true } | { allowed: false; retryAfter: Date }>;

export class RateLimiter {
  private readonly events: Array<Readonly<{ recipientRef: string; channel: NotificationChannel; at: Date }>> = [];

  checkAndRecord(recipientRef: string, channel: NotificationChannel, at: Date = new Date()): RateLimitDecision {
    this.prune(at);
    const perUser = this.limitFor(channel);
    const windowStart = new Date(at.getTime() - perUser.windowMs);
    const userEvents = this.events.filter((event) => event.recipientRef === recipientRef && event.channel === channel && event.at > windowStart);
    if (userEvents.length >= perUser.max) {
      return { allowed: false, retryAfter: new Date(Math.min(...userEvents.map((event) => event.at.getTime())) + perUser.windowMs) };
    }
    if (channel === "SMS") {
      const globalWindowStart = new Date(at.getTime() - 60_000);
      const smsEvents = this.events.filter((event) => event.channel === "SMS" && event.at > globalWindowStart);
      if (smsEvents.length >= 1000) {
        return { allowed: false, retryAfter: new Date(Math.min(...smsEvents.map((event) => event.at.getTime())) + 60_000) };
      }
    }
    this.events.push(Object.freeze({ recipientRef, channel, at: new Date(at) }));
    return { allowed: true };
  }

  private limitFor(channel: NotificationChannel): Readonly<{ max: number; windowMs: number }> {
    switch (channel) {
      case "PUSH":
        return { max: 10, windowMs: 60 * 60 * 1000 };
      case "SMS":
        return { max: 5, windowMs: 24 * 60 * 60 * 1000 };
      case "EMAIL":
        return { max: 20, windowMs: 24 * 60 * 60 * 1000 };
    }
  }

  private prune(at: Date): void {
    const cutoff = at.getTime() - 24 * 60 * 60 * 1000;
    for (let index = this.events.length - 1; index >= 0; index -= 1) {
      if (this.events[index].at.getTime() < cutoff) {
        this.events.splice(index, 1);
      }
    }
  }
}

export type AggregatableNotification = Readonly<{
  recipientRef: string;
  orderRef: string;
  templateType: NotificationTemplateType;
  occurredAt: Date;
}>;

export class NotificationAggregator {
  private readonly seen = new Map<string, AggregatableNotification>();

  shouldSuppress(candidate: AggregatableNotification): boolean {
    const key = `${candidate.recipientRef}:${candidate.orderRef}`;
    const previous = this.seen.get(key);
    this.seen.set(key, candidate);
    if (!previous) {
      return false;
    }
    const withinWindow = Math.abs(candidate.occurredAt.getTime() - previous.occurredAt.getTime()) <= 5 * 60 * 1000;
    return withinWindow && aggregationPriority(previous.templateType) <= aggregationPriority(candidate.templateType);
  }
}

export function builtInNotificationTemplates(): readonly NotificationTemplate[] {
  return Object.freeze([
    templateSet("ORDER_CONFIRMED", "订单已确认", "您的订单 {orderId} 已确认，{origin}→{destination}，{departureTime} 出发", "订单{orderId}已确认 {origin}→{destination}", "订单已确认"),
    templateSet("PAYMENT_REMINDER", "待支付提醒", "订单 {orderId} 待支付，请在 {expiresAt} 前完成付款", "订单{orderId}待支付，请尽快付款", "待支付提醒"),
    templateSet("TICKET_ISSUED", "电子客票已出票", "电子客票已出票：{trainNumber} {seatInfo}，请凭身份证进站", "{trainNumber}{seatInfo}已出票", "电子客票"),
    templateSet("DELAY_ALERT", "列车晚点提醒", "您乘坐的 {trainNumber} 次列车预计晚点 {delayMinutes} 分钟", "{trainNumber}晚点{delayMinutes}分钟", "列车晚点"),
    templateSet("REFUND_COMPLETED", "退款到账提醒", "退款 {amount} 元已原路返回，预计 {arrivalDays} 个工作日到账", "退款{amount}元已返回", "退款完成"),
    templateSet("WAITLIST_PROMOTED", "候补购票成功", "候补购票成功！{origin}→{destination} {departureDate}，请在 15 分钟内确认", "候补成功，请15分钟内确认", "候补成功"),
    templateSet("DISRUPTION_REBOOK", "列车取消改签", "由于列车取消，已为您改签至 {newTrainNumber} {newDepartureTime}", "已改签至{newTrainNumber}", "已为您改签"),
  ].flat());
}

function templateSet(type: NotificationTemplateType, subject: string, body: string, smsBody: string, pushTitle: string): NotificationTemplate[] {
  return [
    { templateId: `${type}:EMAIL`, templateType: type, channel: "EMAIL", subjectTemplate: subject, bodyTemplate: body },
    { templateId: `${type}:SMS`, templateType: type, channel: "SMS", bodyTemplate: smsBody },
    { templateId: `${type}:PUSH`, templateType: type, channel: "PUSH", pushTitleTemplate: pushTitle, bodyTemplate: body },
  ];
}

function renderTemplate(template: string, variables: Readonly<Record<string, string>>): string {
  return template.replace(/\{([A-Za-z0-9_]+)\}/g, (_placeholder, key: string) => variables[key] ?? "");
}

function aggregationPriority(type: NotificationTemplateType): number {
  switch (type) {
    case "ORDER_CONFIRMED":
      return 1;
    case "TICKET_ISSUED":
      return 2;
    case "DISRUPTION_REBOOK":
      return 3;
    case "DELAY_ALERT":
    case "REFUND_COMPLETED":
    case "WAITLIST_PROMOTED":
      return 4;
    case "PAYMENT_REMINDER":
      return 5;
  }
}

// ─── Boundary Proof ────────────────────────────────────────────────────────────

export type NotificationBoundaryProof = Readonly<{
  journeyOrderMutated: false;
  capacityHoldMutated: false;
  paymentIntentMutated: false;
  entitlementMutated: false;
  crossContextWriteTargets: readonly [];
}>;

const boundaryProof: NotificationBoundaryProof = Object.freeze({
  journeyOrderMutated: false,
  capacityHoldMutated: false,
  paymentIntentMutated: false,
  entitlementMutated: false,
  crossContextWriteTargets: Object.freeze([]) as readonly [],
});

export function notificationBoundaryProof(): NotificationBoundaryProof {
  return boundaryProof;
}

export function newNotificationTaskId(now: Date = new Date()): NotificationTaskId {
  return `nt-${uuidV7(now)}`;
}

export function newReceiptId(now: Date = new Date()): ReceiptId {
  return `rct-${uuidV7(now)}`;
}

// ─── Idempotency key generation ────────────────────────────────────────────────

export function idempotencyKey(
  triggerEventId: EventId,
  recipientRef: RecipientRef,
  templateCode: TemplateCode,
): string {
  return `idem-${triggerEventId}:${recipientRef}:${templateCode}`;
}

// ─── Internal helpers ──────────────────────────────────────────────────────────

function validateScheduleCommand(command: ScheduleNotification): void {
  requireNonBlank(command.notificationTaskId, "notificationTaskId");
  requireNonBlank(command.triggerEventId, "triggerEventId");
  requireNonBlank(command.triggerEventType, "triggerEventType");
  requireNonBlank(command.correlationId, "correlationId");
  requireNonBlank(command.recipientRef, "recipientRef");
  requireNonBlank(command.templateCode, "templateCode");
  requireNonBlank(command.intent, "intent");

  if (!Number.isFinite(command.scheduledAt.getTime())) {
    throw new DomainError("INVALID_SCHEDULED_AT", "scheduledAt must be a valid Date");
  }
}

function requireNonBlank(value: string | undefined, label: string): void {
  if (!value || value.trim().length === 0) {
    throw new DomainError("MISSING_REQUIRED_FIELD", `${label} is required`);
  }
}

function deepFreeze<T>(value: T): T {
  if (value && typeof value === "object") {
    for (const nested of Object.values(value as Record<string, unknown> | unknown[])) {
      deepFreeze(nested);
    }
    Object.freeze(value);
  }
  return value;
}

function cloneForSnapshot<T>(value: T): T {
  if (value instanceof Date) {
    return new Date(value) as T;
  }
  if (Array.isArray(value)) {
    return value.map((entry) => cloneForSnapshot(entry)) as T;
  }
  if (value && typeof value === "object") {
    const clone: Record<string, unknown> = {};
    for (const [key, nested] of Object.entries(value as Record<string, unknown>)) {
      clone[key] = cloneForSnapshot(nested);
    }
    return clone as T;
  }
  return value;
}
