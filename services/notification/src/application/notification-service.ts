import {
  ChannelFallbackChain,
  type ChannelType,
  type NotificationChannel,
  NotificationAggregator,
  type NotificationTask,
  NotificationTask as NotificationTaskAggregate,
  type NotificationTemplateType,
  RateLimitExceeded,
  RateLimiter,
  TemplateRenderer,
  newNotificationTaskId,
  newReceiptId,
  type ScheduleNotification,
} from "../domain.js";
import { type EventEnvelope, type EventPublisher, toEventEnvelope } from "./messaging.js";

export type ExternalNotificationTrigger = Readonly<{
  eventId: string;
  eventType: string;
  correlationId: string;
  occurredAt: string;
  payload: Record<string, unknown>;
}>;

export type ExternalTriggerResult = "delivered" | "cancelled" | "failed" | "ignored" | "deferred";

export type UserPreference = Readonly<{
  recipientRef: string;
  intent: string;
  channel: ChannelType;
  enabled: boolean;
}>;

export interface UserPreferenceRepository {
  isEnabled(recipientRef: string, intent: string, channel: ChannelType): Promise<boolean> | boolean;
}

export type ContactProfile = Readonly<{
  deviceToken?: string;
  phoneNumber?: string;
  emailAddress?: string;
  preferredChannel?: NotificationChannel;
}>;

export interface RecipientContactRepository {
  getContactProfile(recipientRef: string): Promise<ContactProfile> | ContactProfile;
}

export interface NotificationTaskStore {
  saveNew(task: NotificationTask): Promise<{ version: bigint }> | { version: bigint };
  save(task: NotificationTask, expectedVersion: bigint): Promise<{ version: bigint }> | { version: bigint };
}

export interface RateLimitStore {
  checkAndRecord(recipientRef: string, channel: ChannelType, at: Date): Promise<{ allowed: true } | { allowed: false; retryAfter: Date }> | { allowed: true } | { allowed: false; retryAfter: Date };
}

export type DeliveryResult =
  | Readonly<{ ok: true; providerMessageId?: string }>
  | Readonly<{ ok: false; outcome: "Bounced" | "Rejected" | "Timeout" | "Expired"; providerCode?: string; providerMessage?: string }>;

export interface NotificationChannelGateway {
  send(task: ReturnType<NotificationTask["toSnapshot"]>): Promise<DeliveryResult> | DeliveryResult;
}

export class NonConformantNotificationTrigger extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NonConformantNotificationTrigger";
  }
}

export class DirectSuccessGateway implements NotificationChannelGateway {
  async send(task: ReturnType<NotificationTask["toSnapshot"]>): Promise<DeliveryResult> {
    if (task.channel === "IN_APP") {
      return { ok: true };
    }
    return { ok: true, providerMessageId: `${task.channel.toLowerCase()}-${task.notificationTaskId}` };
  }
}

class AllowAllPreferences implements UserPreferenceRepository {
  isEnabled(): boolean {
    return true;
  }
}

class DefaultContactRepository implements RecipientContactRepository {
  getContactProfile(): ContactProfile {
    return { deviceToken: "push-default", phoneNumber: "+860000000000", emailAddress: "default@train-ticket.local" };
  }
}

class InMemoryRateLimitStore implements RateLimitStore {
  private readonly limiter = new RateLimiter();

  checkAndRecord(recipientRef: string, channel: ChannelType, at: Date): { allowed: true } | { allowed: false; retryAfter: Date } {
    return this.limiter.checkAndRecord(recipientRef, channel, at);
  }
}

type TriggerMapping = Readonly<{
  templateCode: string;
  templateType?: NotificationTemplateType | ((payload: Record<string, unknown>) => NotificationTemplateType);
  intent: string | ((payload: Record<string, unknown>) => string);
  channel: ChannelType;
  recipient: (payload: Record<string, unknown>) => string | readonly string[] | undefined;
  variables: (payload: Record<string, unknown>) => Record<string, string>;
}>;

export class NotificationApplicationService {
  private readonly renderer = new TemplateRenderer();

  constructor(
    private readonly publisher: EventPublisher,
    private readonly preferences: UserPreferenceRepository = new AllowAllPreferences(),
    private readonly channelGateway: NotificationChannelGateway = new DirectSuccessGateway(),
    private readonly taskStore?: NotificationTaskStore,
    private readonly contacts: RecipientContactRepository = new DefaultContactRepository(),
    private readonly rateLimits: RateLimitStore = new InMemoryRateLimitStore(),
    private readonly aggregator: NotificationAggregator = new NotificationAggregator(),
  ) {}

  async handleExternalTrigger(envelope: EventEnvelope): Promise<ExternalTriggerResult> {
    const commands = scheduleCommandsFromEnvelope(envelope, this.aggregator);
    if (commands === undefined) {
      if (mappingFor(envelope.eventType) === undefined) {
        console.info(`Ignoring unsupported notification trigger ${envelope.eventType} (${envelope.eventId})`);
      }
      return "ignored";
    }

    const mapping = mappingFor(envelope.eventType);
    const templateType = mapping ? resolveTemplateType(mapping, envelope.payload) : undefined;
    const results: ExternalTriggerResult[] = [];
    for (const command of commands) {
      if (templateType) {
        (command.variables as Record<string, string>).renderedPreview = this.renderer.render(templateType, command.channel, command.variables).body;
      }
      results.push(await this.deliverWithFallback(command, templateType));
    }

    if (results.includes("delivered")) {
      return "delivered";
    }
    if (results.includes("failed")) {
      return "failed";
    }
    if (results.includes("cancelled")) {
      return "cancelled";
    }
    return "ignored";
  }

