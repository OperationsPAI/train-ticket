import {
  DomainError,
  CaseTimeline,
  EvidenceRef,
  ManualActionRequest,
  SupportCase,
  CompensationOffer,
  EscalationPolicy,
  SimulatedResolutionPolicy,
  toTicketEscalated,
  toTicketResolved,
  toTicketReopened,
  type CaseChannel,
  type CasePriority,
  type CustomerTier,
  type CompensationType,
  type EscalationLevel,
  type EscalationTriggerCondition,
  type CloseReason,
  type EvidenceAccessLevel,
  type EvidenceType,
  type ManualActionTargetDomain,
  type SupportCaseSnapshot,
  type TimelineEntrySnapshot,
  type CompensationOfferSnapshot,
  type CaseContextSeverity,
  type CaseContextSnapshot,
  type CaseContextType,
} from "../domain.js";
import { newCommandId, newCorrelationId, toEventEnvelope, type EventEnvelope, type EventPublisher } from "./messaging.js";
import { OptimisticConcurrencyConflict, uuidV7 } from "@trainticket/ts-kit";
import { type CustomerServiceRepository } from "./ports/customer-service-repository.js";

export type SupportCaseDetails = SupportCaseSnapshot &
  Readonly<{
    evidence: readonly ReturnType<EvidenceRef["toSnapshot"]>[];
    timeline: readonly TimelineEntrySnapshot[];
    slaMetrics: Readonly<{ timeToFirstResponseMinutes?: number; timeToResolutionMinutes?: number; compliant: boolean }>;
    caseContext: readonly CaseContextSnapshot[];
  }>;

export type OpenSupportCaseRequest = Readonly<{
  requesterRef: string;
  channel: CaseChannel;
  classification?: string;
  priority?: CasePriority;
  description: string;
  businessReferences?: Record<string, string>;
  customerTier?: CustomerTier;
}>;

export type AttachEvidenceRequest = Readonly<{
  evidenceType: EvidenceType;
  reference: string;
  summary: string;
  accessLevel: EvidenceAccessLevel;
  attachedBy: string;
}>;

export type ClassifySupportCaseRequest = Readonly<{ classification: string; priority: CasePriority }>;
export type AssignSupportCaseRequest = Readonly<{ assignedTo?: string; ownerQueue: string }>;
export type EscalateCaseRequest = Readonly<{ targetQueue: string; reason: string; triggerCondition?: EscalationTriggerCondition }>;
export type ResolveCaseRequest = Readonly<{ summary: string; resolutionCode: string }>;
export type CloseCaseRequest = Readonly<{ reason: CloseReason }>;
export type ReopenCaseRequest = Readonly<{ reason: string; requesterRef: string }>;
export type RequestManualActionRequest = Readonly<{
  targetDomain: ManualActionTargetDomain;
  commandType: string;
  operatorRef: string;
  reason: string;
  evidenceRefs?: readonly string[];
  description: string;
  requiresApproval: boolean;
}>;

export type OfferCompensationRequest = Readonly<{
  type: CompensationType;
  amountMinor: number;
  authorizationLevel: EscalationLevel;
  offeredBy: string;
}>;

export class NotFoundError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NotFoundError";
  }
}

export class CustomerServiceApplication {
  private readonly cases = new Map<string, SupportCase>();
  private readonly evidenceByCase = new Map<string, EvidenceRef[]>();
  private readonly timelines = new Map<string, CaseTimeline>();
  private readonly manualActions = new Map<string, ManualActionRequest>();
  private readonly compensationOffers = new Map<string, CompensationOffer>();
  private readonly caseContextByCase = new Map<string, CaseContextSnapshot[]>();
  private readonly consumedIntegrationEvents = new Map<string, Readonly<{ source: string; eventType: string; consumedAt: Date; payload: Readonly<Record<string, unknown>> }>>();

  constructor(
    private readonly publisher: EventPublisher,
    private readonly repository?: CustomerServiceRepository,
  ) {}

  async openSupportCase(request: OpenSupportCaseRequest, correlationId: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const openedAt = new Date();
    const { case: supportCase, event } = SupportCase.open({
      caseId: newSupportCaseId(),
      requesterRef: request.requesterRef,
      channel: request.channel,
      classification: request.classification,
      priority: request.priority ?? "NORMAL",
      description: request.description,
      businessReferences: request.businessReferences,
      customerTier: request.customerTier,
      correlationId,
      causationId,
      openedAt,
    });
    try {
      await this.repository?.saveNewCase(supportCase.toSnapshot());
    } catch (error) {
      if ((error instanceof OptimisticConcurrencyConflict || isUniqueViolation(error)) && this.repository) {
        const duplicate = await this.repository.findDuplicateOpenCase(supportCase.toSnapshot());
        if (duplicate) {
          return duplicate;
        }
      }
      throw error;
    }
    const timeline = CaseTimeline.create(supportCase.id);
    await this.repository?.saveNewTimeline(timeline.toSnapshot());
    this.cases.set(supportCase.id, supportCase);
    this.timelines.set(supportCase.id, timeline);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return supportCase.toSnapshot();
  }