  private async deliverWithFallback(command: ScheduleNotification, templateType: NotificationTemplateType | undefined): Promise<ExternalTriggerResult> {
    const channels: readonly ChannelType[] = command.channel === "IN_APP"
      ? ["IN_APP"]
      : new ChannelFallbackChain(command.channel, availableChannels(await this.contacts.getContactProfile(command.recipientRef))).toArray();

    if (!command.transactionRequired && !await this.preferences.isEnabled(command.recipientRef, command.intent, channels[0])) {
      return this.publishCancellation({ ...command, notificationTaskId: newNotificationTaskId(), channel: channels[0], templateCode: templateType ?? command.templateCode }, "SUPPRESSED_BY_PREFERENCES");
    }

    const scheduledCommand: ScheduleNotification = {
      ...command,
      notificationTaskId: newNotificationTaskId(),
      channel: channels[0],
      templateCode: templateType ?? command.templateCode,
      variables: renderedVariables(command.variables, templateType, channels[0], this.renderer),
    };
    const { task, event: scheduled } = NotificationTaskAggregate.schedule(scheduledCommand);
    let version = (await this.taskStore?.saveNew(task))?.version;
    await this.publisher.publish(toEventEnvelope(scheduled));

    const dispatchedAt = new Date();
    let { task: currentTask, event: dispatched } = task.dispatch({ notificationTaskId: task.id, dispatchedAt });
    if (version !== undefined) {
      version = (await this.taskStore?.save(currentTask, version))?.version ?? version;
    }
    await this.publisher.publish(toEventEnvelope(dispatched));

    for (const channel of channels) {
      const rateDecision = await this.rateLimits.checkAndRecord(command.recipientRef, channel, new Date());
      if (!rateDecision.allowed) {
        throw new RateLimitExceeded(command.recipientRef, channel, rateDecision.retryAfter);
      }

      const delivery = await this.channelGateway.send({
        ...currentTask.toSnapshot(),
        channel,
        variables: renderedVariables(command.variables, templateType, channel, this.renderer),
      });
      const finalFailure = channels[channels.length - 1] === channel;
      const { task: updatedTask, event } = currentTask.recordReceipt({
        receiptId: newReceiptId(),
        notificationTaskId: currentTask.id,
        channel,
        outcome: delivery.ok ? "Delivered" : delivery.outcome,
        ...(delivery.ok ? {} : { providerCode: delivery.providerCode, providerMessage: delivery.providerMessage, finalFailure }),
        recordedAt: new Date(),
      });
      currentTask = updatedTask;
      if (version !== undefined) {
        version = (await this.taskStore?.save(currentTask, version))?.version ?? version;
      }
      await this.publisher.publish(toEventEnvelope(event));
      if (delivery.ok) {
        return "delivered";
      }
    }

    return "failed";
  }

  private async publishCancellation(command: ScheduleNotification, reason: string): Promise<ExternalTriggerResult> {
    const { task, event: scheduled } = NotificationTaskAggregate.schedule(command);
    let version = (await this.taskStore?.saveNew(task))?.version;
    await this.publisher.publish(toEventEnvelope(scheduled));
    const { task: cancelled, event } = task.cancel({ notificationTaskId: task.id, reason, cancelledAt: new Date() });
    if (version !== undefined) {
      version = (await this.taskStore?.save(cancelled, version))?.version ?? version;
    }
    await this.publisher.publish(toEventEnvelope(event));
    return "cancelled";
  }
}

function renderedVariables(
  variables: Readonly<Record<string, string>>,
  templateType: NotificationTemplateType | undefined,
  channel: ChannelType,
  renderer: TemplateRenderer,
): Record<string, string> {
  if (!templateType) {
    return { ...variables };
  }
  const rendered = renderer.render(templateType, channel, variables);
  return {
    ...variables,
    subject: rendered.subject ?? rendered.title ?? templateType,
    body: rendered.body,
  };
}

function resolveTemplateType(mapping: TriggerMapping, payload: Record<string, unknown>): NotificationTemplateType | undefined {
  return typeof mapping.templateType === "function" ? mapping.templateType(payload) : mapping.templateType;
}

function resolveIntent(mapping: TriggerMapping, payload: Record<string, unknown>): string {
  return typeof mapping.intent === "function" ? mapping.intent(payload) : mapping.intent;
}

function scheduleCommandsFromEnvelope(envelope: EventEnvelope, aggregator?: NotificationAggregator): readonly ScheduleNotification[] | undefined {
  const mapping = mappingFor(envelope.eventType);
  if (mapping === undefined) {
    return undefined;
  }

  validateTriggerContract(envelope);

  const payload = envelope.payload;
  const recipientRefs = recipientRefsFromMapping(mapping.recipient(payload));
  if (recipientRefs.length === 0) {
    console.debug(`Skipping notification trigger ${envelope.eventType} (${envelope.eventId}): no resolvable recipientRef`);
    return undefined;
  }

  const businessRef = triggerBusinessRef(envelope);
  const aggregationRef = aggregationBusinessRef(envelope);
  const templateType = resolveTemplateType(mapping, payload);
  const commands: ScheduleNotification[] = [];
  for (const recipientRef of recipientRefs) {
    if (aggregator && templateType && aggregationRef && aggregator.shouldSuppress({
      recipientRef,
      orderRef: aggregationRef,
      templateType,
      occurredAt: dateValue(envelope.occurredAt) ?? new Date(),
    })) {
      continue;
    }

    commands.push({
      notificationTaskId: newNotificationTaskId(),
      triggerEventId: envelope.eventId,
      triggerEventType: envelope.eventType,
      correlationId: envelope.correlationId,
      causationId: envelope.eventId,
      recipientRef,
      templateCode: templateType ?? mapping.templateCode,
      channel: mapping.channel,
      intent: resolveIntent(mapping, payload),
      transactionRequired: booleanValue(payload.transactionRequired) ?? true,
      variables: mapping.variables(payload),
      scheduledAt: new Date(),
      triggerBusinessRef: businessRef,
    });
  }

  return commands.length > 0 ? commands : undefined;
}

function recipientRefsFromMapping(value: string | readonly string[] | undefined): readonly string[] {
  const candidates = Array.isArray(value) ? value : value === undefined ? [] : [value];
  return [...new Set(candidates.filter((candidate) => candidate.trim().length > 0))];
}


function aggregationBusinessRef(envelope: EventEnvelope): string | undefined {
  const payload = envelope.payload;
  return stringValue(payload.orderId)
    ?? stringValue(payload.journeyOrderId)
    ?? stringValue(payload.businessRef)
    ?? stringValue(payload.paymentIntentId)
    ?? stringValue(payload.refundId)
    ?? stringValue(payload.entitlementId)
    ?? stringValue(payload.segmentBookingId)
    ?? stringValue(payload.caseId)
    ?? stringValue(payload.serviceAlertId)
    ?? stringValue(payload.incidentId)
    ?? stringValue(recordValue(payload.connection)?.connectionId)
    ?? stringValue(payload.waitlistRequestId)
    ?? stringValue(payload.journeyOrderRef)
    ?? stringValue(payload.postSalesCaseId);
}

function triggerBusinessRef(envelope: EventEnvelope): string | undefined {
  const payload = envelope.payload;
  const businessRef = stringValue(payload.orderId)
    ?? stringValue(payload.journeyOrderId)
    ?? stringValue(payload.paymentIntentId)
    ?? stringValue(payload.refundId)
    ?? stringValue(payload.entitlementId)
    ?? stringValue(payload.benefitId)
    ?? stringValue(payload.segmentBookingId)
    ?? stringValue(payload.caseId)
    ?? stringValue(payload.serviceAlertId)
    ?? stringValue(payload.incidentId)
    ?? stringValue(payload.waitlistRequestId)
    ?? stringValue(payload.journeyOrderRef)
    ?? stringValue(payload.postSalesCaseId)
    ?? stringValue(recordValue(payload.connection)?.connectionId)
    ?? stringValue(payload.businessRef);
  return businessRef ? `${envelope.eventType}:${businessRef}` : undefined;
}

type FieldType = "array" | "boolean" | "money" | "object" | "string";

type RequiredField = Readonly<{
  name: string;
  type: FieldType;
}>;

const CONTRACT_FIELDS_BY_EVENT: Readonly<Record<string, readonly RequiredField[]>> = Object.freeze({
  JourneyOrderCreated: [
    { name: "orderId", type: "string" },
    { name: "accountId", type: "string" },
    { name: "offerId", type: "string" },
    { name: "monetarySummary", type: "object" },
    { name: "travelerRefs", type: "array" },
    { name: "segmentRefs", type: "array" },
    { name: "createdAt", type: "string" },
  ],
  JourneyOrderPendingPayment: [
    { name: "orderId", type: "string" },
    { name: "accountId", type: "string" },
    { name: "paymentPurpose", type: "string" },
    { name: "monetarySummary", type: "object" },
  ],
  JourneyOrderPaymentRecorded: [
    { name: "orderId", type: "string" },
    { name: "accountId", type: "string" },
    { name: "paymentIntentId", type: "string" },
  ],
  JourneyOrderConfirmed: [
    { name: "orderId", type: "string" },
    { name: "accountId", type: "string" },
    { name: "monetarySummary", type: "object" },
    { name: "confirmedAt", type: "string" },
  ],
  JourneyOrderCancelled: [
    { name: "orderId", type: "string" },
    { name: "accountId", type: "string" },
    { name: "reason", type: "string" },
  ],
  JourneyOrderPostSalesAdjusted: [
    { name: "orderId", type: "string" },
    { name: "accountId", type: "string" },
    { name: "postSalesCaseId", type: "string" },
    { name: "monetarySummary", type: "object" },
  ],
  JourneyOrderAdjusted: [
    { name: "orderId", type: "string" },
    { name: "accountId", type: "string" },
    { name: "postSalesCaseId", type: "string" },
    { name: "monetarySummary", type: "object" },
  ],
  PaymentCaptured: [
    { name: "paymentIntentId", type: "string" },
    { name: "businessRef", type: "string" },
    { name: "capturedAmount", type: "money" },
    { name: "channel", type: "string" },
    { name: "channelTransactionId", type: "string" },
  ],
  PaymentFailed: [
    { name: "paymentIntentId", type: "string" },
    { name: "reasonCode", type: "string" },
    { name: "retryable", type: "boolean" },
  ],
  PaymentIntentFailed: [
    { name: "paymentIntentId", type: "string" },
    { name: "reasonCode", type: "string" },
    { name: "retryable", type: "boolean" },
  ],
  PaymentIntentExpired: [{ name: "paymentIntentId", type: "string" }],
  PaymentExpired: [{ name: "paymentIntentId", type: "string" }],
  RefundSettled: [
    { name: "refundId", type: "string" },
    { name: "paymentIntentId", type: "string" },
    { name: "amount", type: "money" },
  ],
  EntitlementIssued: [
    { name: "entitlementId", type: "string" },
    { name: "segmentBookingId", type: "string" },
    { name: "journeyOrderId", type: "string" },
    { name: "travelerRef", type: "string" },
    { name: "segmentRef", type: "string" },
    { name: "issuePurpose", type: "string" },
    { name: "credentialNo", type: "string" },
    { name: "credentialType", type: "string" },
    { name: "issuedAt", type: "string" },
  ],
  PostSalesEligibilityEvaluated: [
    { name: "caseId", type: "string" },
    { name: "eligible", type: "boolean" },
    { name: "reasonCode", type: "string" },
  ],
  PostSalesDecisionQuoted: [
    { name: "caseId", type: "string" },
    { name: "decisionKind", type: "string" },
    { name: "eligible", type: "boolean" },
    { name: "ruleSnapshotRef", type: "string" },
  ],
  PostSalesExecutionStarted: [
    { name: "caseId", type: "string" },
    { name: "orderedSteps", type: "array" },
    { name: "approvalRef", type: "string" },
  ],
  PostSalesApplied: [
    { name: "caseId", type: "string" },
    { name: "orderId", type: "string" },
    { name: "resultSummary", type: "object" },
  ],
  PostSalesFailed: [
    { name: "caseId", type: "string" },
    { name: "orderId", type: "string" },
    { name: "reason", type: "string" },
  ],
  ChangeApplied: [
    { name: "caseId", type: "string" },
    { name: "orderId", type: "string" },
    { name: "oldEntitlementRef", type: "string" },
    { name: "newEntitlementRef", type: "string" },
    { name: "changeOfferRef", type: "string" },
  ],
  BenefitIssued: [
    { name: "benefitId", type: "string" },
    { name: "accountId", type: "string" },
    { name: "issuedAmount", type: "money" },
    { name: "issuanceSource", type: "string" },
    { name: "issuedAt", type: "string" },
  ],
  BenefitExpired: [
    { name: "benefitId", type: "string" },
    { name: "accountId", type: "string" },
    { name: "expiredAmount", type: "money" },
    { name: "expiredAt", type: "string" },
  ],
  BenefitRevoked: [
    { name: "benefitId", type: "string" },
    { name: "accountId", type: "string" },
    { name: "revokedAmount", type: "money" },
    { name: "revokedAt", type: "string" },
  ],
  WaitlistFulfilled: [
    { name: "waitlistRequestId", type: "string" },
    { name: "accountId", type: "string" },
    { name: "travelerRef", type: "string" },
    { name: "segmentRef", type: "string" },
    { name: "journeyOrderRef", type: "string" },
    { name: "fulfilledAt", type: "string" },
    { name: "status", type: "string" },
  ],
  ServiceAlertPublished: [
    { name: "serviceAlertId", type: "string" },
    { name: "incidentId", type: "string" },
    { name: "disruptionType", type: "string" },
    { name: "serviceDate", type: "string" },
    { name: "audience", type: "string" },
    { name: "messageSummary", type: "string" },
    { name: "publishedAt", type: "string" },
  ],

  RecoveryCaseOpened: [
    { name: "caseId", type: "string" },
    { name: "incidentId", type: "string" },
    { name: "disruptionId", type: "string" },
    { name: "journeyOrderId", type: "string" },
    { name: "affectedScope", type: "object" },
    { name: "openedAt", type: "string" },
    { name: "status", type: "string" },
  ],
  RecoveryOptionsGenerated: [
    { name: "caseId", type: "string" },
    { name: "incidentId", type: "string" },
    { name: "journeyOrderId", type: "string" },
    { name: "optionSetId", type: "string" },
    { name: "options", type: "array" },
    { name: "requiresUserChoice", type: "boolean" },
    { name: "generatedAt", type: "string" },
    { name: "status", type: "string" },
  ],
  RecoveryOptionSelected: [
    { name: "caseId", type: "string" },
    { name: "incidentId", type: "string" },
    { name: "journeyOrderId", type: "string" },
    { name: "optionSetId", type: "string" },
    { name: "optionId", type: "string" },
    { name: "optionType", type: "string" },
    { name: "selectedBy", type: "object" },
    { name: "selectedAt", type: "string" },
    { name: "status", type: "string" },
  ],
  RecoveryExecutionStarted: [
    { name: "caseId", type: "string" },
    { name: "incidentId", type: "string" },
    { name: "journeyOrderId", type: "string" },
    { name: "optionId", type: "string" },
    { name: "optionType", type: "string" },
    { name: "executionId", type: "string" },
    { name: "executionTarget", type: "string" },
    { name: "startedAt", type: "string" },
    { name: "status", type: "string" },
  ],
  RecoveryCompleted: [
    { name: "caseId", type: "string" },
    { name: "incidentId", type: "string" },
    { name: "journeyOrderId", type: "string" },
    { name: "optionId", type: "string" },
    { name: "optionType", type: "string" },
    { name: "executionId", type: "string" },
    { name: "completedAt", type: "string" },
    { name: "status", type: "string" },
  ],
  RecoveryFailed: [
    { name: "caseId", type: "string" },
    { name: "incidentId", type: "string" },
    { name: "journeyOrderId", type: "string" },
    { name: "failedAt", type: "string" },
    { name: "reason", type: "string" },
    { name: "nextStatus", type: "string" },
  ],
  TransferAtRisk: [
    { name: "connection", type: "object" },
    { name: "previousStatus", type: "string" },
    { name: "status", type: "string" },
    { name: "riskLevel", type: "string" },
    { name: "riskPolicyVersion", type: "string" },
    { name: "reasons", type: "array" },
    { name: "window", type: "object" },
    { name: "detectedAt", type: "string" },
  ],
  ConnectionMissed: [
    { name: "connection", type: "object" },
    { name: "previousStatus", type: "string" },
    { name: "status", type: "string" },
    { name: "riskLevel", type: "string" },
    { name: "contractType", type: "string" },
    { name: "missedAt", type: "string" },
    { name: "missedCause", type: "string" },
    { name: "window", type: "object" },
    { name: "recoveryRequired", type: "boolean" },
  ],
  ConnectionRecovered: [
    { name: "connection", type: "object" },
    { name: "previousStatus", type: "string" },
    { name: "status", type: "string" },
    { name: "riskLevel", type: "string" },
    { name: "recoveredAt", type: "string" },
  ],
});