  getSupportCase(caseId: string): SupportCaseDetails {
    const supportCase = this.requireCaseSync(caseId);
    return {
      ...supportCase.toSnapshot(),
      evidence: Object.freeze((this.evidenceByCase.get(caseId) ?? []).map((evidence) => evidence.toSnapshot())),
      timeline: Object.freeze((this.timelines.get(caseId) ?? CaseTimeline.create(caseId)).entries.map((entry) => ({ ...entry }))),
      slaMetrics: slaMetrics(supportCase.toSnapshot()),
      caseContext: Object.freeze((this.caseContextByCase.get(caseId) ?? []).map(cloneCaseContext)),
    };
  }


  async getSupportCaseDetails(caseId: string): Promise<SupportCaseDetails> {
    if (!this.repository) {
      return this.getSupportCase(caseId);
    }
    const supportCase = await this.requireCase(caseId);
    return {
      ...supportCase.toSnapshot(),
      evidence: Object.freeze(await this.repository.evidenceForCase(caseId)),
      timeline: Object.freeze(((await this.findTimeline(caseId)) ?? CaseTimeline.create(caseId)).entries.map((entry) => ({ ...entry }))),
      slaMetrics: slaMetrics(supportCase.toSnapshot()),
      caseContext: Object.freeze(await this.caseContextForCase(caseId)),
    };
  }

  async attachEvidence(caseId: string, request: AttachEvidenceRequest, correlationId: string, causationId = newCommandId()) {
    await this.requireCase(caseId);
    const { evidence, event } = EvidenceRef.attach({
      evidenceId: newEvidenceId(),
      caseId,
      evidenceType: request.evidenceType,
      reference: request.reference,
      summary: request.summary,
      accessLevel: request.accessLevel,
      attachedBy: request.attachedBy,
      correlationId,
      causationId,
      attachedAt: new Date(),
    });
    const evidenceRefs = this.evidenceByCase.get(caseId) ?? [];
    evidenceRefs.push(evidence);
    this.evidenceByCase.set(caseId, evidenceRefs);
    await this.repository?.saveEvidence(evidence.toSnapshot());
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return evidence.toSnapshot();
  }