function validateTriggerContract(envelope: EventEnvelope): void {
  const envelopeViolations = envelopeContractViolations(envelope);
  const payload = recordValue(envelope.payload);
  const requiredFields = CONTRACT_FIELDS_BY_EVENT[envelope.eventType] ?? [];
  const payloadViolations = payload === undefined
    ? ["payload must be an object"]
    : requiredFields.flatMap((field) => fieldViolation(payload, field));
  const violations = [...envelopeViolations, ...payloadViolations];

  if (violations.length > 0) {
    throw new NonConformantNotificationTrigger(
      `Notification trigger ${envelope.eventType} (${envelope.eventId}) violates its event contract: ${violations.join(", ")}`,
    );
  }
}

function envelopeContractViolations(envelope: EventEnvelope): string[] {
  const violations: string[] = [];
  for (const field of ["eventId", "eventType", "producer", "correlationId", "occurredAt"] as const) {
    if (stringValue(envelope[field]) === undefined) {
      violations.push(`${field} must be a non-empty string`);
    }
  }
  if (!hasPrefix(envelope.eventId, "evt-")) {
    violations.push("eventId must use evt- prefix");
  }
  if (!hasPrefix(envelope.correlationId, "corr-")) {
    violations.push("correlationId must use corr- prefix");
  }
  if (envelope.causationId !== undefined && !hasAnyPrefix(envelope.causationId, ["cmd-", "evt-"])) {
    violations.push("causationId must use cmd- or evt- prefix");
  }
  if (typeof envelope.schemaVersion !== "number" || !Number.isInteger(envelope.schemaVersion) || envelope.schemaVersion < 1) {
    violations.push("schemaVersion must be a positive integer");
  }
  return violations;
}

function hasPrefix(value: unknown, prefix: string): boolean {
  return typeof value === "string" && value.startsWith(prefix) && value.length > prefix.length;
}

function hasAnyPrefix(value: unknown, prefixes: readonly string[]): boolean {
  return prefixes.some((prefix) => hasPrefix(value, prefix));
}