  async classifySupportCase(caseId: string, request: ClassifySupportCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
    const { case: updated, event } = current.classify({
      caseId,
      classification: request.classification,
      priority: request.priority,
      classifiedBy: operatorRef,
      correlationId,
      causationId,
      classifiedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async assignSupportCase(caseId: string, request: AssignSupportCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
    const { case: updated, event } = current.assign({
      caseId,
      ownerQueue: request.ownerQueue,
      assignedTo: request.assignedTo,
      assignedBy: operatorRef,
      correlationId,
      causationId,
      assignedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async escalateCase(caseId: string, request: EscalateCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
    const triggerCondition = request.triggerCondition ?? "MANUAL";
    const toLevel = triggerCondition === "MANUAL"
      ? undefined
      : EscalationPolicy.targetFor(triggerCondition, current.escalationLevel);
    const { case: updated, event } = current.escalate({
      caseId,
      targetQueue: request.targetQueue,
      reason: request.reason,
      escalatedBy: operatorRef,
      toLevel,
      triggerCondition,
      correlationId,
      causationId,
      escalatedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, causationId));
    await this.publisher.publish(toEventEnvelope(toTicketEscalated(event), causationId));
    return updated.toSnapshot();
  }

  async resolveCase(caseId: string, request: ResolveCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
    const { case: updated, event } = current.resolve({
      caseId,
      summary: request.summary,
      resolutionCode: request.resolutionCode,
      resolvedBy: operatorRef,
      correlationId,
      causationId,
      resolvedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, causationId));
    await this.publisher.publish(toEventEnvelope(toTicketResolved(event), causationId));
    return updated.toSnapshot();
  }

  async closeCase(caseId: string, request: CloseCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
    const { case: updated, event } = current.close({
      caseId,
      reason: request.reason,
      closedBy: operatorRef,
      correlationId,
      causationId,
      closedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async reopenCase(caseId: string, request: ReopenCaseRequest, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
    const { case: updated, event } = current.reopen({
      caseId,
      reason: request.reason,
      requesterRef: request.requesterRef,
      correlationId,
      causationId,
      reopenedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, causationId));
    await this.publisher.publish(toEventEnvelope(toTicketReopened(event), causationId));
    return updated.toSnapshot();
  }

  async requestManualAction(caseId: string, request: RequestManualActionRequest, correlationId: string, causationId = newCommandId()) {
    await this.requireCase(caseId);
    const { action, event } = ManualActionRequest.request({
      manualActionId: newManualActionId(),
      caseId,
      targetDomain: request.targetDomain,
      commandType: request.commandType,
      operatorRef: request.operatorRef,
      reason: request.reason,
      evidenceRefs: request.evidenceRefs,
      description: request.description,
      requiresApproval: request.requiresApproval,
      correlationId,
      causationId,
      requestedAt: new Date(),
    });
    this.manualActions.set(action.id, action);
    await this.repository?.saveNewManualAction(action.toSnapshot());
    await this.appendTimelineEntry(caseId, "ManualActionRequested", toEventEnvelope(event, causationId).payload, "INTERNAL_ONLY", event.occurredAt, event.correlationId, event.causationId);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return action.toSnapshot();
  }

  async offerCompensation(caseId: string, request: OfferCompensationRequest, correlationId: string, causationId = newCommandId()): Promise<CompensationOfferSnapshot> {
    await this.requireCase(caseId);
    const { offer, event } = CompensationOffer.offer({
      offerId: newCompensationOfferId(),
      ticketId: caseId,
      type: request.type,
      amountMinor: request.amountMinor,
      authorizationLevel: request.authorizationLevel,
      offeredBy: request.offeredBy,
      correlationId,
      causationId,
      offeredAt: new Date(),
    });
    this.compensationOffers.set(offer.id, offer);
    await this.repository?.saveNewCompensationOffer(offer.toSnapshot());
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return offer.toSnapshot();
  }

  async acceptCompensation(offerId: string, correlationId: string, causationId = newCommandId()): Promise<CompensationOfferSnapshot> {
    const versioned = await this.requireCompensationOffer(offerId);
    const { offer, event } = versioned.aggregate.accept({ offerId, acceptedAt: new Date(), correlationId, causationId });
    this.compensationOffers.set(offerId, offer);
    await this.repository?.saveCompensationOffer(offer.toSnapshot(), versioned.version ?? 0n);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return offer.toSnapshot();
  }

  async issueCompensation(offerId: string, correlationId: string, causationId = newCommandId()): Promise<CompensationOfferSnapshot> {
    const versioned = await this.requireCompensationOffer(offerId);
    const { offer, event } = versioned.aggregate.issue({ offerId, issuedAt: new Date(), correlationId, causationId });
    this.compensationOffers.set(offerId, offer);
    await this.repository?.saveCompensationOffer(offer.toSnapshot(), versioned.version ?? 0n);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return offer.toSnapshot();
  }

  async evaluateEscalationAndSla(caseId: string, now = new Date(), correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const effectiveCorrelationId = correlationId ?? newCorrelationId();
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
    let updated = current;
    const autoRule = EscalationPolicy.autoEscalation(now, updated.toSnapshot());
    if (autoRule) {
      const result = updated.escalate({ caseId, targetQueue: queueForLevel(autoRule.toLevel), reason: autoRule.triggerCondition, escalatedBy: "op-system-sla", toLevel: autoRule.toLevel, triggerCondition: autoRule.triggerCondition, correlationId: effectiveCorrelationId, causationId, escalatedAt: now });
      updated = result.case;
      await this.publisher.publish(toEventEnvelope(result.event, causationId));
      await this.publisher.publish(toEventEnvelope(toTicketEscalated(result.event), causationId));
    } else {
      const simulation = SimulatedResolutionPolicy.decisionAt(now, updated.toSnapshot());
      if (simulation?.action === "RESOLVE") {
        const result = updated.resolveBySimulation({
          caseId,
          summary: `Auto-resolved at ${simulation.level} by simulated support workflow`,
          resolutionCode: `AUTO_RESOLVED_${simulation.level}`,
          resolvedBy: "op-system-resolution",
          correlationId: effectiveCorrelationId,
          causationId,
          resolvedAt: now,
        });
        updated = result.case;
        await this.publisher.publish(toEventEnvelope(result.event, causationId));
        await this.publisher.publish(toEventEnvelope(toTicketResolved(result.event), causationId));
      } else if (simulation?.action === "ESCALATE" && updated.escalationLevel !== "L3_SUPERVISOR") {
        const toLevel = EscalationPolicy.targetFor("MANUAL", updated.escalationLevel);
        const result = updated.escalate({
          caseId,
          targetQueue: queueForLevel(toLevel),
          reason: `Simulated ${simulation.level} handling did not resolve ticket`,
          escalatedBy: "op-system-resolution",
          toLevel,
          triggerCondition: "MANUAL",
          correlationId: effectiveCorrelationId,
          causationId,
          escalatedAt: now,
        });
        updated = result.case;
        await this.publisher.publish(toEventEnvelope(result.event, causationId));
        await this.publisher.publish(toEventEnvelope(toTicketEscalated(result.event), causationId));
      }
    }
    for (const breachType of updated.slaTracker.breachesAt(now)) {
      const breachEvent = updated.createSlaBreachEvent(breachType, now, effectiveCorrelationId, causationId);
      updated = updated.markSlaBreach(breachType, now);
      await this.publisher.publish(toEventEnvelope(breachEvent, causationId));
      if (breachType === "RESPONSE" && updated.escalationLevel !== "L3_SUPERVISOR") {
        const toLevel = EscalationPolicy.targetFor("RESPONSE_SLA_BREACH", updated.escalationLevel);
        const result = updated.escalate({ caseId, targetQueue: queueForLevel(toLevel), reason: "Response SLA breached; supervisor alert required", escalatedBy: "op-system-sla", toLevel, triggerCondition: "RESPONSE_SLA_BREACH", correlationId: effectiveCorrelationId, causationId, escalatedAt: now });
        updated = result.case;
        await this.publisher.publish(toEventEnvelope(result.event, causationId));
        await this.publisher.publish(toEventEnvelope(toTicketEscalated(result.event), causationId));
      }
      if (breachType === "RESOLUTION") {
        await this.createResolutionBreachCompensationOffer(caseId, updated, now, effectiveCorrelationId, causationId);
      }
    }
    this.cases.set(caseId, updated);
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    return updated.toSnapshot();
  }

  async evaluateOpenTickets(now = new Date(), correlationId = `corr-${uuidV7()}`, causationId = newCommandId()): Promise<readonly SupportCaseSnapshot[]> {
    const cases = this.repository ? await this.repository.listOpenCasesForEvaluation() : [...this.cases.values()].filter((supportCase) => !isTerminalCase(supportCase.toSnapshot()));
    const evaluated: SupportCaseSnapshot[] = [];
    for (const supportCase of cases) {
      evaluated.push(await this.evaluateEscalationAndSla(supportCase.id, now, correlationId, causationId));
    }
    return Object.freeze(evaluated);
  }

  async handleIntegrationEvent(envelope: EventEnvelope): Promise<void> {
    if (this.consumedIntegrationEvents.has(envelope.eventId)) {
      return;
    }

    if (envelope.producer === "admin-audit" && (envelope.eventType === "ManualActionExecuted" || envelope.eventType === "ManualActionRejected")) {
      await this.recordAdminAuditManualActionOutcome(envelope);
    } else if (envelope.producer === "transfer-management" || envelope.producer === "identity-verification") {
      await this.projectCaseContext(envelope);
    } else {
      for (const supportCase of await this.findCasesReferencingEnvelope(envelope)) {
        if (caseReferencesEnvelope(supportCase.toSnapshot(), envelope)) {
          await this.appendTimelineEntry(
            supportCase.id,
            envelope.eventType,
            { sourceEventId: envelope.eventId, producer: envelope.producer, payload: envelope.payload },
            "INTERNAL_ONLY",
            new Date(envelope.occurredAt),
            envelope.correlationId,
            envelope.causationId,
          );
        }
      }
    }

    this.consumedIntegrationEvents.set(envelope.eventId, Object.freeze({
      source: envelope.producer,
      eventType: envelope.eventType,
      consumedAt: new Date(),
      payload: envelope.payload,
    }));
  }

  consumedIntegrationEventCount(): number {
    return this.consumedIntegrationEvents.size;
  }

  private async projectCaseContext(envelope: EventEnvelope): Promise<void> {
    const projection = caseContextProjection(envelope);
    if (!projection) {
      return;
    }

    for (const supportCase of await this.findCasesReferencingCaseContext(envelope)) {
      if (!caseMatchesContextRefs(supportCase.toSnapshot(), projection.refs)) {
        continue;
      }
      if (await this.hasCaseContextForEvent(supportCase.id, envelope.eventId)) {
        continue;
      }

      const context: CaseContextSnapshot = Object.freeze({
        contextId: newCaseContextId(),
        caseId: supportCase.id,
        source: envelope.producer as CaseContextSnapshot["source"],
        sourceEventId: envelope.eventId,
        sourceEventType: envelope.eventType,
        contextType: projection.contextType,
        severity: projection.severity,
        occurredAt: new Date(envelope.occurredAt),
        refs: Object.freeze({ ...projection.refs }),
        summary: projection.summary,
        facts: deepFreezeRecord({ ...projection.facts }),
        createdAt: new Date(),
      });

      const current = this.caseContextByCase.get(supportCase.id) ?? [];
      current.push(context);
      this.caseContextByCase.set(supportCase.id, current);
      await this.repository?.saveCaseContext(context);
      await this.appendTimelineEntry(
        supportCase.id,
        `CaseContext.${projection.contextType}`,
        { sourceEventId: envelope.eventId, producer: envelope.producer, contextType: projection.contextType, severity: projection.severity, refs: context.refs, facts: context.facts },
        "INTERNAL_ONLY",
        new Date(envelope.occurredAt),
        envelope.correlationId,
        envelope.eventId,
      );
      if (projection.severity === "CRITICAL") {
        await this.escalateForCaseContext(supportCase.id, context, envelope);
      }
    }
  }

  private async escalateForCaseContext(caseId: string, context: CaseContextSnapshot, envelope: EventEnvelope): Promise<void> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
    if (isTerminalCase(current.toSnapshot())) {
      return;
    }
    const toLevel = escalationLevelForContext(context.contextType, current.escalationLevel);
    const { case: updated, event } = current.escalate({
      caseId,
      targetQueue: queueForCaseContext(context.contextType),
      reason: context.summary,
      escalatedBy: "op-system-case-context",
      toLevel,
      triggerCondition: "MANUAL",
      correlationId: envelope.correlationId,
      causationId: envelope.eventId,
      escalatedAt: new Date(envelope.occurredAt),
    });
    this.cases.set(caseId, updated);
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, envelope.eventId));
    await this.publisher.publish(toEventEnvelope(toTicketEscalated(event), envelope.eventId));
  }

  private async findCasesReferencingCaseContext(envelope: EventEnvelope): Promise<SupportCase[]> {
    const cases = this.repository ? await this.repository.listCases() : [...this.cases.values()];
    const projection = caseContextProjection(envelope);
    if (!projection) {
      return [];
    }
    return cases.filter((supportCase) => caseMatchesContextRefs(supportCase.toSnapshot(), projection.refs));
  }

  private async caseContextForCase(caseId: string): Promise<CaseContextSnapshot[]> {
    if (this.repository) {
      return await this.repository.caseContextForCase(caseId);
    }
    return (this.caseContextByCase.get(caseId) ?? []).map(cloneCaseContext);
  }

  private async hasCaseContextForEvent(caseId: string, sourceEventId: string): Promise<boolean> {
    if (this.repository) {
      return await this.repository.hasCaseContextForEvent(caseId, sourceEventId);
    }
    return (this.caseContextByCase.get(caseId) ?? []).some((context) => context.sourceEventId === sourceEventId);
  }

  private async recordAdminAuditManualActionOutcome(envelope: EventEnvelope): Promise<void> {
    const manualActionId = requiredPayloadString(envelope.payload, "manualActionId");
    const versionedAction = await this.findManualAction(manualActionId);
    const action = versionedAction?.aggregate;
    if (!action) {
      return;
    }
    const resultSummary = envelope.eventType === "ManualActionRejected"
      ? requiredPayloadString(envelope.payload, "reason")
      : requiredPayloadString(envelope.payload, "resultSummary");
    const outcome = manualActionOutcome(envelope.eventType, resultSummary);
    const { action: updated, event } = action.recordOutcome({
      manualActionId,
      outcome,
      resultSummary,
      recordedAt: new Date(envelope.occurredAt),
      correlationId: envelope.correlationId,
      causationId: envelope.eventId,
    });
    this.manualActions.set(manualActionId, updated);
    if (this.repository && versionedAction) {
      await this.repository.saveManualAction(updated.toSnapshot(), versionedAction.version ?? 0n);
    }
    const eventEnvelope = toEventEnvelope(event, envelope.eventId);
    await this.publisher.publish(eventEnvelope);
    await this.appendTimelineEntry(
      updated.caseId,
      "ManualActionResultRecorded",
      eventEnvelope.payload,
      "INTERNAL_ONLY",
      event.occurredAt,
      envelope.correlationId,
      envelope.eventId,
    );
  }

  private async createResolutionBreachCompensationOffer(caseId: string, supportCase: SupportCase, offeredAt: Date, correlationId: string, causationId: string): Promise<void> {
    const { offer, event } = CompensationOffer.offer({
      offerId: newCompensationOfferId(),
      ticketId: caseId,
      type: "VOUCHER",
      amountMinor: resolutionBreachCompensationAmount(supportCase.toSnapshot()),
      authorizationLevel: compensationAuthorizationLevel(supportCase.escalationLevel),
      offeredBy: "op-system-sla",
      correlationId,
      causationId,
      offeredAt,
    });
    this.compensationOffers.set(offer.id, offer);
    await this.repository?.saveNewCompensationOffer(offer.toSnapshot());
    await this.publisher.publish(toEventEnvelope(event, causationId));
  }

  private async appendTimelineEntry(caseId: string, eventType: string, payload: Readonly<Record<string, unknown>>, visibility: "CUSTOMER_VISIBLE" | "INTERNAL_ONLY", occurredAt: Date, correlationId: string, causationId?: string): Promise<void> {
    const versionedTimeline = await this.findVersionedTimeline(caseId);
    const current = versionedTimeline?.aggregate ?? CaseTimeline.create(caseId);
    const { timeline, event } = current.append({
      entryId: newTimelineEntryId(),
      caseId,
      eventType,
      payload,
      visibility,
      occurredAt,
      correlationId,
      causationId,
    });
    this.timelines.set(caseId, timeline);
    if (this.repository) {
      if (versionedTimeline) {
        await this.repository.saveTimeline(timeline.toSnapshot(), versionedTimeline.version);
      } else {
        await this.repository.saveNewTimeline(timeline.toSnapshot());
      }
    }
    await this.publisher.publish(toEventEnvelope(event, event.causationId ?? newCommandId()));
  }

  private requireCaseSync(caseId: string): SupportCase {
    const supportCase = this.cases.get(caseId);
    if (!supportCase) {
      throw new NotFoundError(`Support case ${caseId} was not found`);
    }
    return supportCase;
  }

  private async requireCase(caseId: string): Promise<SupportCase> {
    return (await this.requireVersionedCase(caseId)).aggregate;
  }

  private async requireVersionedCase(caseId: string): Promise<Readonly<{ aggregate: SupportCase; version: bigint | undefined }>> {
    const persisted = await this.repository?.findCase(caseId);
    if (persisted) {
      return persisted;
    }
    const supportCase = this.cases.get(caseId);
    if (!supportCase) {
      throw new NotFoundError(`Support case ${caseId} was not found`);
    }
    return { aggregate: supportCase, version: undefined };
  }

  private async findTimeline(caseId: string): Promise<CaseTimeline | undefined> {
    return (await this.findVersionedTimeline(caseId))?.aggregate;
  }

  private async findVersionedTimeline(caseId: string): Promise<Readonly<{ aggregate: CaseTimeline; version: bigint }> | undefined> {
    return await this.repository?.findTimeline(caseId) ?? (this.timelines.has(caseId) ? { aggregate: this.timelines.get(caseId)!, version: 0n } : undefined);
  }

  private async findManualAction(manualActionId: string): Promise<Readonly<{ aggregate: ManualActionRequest; version: bigint | undefined }> | undefined> {
    const persisted = await this.repository?.findManualAction(manualActionId);
    if (persisted) {
      return persisted;
    }
    const action = this.manualActions.get(manualActionId);
    return action ? { aggregate: action, version: undefined } : undefined;
  }

  private async requireCompensationOffer(offerId: string): Promise<Readonly<{ aggregate: CompensationOffer; version: bigint | undefined }>> {
    const persisted = await this.repository?.findCompensationOffer(offerId);
    if (persisted) {
      return persisted;
    }
    const offer = this.compensationOffers.get(offerId);
    if (!offer) {
      throw new NotFoundError(`Compensation offer ${offerId} was not found`);
    }
    return { aggregate: offer, version: undefined };
  }

  private async findCasesReferencingEnvelope(envelope: EventEnvelope): Promise<SupportCase[]> {
    if (!this.repository) {
      return [...this.cases.values()];
    }
    return (await this.repository.listCases()).filter((supportCase) => caseReferencesEnvelope(supportCase.toSnapshot(), envelope));
  }
}

export function isDomainError(error: unknown): error is DomainError {
  return error instanceof DomainError;
}

function isUniqueViolation(error: unknown): boolean {
  return typeof error === "object" && error !== null && (error as { code?: unknown }).code === "23505";
}

function requiredPayloadString(payload: Record<string, unknown>, field: string): string {
  const value = stringField(payload, field);
  if (value === undefined) {
    throw new DomainError("MISSING_REQUIRED_FIELD", `${field} is required`);
  }
  return value;
}

function manualActionOutcome(eventType: string, resultSummary: string): "Succeeded" | "Failed" | "Rejected" {
  if (eventType === "ManualActionRejected") {
    return "Rejected";
  }
  return resultSummary.trim().toUpperCase().startsWith("FAILED:") ? "Failed" : "Succeeded";
}

function caseReferencesEnvelope(snapshot: SupportCaseSnapshot, envelope: EventEnvelope): boolean {
  if (envelope.producer !== "journey-order" && envelope.producer !== "post-sales") {
    return false;
  }
  const references = snapshot.businessReferences;
  const orderId = stringField(envelope.payload, "orderId") ?? stringField(envelope.payload, "journeyOrderId");
  const postSalesCaseId = stringField(envelope.payload, "caseId");
  return (references.journeyOrderId !== undefined && references.journeyOrderId === orderId)
    || (references.postSalesCaseId !== undefined && references.postSalesCaseId === postSalesCaseId);
}

function stringField(payload: Record<string, unknown> | undefined, field: string): string | undefined {
  const value = payload?.[field];
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function newSupportCaseId(): string {
  return `sc-${uuidV7()}`;
}

function newEvidenceId(): string {
  return `evid-${uuidV7()}`;
}

function newManualActionId(): string {
  return `ma-${uuidV7()}`;
}

function newCompensationOfferId(): string {
  return `co-${uuidV7()}`;
}

function isTerminalCase(snapshot: SupportCaseSnapshot): boolean {
  return snapshot.status === "Resolved" || snapshot.status === "Closed";
}

function compensationAuthorizationLevel(level: EscalationLevel): EscalationLevel {
  return level === "L1_AGENT" ? "L2_SPECIALIST" : level;
}

function resolutionBreachCompensationAmount(snapshot: SupportCaseSnapshot): number {
  switch (snapshot.priority ?? "NORMAL") {
    case "URGENT": return 20_000;
    case "HIGH": return 10_000;
    case "NORMAL": return 5_000;
    case "LOW": return 2_000;
  }
}

function queueForLevel(level: EscalationLevel): string {
  switch (level) {
    case "L1_AGENT": return "l1-agent";
    case "L2_SPECIALIST": return "l2-specialist";
    case "L3_SUPERVISOR": return "l3-supervisor";
  }
}

function slaMetrics(snapshot: SupportCaseSnapshot): SupportCaseDetails["slaMetrics"] {
  const first = snapshot.slaTracker.firstResponseAt ? Math.floor((snapshot.slaTracker.firstResponseAt.getTime() - snapshot.slaTracker.openedAt.getTime()) / 60_000) : undefined;
  const resolution = snapshot.slaTracker.resolvedAt ? Math.floor((snapshot.slaTracker.resolvedAt.getTime() - snapshot.slaTracker.openedAt.getTime()) / 60_000) : undefined;
  return { timeToFirstResponseMinutes: first, timeToResolutionMinutes: resolution, compliant: snapshot.slaBreaches.length === 0 };
}

function newTimelineEntryId(): string {
  return `tl-${uuidV7()}`;
}

function newCaseContextId(): string {
  return `ctx-${uuidV7()}`;
}

type CaseContextProjection = Readonly<{
  contextType: CaseContextType;
  severity: CaseContextSeverity;
  refs: Readonly<Record<string, string>>;
  summary: string;
  facts: Readonly<Record<string, unknown>>;
}>;

function caseContextProjection(envelope: EventEnvelope): CaseContextProjection | undefined {
  if (envelope.producer === "transfer-management") {
    return transferCaseContextProjection(envelope);
  }
  if (envelope.producer === "identity-verification") {
    return identityCaseContextProjection(envelope);
  }
  return undefined;
}

function transferCaseContextProjection(envelope: EventEnvelope): CaseContextProjection | undefined {
  const payload = envelope.payload;
  const connection = objectField(payload, "connection");
  const refs = compactStringRecord({
    connectionId: stringField(connection, "connectionId") ?? stringField(payload, "connectionId"),
    transferPlanId: stringField(connection, "transferPlanId") ?? stringField(payload, "transferPlanId"),
    journeyOrderId: stringField(connection, "journeyOrderId") ?? stringField(payload, "journeyOrderId"),
    recoveryCaseId: stringField(payload, "recoveryCaseId") ?? firstString(arrayField(objectField(payload, "recovery"), "caseIds")),
  });
  if (Object.keys(refs).length === 0) {
    return undefined;
  }

  if (envelope.eventType === "ConnectionMissed") {
    const missedCause = stringField(payload, "missedCause") ?? "UNKNOWN";
    return {
      contextType: "MISSED_CONNECTION",
      severity: "CRITICAL",
      refs,
      summary: `Connection ${refs.connectionId ?? "unknown"} missed (${missedCause})`,
      facts: compactRecord({
        previousStatus: stringField(payload, "previousStatus"),
        status: stringField(payload, "status"),
        riskLevel: stringField(payload, "riskLevel"),
        contractType: stringField(payload, "contractType"),
        missedAt: stringField(payload, "missedAt"),
        missedCause,
        recoveryRequired: booleanField(payload, "recoveryRequired"),
      }),
    };
  }

  if (envelope.eventType === "ConnectionRecovered") {
    const replacementConnectionId = stringField(payload, "replacementConnectionId");
    return {
      contextType: "CONNECTION_RECOVERED",
      severity: "INFO",
      refs: compactStringRecord({ ...refs, replacementConnectionId }),
      summary: replacementConnectionId
        ? `Connection ${refs.connectionId ?? "unknown"} recovered by reaccommodation ${replacementConnectionId}`
        : `Connection ${refs.connectionId ?? "unknown"} recovered`,
      facts: compactRecord({
        previousStatus: stringField(payload, "previousStatus"),
        status: stringField(payload, "status"),
        riskLevel: stringField(payload, "riskLevel"),
        recoveredAt: stringField(payload, "recoveredAt"),
        recoverySummary: stringField(payload, "recoverySummary"),
        replacementConnectionId,
        reaccommodatedAt: stringField(payload, "reaccommodatedAt"),
        disruptionRecoveryEventId: stringField(payload, "disruptionRecoveryEventId"),
      }),
    };
  }

  if (envelope.eventType === "ConnectionRecoveryFailed") {
    const reason = stringField(payload, "reason") ?? "UNKNOWN";
    return {
      contextType: "CONNECTION_RECOVERY_FAILED",
      severity: "CRITICAL",
      refs,
      summary: `Recovery failed for connection ${refs.connectionId ?? "unknown"}: ${reason}`,
      facts: compactRecord({
        status: stringField(payload, "status"),
        failedAt: stringField(payload, "failedAt"),
        reason,
        disruptionRecoveryEventId: stringField(payload, "disruptionRecoveryEventId"),
      }),
    };
  }

  return undefined;
}

function identityCaseContextProjection(envelope: EventEnvelope): CaseContextProjection | undefined {
  const payload = envelope.payload;
  const reason = stringField(payload, "reasonCode") ?? stringField(payload, "reason") ?? stringField(payload, "failureCode");
  const refs = compactStringRecord({
    travelerId: stringField(payload, "travelerId"),
    credentialRecordId: stringField(payload, "credentialRecordId"),
    verificationCaseId: stringField(payload, "verificationCaseId"),
    purchaseLimitFactId: stringField(payload, "purchaseLimitFactId"),
    journeyOrderId: stringField(payload, "journeyOrderId"),
    orderIntentId: stringField(payload, "orderIntentId"),
  });
  if (Object.keys(refs).length === 0) {
    return undefined;
  }

  if (envelope.eventType === "VerificationFailed") {
    const contextType = identityContextType(reason);
    return {
      contextType,
      severity: contextType === "IDENTITY_VERIFICATION_FAILED" ? "WARNING" : "CRITICAL",
      refs,
      summary: identitySummary(contextType, refs.travelerId, reason),
      facts: compactRecord({
        simOutcome: stringField(payload, "simOutcome"),
        verificationStatus: stringField(payload, "verificationStatus"),
        reasonCode: reason,
        policyVersion: stringField(payload, "policyVersion"),
        completedAt: stringField(payload, "completedAt") ?? stringField(payload, "rejectedAt"),
      }),
    };
  }

  if (envelope.eventType === "PurchaseLimitFactFailed" || envelope.eventType === "PurchaseLimitFactMissed") {
    return {
      contextType: "DUPLICATE_TICKET_SIGNAL",
      severity: "CRITICAL",
      refs,
      summary: `Duplicate-ticket control ${envelope.eventType === "PurchaseLimitFactMissed" ? "missed" : "failed"}`,
      facts: compactRecord({
        factStatus: stringField(payload, "factStatus"),
        limitPolicyVersion: stringField(payload, "limitPolicyVersion"),
        failedAt: stringField(payload, "failedAt"),
        missedAt: stringField(payload, "missedAt"),
        failureCode: stringField(payload, "failureCode"),
      }),
    };
  }

  return undefined;
}

function identityContextType(reason: string | undefined): CaseContextType {
  const normalized = reason?.trim().toUpperCase() ?? "";
  if (normalized.includes("DUPLICATE_TICKET")) {
    return "DUPLICATE_TICKET_SIGNAL";
  }
  if (normalized.includes("BLACKLIST") || normalized.includes("TRAVEL_BAN") || normalized.includes("RESTRICTED")) {
    return "IDENTITY_BLACKLIST_SIGNAL";
  }
  return "IDENTITY_VERIFICATION_FAILED";
}

function identitySummary(contextType: CaseContextType, travelerId: string | undefined, reason: string | undefined): string {
  const subject = travelerId ?? "traveler";
  switch (contextType) {
    case "IDENTITY_BLACKLIST_SIGNAL": return `Identity blacklist signal for ${subject}`;
    case "DUPLICATE_TICKET_SIGNAL": return `Duplicate-ticket signal for ${subject}`;
    default: return `Identity verification failed for ${subject}${reason ? ` (${reason})` : ""}`;
  }
}

function caseMatchesContextRefs(snapshot: SupportCaseSnapshot, refs: Readonly<Record<string, string>>): boolean {
  const businessRefs = snapshot.businessReferences;
  return matchesOptionalRef(businessRefs.journeyOrderId, refs.journeyOrderId)
    || matchesOptionalRef(businessRefs.recoveryCaseId, refs.recoveryCaseId)
    || matchesOptionalRef(businessRefs.accountRef, refs.travelerId)
    || matchesOptionalRef(snapshot.requesterRef, refs.travelerId)
    || matchesOptionalRef(businessRefs.journeyOrderId, refs.orderIntentId)
    || matchesOptionalRef(businessRefField(businessRefs, "connectionId"), refs.connectionId)
    || matchesOptionalRef(businessRefField(businessRefs, "transferPlanId"), refs.transferPlanId)
    || matchesOptionalRef(businessRefField(businessRefs, "verificationCaseId"), refs.verificationCaseId)
    || matchesOptionalRef(businessRefField(businessRefs, "credentialRecordId"), refs.credentialRecordId)
    || matchesOptionalRef(businessRefField(businessRefs, "purchaseLimitFactId"), refs.purchaseLimitFactId)
    || matchesOptionalRef(businessRefs.postSalesCaseId, refs.verificationCaseId);
}

function businessRefField(refs: SupportCaseSnapshot["businessReferences"], field: string): string | undefined {
  const value = (refs as Readonly<Record<string, unknown>>)[field];
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function matchesOptionalRef(caseRef: string | undefined, contextRef: string | undefined): boolean {
  return caseRef !== undefined && contextRef !== undefined && caseRef === contextRef;
}

function escalationLevelForContext(contextType: CaseContextType, currentLevel: EscalationLevel): EscalationLevel {
  if (currentLevel === "L3_SUPERVISOR") {
    return "L3_SUPERVISOR";
  }
  if (contextType === "IDENTITY_BLACKLIST_SIGNAL" || contextType === "DUPLICATE_TICKET_SIGNAL") {
    return "L3_SUPERVISOR";
  }
  return EscalationPolicy.targetFor("MANUAL", currentLevel);
}

function queueForCaseContext(contextType: CaseContextType): string {
  switch (contextType) {
    case "IDENTITY_BLACKLIST_SIGNAL": return "identity-risk-supervisor";
    case "DUPLICATE_TICKET_SIGNAL": return "duplicate-ticket-review";
    case "CONNECTION_RECOVERY_FAILED": return "disruption-recovery-escalation";
    case "MISSED_CONNECTION": return "missed-connection-support";
    case "CONNECTION_RECOVERED": return "connection-recovery-support";
    case "IDENTITY_VERIFICATION_FAILED": return "identity-verification-support";
  }
}

function objectField(payload: Record<string, unknown> | undefined, field: string): Record<string, unknown> | undefined {
  const value = payload?.[field];
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

function arrayField(payload: Record<string, unknown> | undefined, field: string): readonly unknown[] | undefined {
  const value = payload?.[field];
  return Array.isArray(value) ? value : undefined;
}

function booleanField(payload: Record<string, unknown>, field: string): boolean | undefined {
  const value = payload[field];
  return typeof value === "boolean" ? value : undefined;
}

function firstString(values: readonly unknown[] | undefined): string | undefined {
  const value = values?.find((entry) => typeof entry === "string" && entry.trim().length > 0);
  return typeof value === "string" ? value : undefined;
}

function compactStringRecord(values: Record<string, string | undefined>): Readonly<Record<string, string>> {
  const compacted: Record<string, string> = {};
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined) {
      compacted[key] = value;
    }
  }
  return Object.freeze(compacted);
}

function compactRecord(values: Record<string, unknown>): Readonly<Record<string, unknown>> {
  const compacted: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined) {
      compacted[key] = value;
    }
  }
  return Object.freeze(compacted);
}

function cloneCaseContext(context: CaseContextSnapshot): CaseContextSnapshot {
  return Object.freeze({
    ...context,
    occurredAt: new Date(context.occurredAt),
    refs: Object.freeze({ ...context.refs }),
    facts: deepFreezeRecord({ ...context.facts }),
    createdAt: new Date(context.createdAt),
  });
}

function deepFreezeRecord<T extends Record<string, unknown>>(value: T): Readonly<T> {
  for (const nested of Object.values(value)) {
    if (Array.isArray(nested)) {
      Object.freeze(nested);
    } else if (nested && typeof nested === "object") {
      deepFreezeRecord(nested as Record<string, unknown>);
    }
  }
  return Object.freeze(value);
}