function fieldViolation(payload: Record<string, unknown>, field: RequiredField): string[] {
  const value = payload[field.name];
  if (value === undefined || value === null) {
    return [`payload.${field.name} is required`];
  }
  switch (field.type) {
    case "array":
      return Array.isArray(value) ? [] : [`payload.${field.name} must be an array`];
    case "boolean":
      return typeof value === "boolean" ? [] : [`payload.${field.name} must be a boolean`];
    case "money":
      return isMoney(value) ? [] : [`payload.${field.name} must be Money`];
    case "object":
      return recordValue(value) === undefined ? [`payload.${field.name} must be an object`] : [];
    case "string":
      return stringValue(value) === undefined ? [`payload.${field.name} must be a non-empty string`] : [];
  }
}

function isMoney(value: unknown): boolean {
  const record = recordValue(value);
  return record !== undefined
    && stringValue(record.currency) !== undefined
    && typeof record.minorUnits === "number"
    && Number.isInteger(record.minorUnits);
}

function mappingFor(eventType: string): TriggerMapping | undefined {
  switch (eventType) {
    case "JourneyOrderCreated":
      return orderMapping("order_created", "ORDER_CREATED");
    case "JourneyOrderPendingPayment":
    case "PaymentIntentExpired":
    case "PaymentExpired":
      return paymentReminderMapping();
    case "JourneyOrderPaymentRecorded":
      return orderMapping("order_payment_recorded", "ORDER_PAYMENT_RECORDED");
    case "JourneyOrderConfirmed":
      return orderMapping("ORDER_CONFIRMED", "ORDER_CONFIRMED", "ORDER_CONFIRMED");
    case "JourneyOrderCancelled":
      return orderMapping("order_cancelled", "ORDER_CANCELLED");
    case "JourneyOrderPostSalesAdjusted":
    case "JourneyOrderAdjusted":
      return orderMapping("order_adjusted", "ORDER_ADJUSTED");
    case "PaymentCaptured":
      return paymentMapping("payment_captured", "PAYMENT_RESULT");
    case "PaymentFailed":
    case "PaymentIntentFailed":
      return paymentMapping("payment_failed", "PAYMENT_RESULT");
    case "RefundSettled":
      return refundMapping();
    case "EntitlementIssued":
      return ticketIssuedMapping();
    case "PostSalesEligibilityEvaluated":
      return postSalesMapping("post_sales_eligibility", "POST_SALES_ELIGIBILITY");
    case "PostSalesDecisionQuoted":
      return postSalesMapping("post_sales_decision", "POST_SALES_DECISION");
    case "PostSalesExecutionStarted":
      return postSalesMapping("post_sales_execution", "POST_SALES_EXECUTION");
    case "PostSalesApplied":
      return postSalesMapping("post_sales_applied", "POST_SALES_APPLIED");
    case "PostSalesFailed":
      return postSalesMapping("post_sales_failed", "POST_SALES_FAILED");
    case "ChangeApplied":
      return postSalesMapping("change_applied", "CHANGE_APPLIED");
    case "BenefitIssued":
      return walletBenefitMapping("wallet_benefit_issued", "WALLET_BENEFIT_ISSUED");
    case "BenefitExpired":
      return walletBenefitMapping("wallet_benefit_expired", "WALLET_BENEFIT_EXPIRED");
    case "BenefitRevoked":
      return walletBenefitMapping("wallet_benefit_revoked", "WALLET_BENEFIT_REVOKED");
    case "WaitlistFulfilled":
      return waitlistFulfilledMapping();
    case "ServiceAlertPublished":
      return serviceAlertPublishedMapping();
    case "RecoveryCaseOpened":
      return recoveryCaseOpenedMapping();
    case "RecoveryOptionsGenerated":
      return recoveryOptionsGeneratedMapping();
    case "RecoveryOptionSelected":
      return recoveryOptionSelectedMapping();
    case "RecoveryExecutionStarted":
      return recoveryExecutionStartedMapping();
    case "RecoveryCompleted":
      return recoveryCompletedMapping();
    case "RecoveryFailed":
      return recoveryFailedMapping();
    case "TransferAtRisk":
      return transferAtRiskMapping();
    case "ConnectionMissed":
      return connectionMissedMapping();
    case "ConnectionRecovered":
      return connectionRecoveredMapping();
    default:
      return undefined;
  }
}

function orderMapping(templateCode: string, intent: string, templateType?: NotificationTemplateType): TriggerMapping {
  return {
    templateCode,
    templateType,
    intent,
    channel: "PUSH",
    recipient: recipientFromOrderEvent,
    variables: (payload) => ({
      ...pickStringVariables(payload, ["orderId", "journeyOrderId", "offerId", "paymentIntentId", "postSalesCaseId", "reason", "origin", "destination", "departureTime"]),
      origin: stringValue(payload.origin) ?? stringValue(recordValue(payload.trip)?.origin) ?? "--",
      destination: stringValue(payload.destination) ?? stringValue(recordValue(payload.trip)?.destination) ?? "--",
      departureTime: stringValue(payload.departureTime) ?? stringValue(recordValue(payload.trip)?.departureTime) ?? stringValue(payload.confirmedAt) ?? "--",
    }),
  };
}

function paymentReminderMapping(): TriggerMapping {
  return {
    templateCode: "PAYMENT_REMINDER",
    templateType: "PAYMENT_REMINDER",
    intent: "PAYMENT_REMINDER",
    channel: "PUSH",
    recipient: recipientFromDirectFields,
    variables: (payload) => ({
      ...pickStringVariables(payload, ["paymentIntentId", "orderId", "businessRef", "expiresAt"]),
      orderId: stringValue(payload.orderId) ?? stringValue(payload.businessRef) ?? stringValue(payload.paymentIntentId) ?? "--",
      expiresAt: stringValue(payload.expiresAt) ?? stringValue(payload.expiredAt) ?? "--",
    }),
  };
}

function paymentMapping(templateCode: string, intent: string): TriggerMapping {
  return {
    templateCode,
    intent,
    channel: "PUSH",
    recipient: recipientFromDirectFields,
    variables: (payload) => pickStringVariables(payload, ["paymentIntentId", "orderId", "businessRef", "channel", "channelTransactionId", "reason", "reasonCode"]),
  };
}

function refundMapping(): TriggerMapping {
  return {
    templateCode: "REFUND_COMPLETED",
    templateType: "REFUND_COMPLETED",
    intent: "REFUND_COMPLETED",
    channel: "PUSH",
    recipient: recipientFromDirectFields,
    variables: (payload) => ({
      ...pickStringVariables(payload, ["refundId", "paymentIntentId", "orderId", "reason", "arrivalDays"]),
      amount: moneyString(payload.amount) ?? "0",
      arrivalDays: stringValue(payload.arrivalDays) ?? "3",
    }),
  };
}

function ticketIssuedMapping(): TriggerMapping {
  return {
    templateCode: "TICKET_ISSUED",
    templateType: "TICKET_ISSUED",
    intent: "TICKET_ISSUED",
    channel: "PUSH",
    recipient: (payload) => recipientFromDirectFields(payload) ?? stringValue(payload.travelerRef),
    variables: (payload) => ({
      ...pickStringVariables(payload, ["entitlementId", "journeyOrderId", "orderId", "orderItemId", "segmentBookingId", "segmentRef", "credentialNo", "credentialType", "trainNumber", "seatInfo"]),
      trainNumber: stringValue(payload.trainNumber) ?? stringValue(payload.segmentRef) ?? "--",
      seatInfo: stringValue(payload.seatInfo) ?? stringValue(payload.credentialType) ?? "--",
    }),
  };
}

function waitlistFulfilledMapping(): TriggerMapping {
  return {
    templateCode: "WAITLIST_PROMOTED",
    templateType: "WAITLIST_PROMOTED",
    intent: "WAITLIST_PROMOTED",
    channel: "PUSH",
    recipient: recipientFromDirectFields,
    variables: (payload) => ({
      ...pickStringVariables(payload, ["waitlistRequestId", "journeyOrderRef", "segmentRef", "travelClass", "fulfilledAt"]),
      orderId: stringValue(payload.journeyOrderRef) ?? stringValue(payload.waitlistRequestId) ?? "--",
      origin: stringValue(payload.origin) ?? stringValue(payload.segmentRef) ?? "--",
      destination: stringValue(payload.destination) ?? stringValue(payload.travelClass) ?? "--",
      departureDate: stringValue(payload.departureDate) ?? stringValue(payload.fulfilledAt) ?? "--",
    }),
  };
}

function serviceAlertPublishedMapping(): TriggerMapping {
  return {
    templateCode: "DISRUPTION_ALERT",
    templateType: "DISRUPTION_ALERT",
    intent: "DISRUPTION_ALERT",
    channel: "IN_APP",
    recipient: recipientFromDisruptionAlert,
    variables: (payload) => ({
      ...pickStringVariables(payload, ["serviceAlertId", "incidentId", "disruptionType", "scheduledServiceRef", "segmentRef", "serviceDate", "messageSummary"]),
      journeyOrderId: firstString(payload.affectedOrderIds) ?? stringValue(payload.serviceAlertId) ?? "--",
      trainNumber: stringValue(payload.trainNumber)
        ?? stringValue(payload.scheduledServiceRef)
        ?? stringValue(payload.segmentRef)
        ?? stringValue(payload.incidentId)
        ?? "--",
      delayMinutes: stringValue(payload.delayMinutes) ?? numberString(payload.delayMinutes) ?? "0",
    }),
  };
}

function recoveryCaseOpenedMapping(): TriggerMapping {
  return disruptionRecoveryMapping("RECOVERY_CASE_OPENED", "RECOVERY_CASE_OPENED", (payload) => ({
    ...baseRecoveryVariables(payload),
    openedAt: stringValue(payload.openedAt) ?? "--",
  }));
}

function recoveryOptionsGeneratedMapping(): TriggerMapping {
  return disruptionRecoveryMapping(
    "RECOVERY_OPTIONS_AVAILABLE",
    "RECOVERY_OPTIONS_AVAILABLE",
    (payload) => ({
      ...baseRecoveryVariables(payload),
      optionSetId: stringValue(payload.optionSetId) ?? "--",
      optionTypes: optionTypes(payload.options).join(",") || "--",
      expiresAt: stringValue(payload.expiresAt) ?? "--",
      requiresUserChoice: booleanValue(payload.requiresUserChoice) === false ? "false" : "true",
    }),
    (payload) => hasReaccommodation(payload) ? "RECOVERY_REACCOMMODATION" : "RECOVERY_OPTIONS_AVAILABLE",
  );
}

function recoveryOptionSelectedMapping(): TriggerMapping {
  return disruptionRecoveryMapping(
    "RECOVERY_OPTION_SELECTED",
    "RECOVERY_OPTION_SELECTED",
    (payload) => ({
      ...baseRecoveryVariables(payload),
      optionSetId: stringValue(payload.optionSetId) ?? "--",
      optionType: stringValue(payload.optionType) ?? "--",
      selectedAt: stringValue(payload.selectedAt) ?? "--",
      reaccommodationSummary: stringValue(payload.optionType) === "REACCOMMODATION" ? "已选择接续改签方案" : "--",
    }),
    (payload) => stringValue(payload.optionType) === "REACCOMMODATION" ? "RECOVERY_REACCOMMODATION" : "RECOVERY_OPTION_SELECTED",
  );
}

function recoveryExecutionStartedMapping(): TriggerMapping {
  return disruptionRecoveryMapping(
    "RECOVERY_EXECUTION_STARTED",
    "RECOVERY_EXECUTION_STARTED",
    (payload) => ({
      ...baseRecoveryVariables(payload),
      optionType: stringValue(payload.optionType) ?? "--",
      executionId: stringValue(payload.executionId) ?? "--",
      executionTarget: stringValue(payload.executionTarget) ?? "--",
      startedAt: stringValue(payload.startedAt) ?? "--",
      reaccommodationSummary: reaccommodationSummary(payload),
    }),
    (payload) => stringValue(payload.optionType) === "REACCOMMODATION"
      ? "RECOVERY_REACCOMMODATION"
      : "RECOVERY_EXECUTION_STARTED",
  );
}

function recoveryCompletedMapping(): TriggerMapping {
  return disruptionRecoveryMapping(
    "RECOVERY_COMPLETED",
    "RECOVERY_COMPLETED",
    (payload) => ({
      ...baseRecoveryVariables(payload),
      optionType: stringValue(payload.optionType) ?? "--",
      executionId: stringValue(payload.executionId) ?? "--",
      externalRef: stringValue(payload.externalRef) ?? "--",
      completedAt: stringValue(payload.completedAt) ?? "--",
      newTrainNumber: stringValue(payload.newTrainNumber)
        ?? stringValue(payload.externalRef)
        ?? stringValue(payload.journeyOrderId)
        ?? "--",
      newDepartureTime: stringValue(payload.newDepartureTime) ?? stringValue(payload.completedAt) ?? "--",
      reaccommodationSummary: reaccommodationSummary(payload),
    }),
    (payload) => {
      const optionType = stringValue(payload.optionType);
      if (optionType === "REFUND") {
        return "RECOVERY_REFUND_EXECUTED";
      }
      if (optionType === "COMPENSATION") {
        return "RECOVERY_COMPENSATION_ISSUED";
      }
      if (optionType === "REACCOMMODATION") {
        return "RECOVERY_REACCOMMODATION";
      }
      return "RECOVERY_COMPLETED";
    },
  );
}

function recoveryFailedMapping(): TriggerMapping {
  return disruptionRecoveryMapping("RECOVERY_FAILED", "RECOVERY_FAILED", (payload) => ({
    ...baseRecoveryVariables(payload),
    optionId: stringValue(payload.optionId) ?? "--",
    executionId: stringValue(payload.executionId) ?? "--",
    failedAt: stringValue(payload.failedAt) ?? "--",
    nextStatus: stringValue(payload.nextStatus) ?? "--",
  }));
}

function transferAtRiskMapping(): TriggerMapping {
  return transferManagementMapping("TRANSFER_AT_RISK", "TRANSFER_AT_RISK", "TRANSFER_AT_RISK", (payload) => ({
    ...baseTransferVariables(payload),
    previousStatus: stringValue(payload.previousStatus) ?? "--",
    status: stringValue(payload.status) ?? "AT_RISK",
    riskLevel: stringValue(payload.riskLevel) ?? "AT_RISK",
    riskPolicyVersion: stringValue(payload.riskPolicyVersion) ?? "--",
    reasons: stringList(payload.reasons).join(",") || "--",
    detectedAt: stringValue(payload.detectedAt) ?? "--",
    availableMinutes: numberLikeString(recordValue(payload.window)?.availableMinutes) ?? "--",
  }));
}

function connectionMissedMapping(): TriggerMapping {
  return transferManagementMapping("CONNECTION_MISSED", "CONNECTION_MISSED", "CONNECTION_MISSED", (payload) => ({
    ...baseTransferVariables(payload),
    previousStatus: stringValue(payload.previousStatus) ?? "--",
    status: stringValue(payload.status) ?? "MISSED",
    riskLevel: stringValue(payload.riskLevel) ?? "MISSED",
    contractType: stringValue(payload.contractType) ?? "--",
    missedAt: stringValue(payload.missedAt) ?? "--",
    missedCause: stringValue(payload.missedCause) ?? "--",
    recoveryRequired: booleanValue(payload.recoveryRequired) === true ? "true" : "false",
    recoveryMessage: booleanValue(payload.recoveryRequired) === true ? "我们正在为您安排恢复方案" : "请查看车站指引或联系客服",
  }));
}

function connectionRecoveredMapping(): TriggerMapping {
  return transferManagementMapping(
    "CONNECTION_RECOVERED",
    (payload) => stringValue(payload.replacementConnectionId) ? "CONNECTION_REACCOMMODATED" : "CONNECTION_RECOVERED",
    (payload) => stringValue(payload.replacementConnectionId) ? "CONNECTION_REACCOMMODATED" : "CONNECTION_RECOVERED",
    (payload) => ({
      ...baseTransferVariables(payload),
      previousStatus: stringValue(payload.previousStatus) ?? "--",
      status: stringValue(payload.status) ?? "RECOVERED",
      riskLevel: stringValue(payload.riskLevel) ?? "RECOVERED",
      recoveryCaseId: stringValue(payload.recoveryCaseId) ?? "--",
      replacementConnectionId: stringValue(payload.replacementConnectionId) ?? "--",
      recoveredAt: stringValue(payload.recoveredAt) ?? "--",
      reaccommodatedAt: stringValue(payload.reaccommodatedAt) ?? "--",
      recoverySummary: stringValue(payload.recoverySummary) ?? (stringValue(payload.replacementConnectionId) ? "接续已重新安排" : "接续已恢复"),
      nextDepartureAt: replacementWindowTime(payload, "nextDepartureAt") ?? "--",
      plannedArrivalAt: replacementWindowTime(payload, "plannedArrivalAt") ?? "--",
    }),
  );
}

function transferManagementMapping(
  templateCode: string,
  intent: string | ((payload: Record<string, unknown>) => string),
  templateType: NotificationTemplateType | ((payload: Record<string, unknown>) => NotificationTemplateType),
  variables: (payload: Record<string, unknown>) => Record<string, string>,
): TriggerMapping {
  return {
    templateCode,
    templateType,
    intent,
    channel: "PUSH",
    recipient: recipientFromTransferConnection,
    variables,
  };
}

function baseTransferVariables(payload: Record<string, unknown>): Record<string, string> {
  const connection = recordValue(payload.connection);
  return {
    ...pickStringVariables(payload, ["recoveryCaseId", "replacementConnectionId"]),
    connectionId: stringValue(connection?.connectionId) ?? stringValue(payload.connectionId) ?? "--",
    transferPlanId: stringValue(connection?.transferPlanId) ?? stringValue(payload.transferPlanId) ?? "--",
    itineraryRef: stringValue(connection?.itineraryRef) ?? "--",
    journeyOrderId: stringValue(connection?.journeyOrderId) ?? stringValue(payload.journeyOrderId) ?? "--",
    previousSegmentRef: stringValue(connection?.previousSegmentRef) ?? "--",
    nextSegmentRef: stringValue(connection?.nextSegmentRef) ?? "--",
  };
}

function recipientFromTransferConnection(payload: Record<string, unknown>): string | readonly string[] | undefined {
  const connection = recordValue(payload.connection);
  return recipientFromDirectFields(payload)
    ?? recipientRefsFromTravelerRefs(connection?.travelerRefs)
    ?? stringValue(connection?.journeyOrderId);
}

function replacementWindowTime(payload: Record<string, unknown>, field: string): string | undefined {
  return stringValue(recordValue(payload.replacementWindow)?.[field]);
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.flatMap((candidate) => stringValue(candidate) ?? []) : [];
}

function numberLikeString(value: unknown): string | undefined {
  return typeof value === "number" && Number.isFinite(value) ? String(value) : stringValue(value);
}

function disruptionRecoveryMapping(
  templateCode: string,
  intent: string,
  variables: (payload: Record<string, unknown>) => Record<string, string>,
  templateType: NotificationTemplateType | ((payload: Record<string, unknown>) => NotificationTemplateType) = templateCode as NotificationTemplateType,
): TriggerMapping {
  return {
    templateCode,
    templateType,
    intent,
    channel: "IN_APP",
    recipient: recipientFromRecoveryEvent,
    variables,
  };
}

function baseRecoveryVariables(payload: Record<string, unknown>): Record<string, string> {
  return {
    ...pickStringVariables(payload, ["caseId", "incidentId", "disruptionId", "journeyOrderId", "status"]),
    journeyOrderId: stringValue(payload.journeyOrderId) ?? stringValue(recordValue(payload.affectedScope)?.journeyOrderId) ?? "--",
    caseId: stringValue(payload.caseId) ?? "--",
  };
}

function optionTypes(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((candidate) => {
    const record = recordValue(candidate);
    const optionType = record ? stringValue(record.optionType) : undefined;
    return optionType ? [optionType] : [];
  });
}

function hasReaccommodation(payload: Record<string, unknown>): boolean {
  return optionTypes(payload.options).includes("REACCOMMODATION") || stringValue(payload.optionType) === "REACCOMMODATION";
}

function reaccommodationSummary(payload: Record<string, unknown>): string {
  const downstream = recordValue(payload.downstreamRequest);
  const connectionId = stringValue(downstream?.connectionId) ?? stringValue(payload.externalRef) ?? stringValue(payload.optionId);
  return connectionId ? `接续改签 ${connectionId}` : "接续改签恢复";
}

function walletBenefitMapping(templateCode: string, intent: string): TriggerMapping {
  return {
    templateCode,
    intent,
    channel: "PUSH",
    recipient: (payload) => stringValue(payload.accountId),
    variables: (payload) => pickStringVariables(payload, ["benefitId", "accountId", "issuanceSource", "caseId", "status"]),
  };
}

function postSalesMapping(templateCode: string, intent: string): TriggerMapping {
  return {
    templateCode,
    intent,
    channel: "PUSH",
    recipient: (payload) => recipientFromDirectFields(payload) ?? stringValue(payload.actorRef),
    variables: (payload) => pickStringVariables(payload, ["caseId", "orderId", "journeyOrderId", "reasonCode", "decisionKind", "approvalRef", "reason"]),
  };
}

function availableChannels(profile: ContactProfile): readonly NotificationChannel[] {
  const channels: NotificationChannel[] = [];
  if (profile.deviceToken) {
    channels.push("PUSH");
  }
  if (profile.phoneNumber) {
    channels.push("SMS");
  }
  if (profile.emailAddress !== undefined) {
    channels.push("EMAIL");
  }
  return channels.length > 0 ? channels : ["EMAIL"];
}

function recipientFromOrderEvent(payload: Record<string, unknown>): string | undefined {
  return recipientFromDirectFields(payload) ?? recipientFromTravelerRefs(payload.travelerRefs);
}

function recipientFromDirectFields(payload: Record<string, unknown>): string | undefined {
  return stringValue(payload.recipientRef) ?? stringValue(payload.travelerId) ?? stringValue(payload.accountId) ?? stringValue(payload.actorRef);
}

function recipientFromDisruptionAlert(payload: Record<string, unknown>): string | undefined {
  return recipientFromDirectFields(payload) ?? firstString(payload.affectedOrderIds) ?? stringValue(payload.serviceAlertId);
}

function recipientFromRecoveryEvent(payload: Record<string, unknown>): string | undefined {
  return recipientFromDirectFields(payload) ?? stringValue(payload.journeyOrderId);
}

function firstString(value: unknown): string | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }
  return value.find((candidate): candidate is string => typeof candidate === "string" && candidate.trim().length > 0);
}

function recipientFromTravelerRefs(value: unknown): string | undefined {
  return recipientRefsFromTravelerRefs(value)?.[0];
}

function recipientRefsFromTravelerRefs(value: unknown): readonly string[] | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }

  const recipientRefs: string[] = [];
  for (const candidate of value) {
    if (typeof candidate === "string" && candidate.trim().length > 0) {
      recipientRefs.push(candidate);
      continue;
    }
    if (candidate && typeof candidate === "object") {
      const ref = candidate as Record<string, unknown>;
      const recipientRef = stringValue(ref.recipientRef) ?? stringValue(ref.travelerId) ?? stringValue(ref.travelerRef) ?? stringValue(ref.id);
      if (recipientRef !== undefined) {
        recipientRefs.push(recipientRef);
      }
    }
  }
  return recipientRefs.length > 0 ? [...new Set(recipientRefs)] : undefined;
}

function pickStringVariables(payload: Record<string, unknown>, keys: readonly string[]): Record<string, string> {
  const variables: Record<string, string> = {};
  for (const key of keys) {
    const value = payload[key];
    if (typeof value === "string" && value.trim().length > 0) {
      variables[key] = value;
    } else if (typeof value === "number" || typeof value === "boolean") {
      variables[key] = String(value);
    }
  }
  return variables;
}

function recordValue(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function numberString(value: unknown): string | undefined {
  return typeof value === "number" && Number.isFinite(value) ? String(value) : undefined;
}

function moneyString(value: unknown): string | undefined {
  const money = recordValue(value);
  if (!money || typeof money.minorUnits !== "number") {
    return undefined;
  }
  return (money.minorUnits / 100).toFixed(2).replace(/\.00$/, "");
}

function booleanValue(value: unknown): boolean | undefined {
  return typeof value === "boolean" ? value : undefined;
}

function dateValue(value: unknown): Date | undefined {
  if (typeof value !== "string") {
    return undefined;
  }
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? date : undefined;
}
