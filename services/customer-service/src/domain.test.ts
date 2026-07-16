import assert from "node:assert/strict";
import crypto from "node:crypto";
import { describe, it } from "node:test";

import {
  DomainError,
  SupportCase,
  EvidenceRef,
  ManualActionRequest,
  CaseTimeline,
  EscalationPolicy,
  SimulatedResolutionPolicy,
  CompensationOffer,
  customerServiceBoundaryProof,
  type OpenSupportCase,
  type ClassifySupportCase,
  type AssignSupportCase,
  type AttachEvidence,
  type RequestManualAction,
  type RecordActionOutcome,
  type EscalateCase,
  type ResolveCase,
  type CloseCase,
  type ReopenCase,
  type AppendTimelineEntry,
  type OfferCompensation,
} from "./domain.js";

const openedAt = new Date("2026-07-03T10:00:00.000Z");
const testCorrelationId = "corr-test-001";

function openCommand(overrides: Partial<OpenSupportCase> = {}): OpenSupportCase {
  return {
    caseId: "sc-test-001",
    requesterRef: "tvl-test-001",
    channel: "APP",
    description: "Payment not reflected after successful charge",
    correlationId: testCorrelationId,
    causationId: "cmd-test-001",
    openedAt,
    ...overrides,
  };
}

function classifyCommand(overrides: Partial<ClassifySupportCase> = {}): ClassifySupportCase {
  return {
    caseId: "sc-test-001",
    classification: "PAYMENT_DISPUTE",
    priority: "HIGH",
    classifiedBy: "op-test-001",
    classifiedAt: new Date("2026-07-03T10:02:00.000Z"),
    ...overrides,
  };
}

function assignCommand(overrides: Partial<AssignSupportCase> = {}): AssignSupportCase {
  return {
    caseId: "sc-test-001",
    ownerQueue: "payment-specialist",
    assignedTo: "op-test-002",
    assignedBy: "op-test-001",
    assignedAt: new Date("2026-07-03T10:03:00.000Z"),
    ...overrides,
  };
}

function attachEvidenceCommand(overrides: Partial<AttachEvidence> = {}): AttachEvidence {
  return {
    evidenceId: "evid-test-001",
    caseId: "sc-test-001",
    evidenceType: "SCREENSHOT",
    reference: "https://drive.example.com/evid-001",
    summary: "Payment receipt screenshot",
    accessLevel: "SENSITIVE",
    attachedBy: "op-test-001",
    correlationId: testCorrelationId,
    causationId: "cmd-test-001",
    attachedAt: new Date("2026-07-03T10:01:00.000Z"),
    ...overrides,
  };
}

function manualActionCommand(overrides: Partial<RequestManualAction> = {}): RequestManualAction {
  return {
    manualActionId: "ma-test-001",
    caseId: "sc-test-001",
    targetDomain: "payment",
    commandType: "ManualPaymentActionRequested",
    operatorRef: "op-test-002",
    reason: "Customer requests payment retry",
    evidenceRefs: ["evid-test-001"],
    description: "Retry failed payment capture for order ord-123",
    requiresApproval: true,
    correlationId: testCorrelationId,
    causationId: "cmd-test-002",
    requestedAt: new Date("2026-07-03T10:05:00.000Z"),
    ...overrides,
  };
}

function recordOutcomeCommand(overrides: Partial<RecordActionOutcome> = {}): RecordActionOutcome {
  return {
    manualActionId: "ma-test-001",
    outcome: "Succeeded",
    resultSummary: "Payment retry completed successfully",
    approvalRef: "aprv-test-001",
    correlationId: testCorrelationId,
    causationId: "evt-test-outcome",
    recordedAt: new Date("2026-07-03T10:10:00.000Z"),
    ...overrides,
  };
}

function escalateCommand(overrides: Partial<EscalateCase> = {}): EscalateCase {
  return {
    caseId: "sc-test-001",
    targetQueue: "senior-payment-specialist",
    reason: "Complex payment dispute requiring supervisor approval",
    escalatedBy: "op-test-002",
    escalatedAt: new Date("2026-07-03T10:15:00.000Z"),
    ...overrides,
  };
}

function resolveCommand(overrides: Partial<ResolveCase> = {}): ResolveCase {
  return {
    caseId: "sc-test-001",
    summary: "Payment retry succeeded, funds captured",
    resolutionCode: "PAYMENT_RETRY_SUCCESS",
    resolvedBy: "op-test-002",
    resolvedAt: new Date("2026-07-03T10:20:00.000Z"),
    ...overrides,
  };
}

function closeCommand(overrides: Partial<CloseCase> = {}): CloseCase {
  return {
    caseId: "sc-test-001",
    reason: "RESOLVED",
    closedBy: "op-test-002",
    closedAt: new Date("2026-07-03T10:25:00.000Z"),
    ...overrides,
  };
}

function reopenCommand(overrides: Partial<ReopenCase> = {}): ReopenCase {
  return {
    caseId: "sc-test-001",
    reason: "Customer reports issue persists",
    requesterRef: "tvl-test-001",
    reopenedAt: new Date("2026-07-03T11:00:00.000Z"),
    ...overrides,
  };
}

function appendTimelineCommand(overrides: Partial<AppendTimelineEntry> = {}): AppendTimelineEntry {
  return {
    entryId: "tl-test-001",
    caseId: "sc-test-001",
    eventType: "PAYMENT_RETRY_REQUESTED",
    payload: { manualActionId: "ma-test-001", targetDomain: "payment" },
    visibility: "INTERNAL_ONLY",
    occurredAt: new Date("2026-07-03T10:05:00.000Z"),
    correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    causationId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c221",
    ...overrides,
  };
}

function expectDomainError(fn: () => unknown, code: string): void {
  assert.throws(fn, (error: unknown) => error instanceof DomainError && error.code === code);
}

describe("Customer Service domain foundation", () => {
  // ─── SupportCase lifecycle ─────────────────────────────────────────────────

  describe("SupportCase", () => {
    it("opens a support case from a customer request", () => {
      const { case: supportCase, event } = SupportCase.open(openCommand());

      assert.equal(supportCase.id, "sc-test-001");
      assert.equal(supportCase.status, "Opened");
      assert.equal(supportCase.requesterRef, "tvl-test-001");

      assert.equal(event.type, "SupportCaseOpened");
      assert.equal(event.eventType, "SupportCaseOpened");
      assert.equal(event.schemaVersion, 1);
      assert.equal(event.producer, "customer-service");
      assert.equal(event.caseId, "sc-test-001");
      assert.equal(event.requesterRef, "tvl-test-001");
      assert.equal(event.channel, "APP");
      assert.equal(event.description, "Payment not reflected after successful charge");
      assert.ok(event.eventId.startsWith("evt-"));
      assert.deepEqual(event.businessReferences, {});
      assert.deepEqual(event.boundaryProof, customerServiceBoundaryProof());
    });

    it("opens a support case with business references", () => {
      const { case: supportCase, event } = SupportCase.open(
        openCommand({
          businessReferences: {
            accountRef: "tvl-test-001",
            journeyOrderId: "ord-test-001",
            paymentRef: "pi-test-001",
          },
        }),
      );

      assert.equal(supportCase.id, "sc-test-001");
      assert.equal(event.businessReferences?.journeyOrderId, "ord-test-001");
      assert.equal(event.businessReferences?.paymentRef, "pi-test-001");
    });


    it("starts VIP customers at L2 specialist escalation level", () => {
      const { case: supportCase } = SupportCase.open(openCommand({ customerTier: "GOLD" }));
      const snapshot = supportCase.toSnapshot();

      assert.equal(snapshot.escalationLevel, "L2_SPECIALIST");
      assert.equal(snapshot.customerTier, "GOLD");
    });

    it("selects L1 unresolved tickets for auto-escalation after thirty minutes", () => {
      const { case: supportCase } = SupportCase.open(openCommand({ priority: "NORMAL" }));
      const rule = EscalationPolicy.autoEscalation(new Date("2026-07-03T10:31:00.000Z"), supportCase.toSnapshot());

      assert.deepEqual(rule, { fromLevel: "L1_AGENT", triggerCondition: "L1_UNRESOLVED_30M", toLevel: "L2_SPECIALIST" });
    });

    it("selects simulated L1 resolution or escalation after assignment handling starts", () => {
      const { case: unassigned } = SupportCase.open(openCommand({ caseId: "sc-auto-resolve" }));
      const { case: assignedAutoResolved } = unassigned.assign(assignCommand({ caseId: unassigned.id }));
      const { case: assignableAutoEscalated } = SupportCase.open(openCommand({ caseId: "sc-auto-escalate" }));
      const { case: assignedAutoEscalated } = assignableAutoEscalated.assign(assignCommand({ caseId: assignableAutoEscalated.id }));

      assert.equal(SimulatedResolutionPolicy.decisionAt(new Date("2026-07-03T10:10:00.000Z"), unassigned.toSnapshot()), undefined);
      assert.equal(SimulatedResolutionPolicy.decisionAt(new Date("2026-07-03T10:03:09.000Z"), assignedAutoResolved.toSnapshot()), undefined);
      assert.equal(SimulatedResolutionPolicy.decisionAt(new Date("2026-07-03T10:03:10.000Z"), assignedAutoResolved.toSnapshot())?.action, "RESOLVE");
      assert.equal(SimulatedResolutionPolicy.decisionAt(new Date("2026-07-03T10:03:10.000Z"), assignedAutoEscalated.toSnapshot())?.action, "ESCALATE");
    });

    it("rejects opening with missing required fields", () => {
      expectDomainError(
        () => SupportCase.open(openCommand({ caseId: "" })),
        "MISSING_REQUIRED_FIELD",
      );
      expectDomainError(
        () => SupportCase.open(openCommand({ requesterRef: "" })),
        "MISSING_REQUIRED_FIELD",
      );
      expectDomainError(
        () => SupportCase.open(openCommand({ description: "" })),
        "MISSING_REQUIRED_FIELD",
      );
    });

    it("classifies an opened case and updates SLA targets for the new priority", () => {
      const { case: supportCase } = SupportCase.open(openCommand({ priority: "NORMAL" }));
      const { case: classified, event } = supportCase.classify(classifyCommand({ priority: "URGENT" }));

      assert.equal(classified.status, "Classifying");
      assert.equal(classified.id, "sc-test-001");
      assert.equal(event.type, "SupportCaseClassified");
      assert.equal(event.classification, "PAYMENT_DISPUTE");
      assert.equal(event.priority, "URGENT");
      assert.equal(event.classifiedBy, "op-test-001");
      assert.equal(classified.toSnapshot().slaTracker.priority, "URGENT");
      assert.equal(classified.toSnapshot().slaTracker.responseTargetMinutes, 5);
      assert.equal(classified.toSnapshot().slaTracker.resolutionTargetMinutes, 30);
    });

    it("rejects classifying a case that is not in Opened state", () => {
      const { case: supportCase } = SupportCase.open(openCommand());
      const { case: classified } = supportCase.classify(classifyCommand());

      expectDomainError(
        () => classified.classify(classifyCommand()),
        "CASE_NOT_CLASSIFIABLE",
      );
    });

    it("assigns a classified case", () => {
      const { case: supportCase } = SupportCase.open(openCommand());
      const { case: classified } = supportCase.classify(classifyCommand());
      const { case: assigned, event } = classified.assign(assignCommand());

      assert.equal(assigned.status, "Assigned");
      assert.equal(event.type, "SupportCaseAssigned");
      assert.equal(event.ownerQueue, "payment-specialist");
      assert.equal(event.assignedTo, "op-test-002");
    });

    it("escalates an assigned case", () => {
      const { case: supportCase } = SupportCase.open(openCommand());
      const { case: classified } = supportCase.classify(classifyCommand());
      const { case: assigned } = classified.assign(assignCommand());
      const { case: escalated, event } = assigned.escalate(escalateCommand());

      assert.equal(escalated.status, "Escalated");
      assert.equal(event.type, "SupportCaseEscalated");
      assert.equal(event.targetQueue, "senior-payment-specialist");
      assert.equal(event.reason, "Complex payment dispute requiring supervisor approval");

      const snapshot = escalated.toSnapshot();
      assert.ok(snapshot.escalation);
      assert.equal(snapshot.escalation?.targetQueue, "senior-payment-specialist");
    });

    it("resolves a case and records resolution", () => {
      const { case: supportCase } = SupportCase.open(openCommand());
      const { case: classified } = supportCase.classify(classifyCommand());
      const { case: assigned } = classified.assign(assignCommand());
      const { case: resolved, event } = assigned.resolve(resolveCommand());

      assert.equal(resolved.status, "Resolved");
      assert.equal(event.type, "SupportCaseResolved");
      assert.equal(event.summary, "Payment retry succeeded, funds captured");
      assert.equal(event.resolutionCode, "PAYMENT_RETRY_SUCCESS");

      const snapshot = resolved.toSnapshot();
      assert.ok(snapshot.resolution);
      assert.equal(snapshot.resolution?.summary, "Payment retry succeeded, funds captured");
    });

    it("closes a resolved case", () => {
      const { case: supportCase } = SupportCase.open(openCommand());
      const { case: classified } = supportCase.classify(classifyCommand());
      const { case: assigned } = classified.assign(assignCommand());
      const { case: resolved } = assigned.resolve(resolveCommand());
      const { case: closed, event } = resolved.close(closeCommand());

      assert.equal(closed.status, "Closed");
      assert.equal(event.type, "SupportCaseClosed");
      assert.equal(event.reason, "RESOLVED");
      assert.equal(event.closedBy, "op-test-002");

      const snapshot = closed.toSnapshot();
      assert.equal(snapshot.closeReason, "RESOLVED");
    });

    it("rejects closure without resolution when reason is RESOLVED", () => {
      const { case: supportCase } = SupportCase.open(openCommand());
      expectDomainError(
        () => supportCase.close(closeCommand({ reason: "RESOLVED" })),
        "CLOSURE_WITHOUT_RESOLUTION",
      );
    });

    it("rejects closure without escalation when reason is ESCALATED", () => {
      const { case: supportCase } = SupportCase.open(openCommand());
      expectDomainError(
        () => supportCase.close(closeCommand({ reason: "ESCALATED" })),
        "CLOSURE_WITHOUT_ESCALATION",
      );
    });

    it("allows closure without resolution when reason is not RESOLVED or ESCALATED", () => {
      const { case: supportCase } = SupportCase.open(openCommand());
      const { case: closed } = supportCase.close(closeCommand({ reason: "NO_FURTHER_ACTION" }));
      assert.equal(closed.status, "Closed");
      assert.equal(closed.toSnapshot().closeReason, "NO_FURTHER_ACTION");
    });

    it("reopens a closed case", () => {
      const { case: supportCase } = SupportCase.open(openCommand());
      const { case: closed } = supportCase.close(closeCommand({ reason: "NO_FURTHER_ACTION" }));
      const { case: reopened, event } = closed.reopen(reopenCommand());

      assert.equal(reopened.status, "Reopened");
      assert.equal(event.type, "SupportCaseReopened");
      assert.equal(event.reason, "Customer reports issue persists");

      const snapshot = reopened.toSnapshot();
      assert.equal(snapshot.closeReason, undefined);
      assert.equal(snapshot.closedAt, undefined);
    });

    it("rejects reopening a case that is not Closed or Resolved", () => {
      const { case: supportCase } = SupportCase.open(openCommand());
      expectDomainError(
        () => supportCase.reopen(reopenCommand()),
        "CASE_NOT_REOPENABLE",
      );
    });


    it("records SLA response breach state and creates monitoring event", () => {
      const { case: supportCase } = SupportCase.open(openCommand({ priority: "URGENT" }));
      const breachedAt = new Date("2026-07-03T10:06:00.000Z");

      const updated = supportCase.markSlaBreach("RESPONSE", breachedAt);
      const event = updated.createSlaBreachEvent("RESPONSE", breachedAt);

      assert.equal(updated.toSnapshot().slaBreaches.length, 1);
      assert.equal(updated.toSnapshot().slaTracker.responseBreachedAt?.toISOString(), breachedAt.toISOString());
      assert.equal(event.type, "SlaBreach");
      assert.equal(event.breachType, "RESPONSE");
      assert.equal(event.targetMinutes, 5);
    });

    it("snapshot is deeply immutable", () => {
      const { case: supportCase } = SupportCase.open(openCommand());
      const snapshot = supportCase.toSnapshot();
      assert.throws(() => {
        (snapshot as { status: string }).status = "Closed";
      }, TypeError);
    });
  });

  // ─── EvidenceRef ───────────────────────────────────────────────────────────

  describe("EvidenceRef", () => {
    it("attaches evidence to a support case", () => {
      const { evidence, event } = EvidenceRef.attach(attachEvidenceCommand());

      assert.equal(evidence.id, "evid-test-001");
      assert.equal(evidence.caseId, "sc-test-001");

      assert.equal(event.type, "EvidenceAttached");
      assert.equal(event.evidenceType, "SCREENSHOT");
      assert.equal(event.reference, "https://drive.example.com/evid-001");
      assert.equal(event.summary, "Payment receipt screenshot");
      assert.equal(event.accessLevel, "SENSITIVE");
      assert.equal(event.attachedBy, "op-test-001");

      const snapshot = evidence.toSnapshot();
      assert.equal(snapshot.evidenceId, "evid-test-001");
      assert.equal(snapshot.caseId, "sc-test-001");
      assert.equal(snapshot.accessLevel, "SENSITIVE");
    });

    it("rejects attaching evidence with missing required fields", () => {
      expectDomainError(
        () => EvidenceRef.attach(attachEvidenceCommand({ evidenceId: "" })),
        "MISSING_REQUIRED_FIELD",
      );
      expectDomainError(
        () => EvidenceRef.attach(attachEvidenceCommand({ caseId: "" })),
        "MISSING_REQUIRED_FIELD",
      );
      expectDomainError(
        () => EvidenceRef.attach(attachEvidenceCommand({ reference: "" })),
        "MISSING_REQUIRED_FIELD",
      );
    });

    it("proves evidence attachment does not mutate business aggregates", () => {
      const externalState = {
        journeyOrder: { id: "ord-123", status: "Confirmed" },
        paymentIntent: { id: "pi-123", status: "Captured" },
        entitlement: { id: "ent-123", status: "Issued" },
      };
      const before = structuredClone(externalState);

      EvidenceRef.attach(attachEvidenceCommand());

      assert.deepEqual(externalState, before);
    });
  });

  // ─── ManualActionRequest lifecycle ─────────────────────────────────────────

  describe("ManualActionRequest", () => {
    it("requests a manual action forwarded to Admin & Audit", () => {
      const { action, event } = ManualActionRequest.request(manualActionCommand());

      assert.equal(action.id, "ma-test-001");
      assert.equal(action.caseId, "sc-test-001");
      assert.equal(action.status, "Requested");
      assert.equal(action.targetDomain, "payment");

      assert.equal(event.type, "ManualActionRequested");
      assert.equal(event.targetDomain, "payment");
      assert.equal(event.commandType, "ManualPaymentActionRequested");
      assert.equal(event.operatorRef, "op-test-002");
      assert.equal(event.reason, "Customer requests payment retry");
      assert.equal(event.requiresApproval, true);
      assert.deepEqual(event.evidenceRefs, ["evid-test-001"]);

      const snapshot = action.toSnapshot();
      assert.equal(snapshot.manualActionId, "ma-test-001");
      assert.equal(snapshot.targetDomain, "payment");
      assert.equal(snapshot.requiresApproval, true);
    });

    it("rejects requesting manual action with missing required fields", () => {
      expectDomainError(
        () => ManualActionRequest.request(manualActionCommand({ manualActionId: "" })),
        "MISSING_REQUIRED_FIELD",
      );
      expectDomainError(
        () => ManualActionRequest.request(manualActionCommand({ targetDomain: "" as never })),
        "MISSING_REQUIRED_FIELD",
      );
      expectDomainError(
        () => ManualActionRequest.request(manualActionCommand({ operatorRef: "" })),
        "MISSING_REQUIRED_FIELD",
      );
    });

    it("records a successful outcome for a manual action", () => {
      const { action } = ManualActionRequest.request(manualActionCommand());
      const { action: recorded, event } = action.recordOutcome(recordOutcomeCommand());

      assert.equal(recorded.status, "Succeeded");
      assert.equal(recorded.id, "ma-test-001");

      assert.equal(event.type, "ManualActionResultRecorded");
      assert.equal(event.outcome, "Succeeded");
      assert.equal(event.resultSummary, "Payment retry completed successfully");
      assert.equal(event.approvalRef, "aprv-test-001");

      const snapshot = recorded.toSnapshot();
      assert.equal(snapshot.status, "Succeeded");
      assert.equal(snapshot.resultSummary, "Payment retry completed successfully");
    });

    it("records a failed outcome for a manual action", () => {
      const { action } = ManualActionRequest.request(manualActionCommand());
      const { action: recorded, event } = action.recordOutcome(
        recordOutcomeCommand({ outcome: "Failed", resultSummary: "Payment retry declined by payment provider" }),
      );

      assert.equal(recorded.status, "Failed");
      assert.equal(event.type, "ManualActionResultRecorded");
      assert.equal(event.outcome, "Failed");
    });

    it("records a rejected outcome", () => {
      const { action } = ManualActionRequest.request(manualActionCommand());
      const { event } = action.recordOutcome(
        recordOutcomeCommand({ outcome: "Rejected", resultSummary: "Approval denied: insufficient funds evidence" }),
      );

      assert.equal(event.type, "ManualActionResultRecorded");
      assert.equal(event.outcome, "Rejected");
    });

    it("proves manual action request does not mutate business aggregates", () => {
      const externalState = {
        journeyOrder: { id: "ord-123", status: "Confirmed" },
        paymentIntent: { id: "pi-123", status: "Captured" },
      };
      const before = structuredClone(externalState);

      ManualActionRequest.request(manualActionCommand());

      assert.deepEqual(externalState, before);
    });
  });


  // ─── CompensationOffer ───────────────────────────────────────────────────────

  describe("CompensationOffer", () => {
    function offerCommand(overrides: Partial<OfferCompensation> = {}): OfferCompensation {
      return {
        offerId: "co-test-001",
        ticketId: "sc-test-001",
        type: "CASH",
        amountMinor: 10_000,
        authorizationLevel: "L1_AGENT",
        offeredBy: "op-test-001",
        correlationId: testCorrelationId,
        offeredAt: openedAt,
        ...overrides,
      };
    }

    it("rejects L1 compensation above 50 CNY", () => {
      expectDomainError(
        () => CompensationOffer.offer(offerCommand({ amountMinor: 10_000, authorizationLevel: "L1_AGENT" })),
        "COMPENSATION_AUTHORIZATION_EXCEEDED",
      );
    });

    it("offers, accepts, and issues authorized compensation", () => {
      const { offer, event: offered } = CompensationOffer.offer(offerCommand({ amountMinor: 20_000, authorizationLevel: "L2_SPECIALIST" }));
      const { offer: accepted, event: acceptedEvent } = offer.accept({ offerId: offer.id, acceptedAt: new Date("2026-07-03T10:10:00.000Z"), correlationId: testCorrelationId });
      const { offer: issued, event: issuedEvent } = accepted.issue({ offerId: offer.id, issuedAt: new Date("2026-07-03T10:11:00.000Z"), correlationId: testCorrelationId });

      assert.equal(offered.type, "CompensationOffered");
      assert.equal(acceptedEvent.type, "CompensationAccepted");
      assert.equal(issuedEvent.type, "CompensationIssued");
      assert.equal(issued.toSnapshot().status, "ISSUED");
    });

    it("rejects accept and issue commands for a different compensation offer", () => {
      const { offer } = CompensationOffer.offer(offerCommand({ amountMinor: 20_000, authorizationLevel: "L2_SPECIALIST" }));

      expectDomainError(
        () => offer.accept({ offerId: "co-other", acceptedAt: new Date("2026-07-03T10:10:00.000Z"), correlationId: testCorrelationId }),
        "COMPENSATION_OFFER_MISMATCH",
      );

      const { offer: accepted } = offer.accept({ offerId: offer.id, acceptedAt: new Date("2026-07-03T10:10:00.000Z"), correlationId: testCorrelationId });
      expectDomainError(
        () => accepted.issue({ offerId: "co-other", issuedAt: new Date("2026-07-03T10:11:00.000Z"), correlationId: testCorrelationId }),
        "COMPENSATION_OFFER_MISMATCH",
      );
    });
  });

  // ─── CaseTimeline (append-only) ────────────────────────────────────────────

  describe("CaseTimeline", () => {
    it("creates an empty timeline for a case", () => {
      const timeline = CaseTimeline.create("sc-test-001");
      assert.equal(timeline.caseId, "sc-test-001");
      assert.equal(timeline.entries.length, 0);
    });

    it("appends timeline entries as append-only facts", () => {
      const timeline = CaseTimeline.create("sc-test-001");
      const { timeline: updated, event } = timeline.append(appendTimelineCommand());

      assert.equal(updated.entries.length, 1);
      assert.equal(updated.entries[0].entryId, "tl-test-001");
      assert.equal(updated.entries[0].eventType, "PAYMENT_RETRY_REQUESTED");
      assert.equal(updated.entries[0].visibility, "INTERNAL_ONLY");

      assert.equal(event.type, "CaseTimelineEntryAppended");
      assert.equal(event.entryId, "tl-test-001");
      assert.equal(event.caseId, "sc-test-001");
      assert.equal(event.eventTypeCode, "PAYMENT_RETRY_REQUESTED");
      assert.equal(event.visibility, "INTERNAL_ONLY");
    });

    it("maintains append-only invariant: entries never replace previous entries", () => {
      const timeline = CaseTimeline.create("sc-test-001");
      const { timeline: t1 } = timeline.append(
        appendTimelineCommand({ entryId: "tl-001", eventType: "CASE_OPENED" }),
      );
      const { timeline: t2 } = t1.append(
        appendTimelineCommand({ entryId: "tl-002", eventType: "EVIDENCE_ATTACHED" }),
      );
      const { timeline: t3 } = t2.append(
        appendTimelineCommand({ entryId: "tl-003", eventType: "MANUAL_ACTION_REQUESTED" }),
      );

      assert.equal(t3.entries.length, 3);
      assert.equal(t3.entries[0].entryId, "tl-001");
      assert.equal(t3.entries[0].eventType, "CASE_OPENED");
      assert.equal(t3.entries[1].entryId, "tl-002");
      assert.equal(t3.entries[1].eventType, "EVIDENCE_ATTACHED");
      assert.equal(t3.entries[2].entryId, "tl-003");
      assert.equal(t3.entries[2].eventType, "MANUAL_ACTION_REQUESTED");
    });

    it("rejects appending entry for a different case", () => {
      const timeline = CaseTimeline.create("sc-test-001");
      expectDomainError(
        () => timeline.append(appendTimelineCommand({ caseId: "sc-test-002" })),
        "TIMELINE_CASE_MISMATCH",
      );
    });

    it("supports customer-visible timeline entries", () => {
      const timeline = CaseTimeline.create("sc-test-001");
      const { timeline: updated } = timeline.append(
        appendTimelineCommand({
          entryId: "tl-customer-001",
          eventType: "CASE_RESOLVED",
          visibility: "CUSTOMER_VISIBLE",
        }),
      );

      assert.equal(updated.entries[0].visibility, "CUSTOMER_VISIBLE");
    });


    it("snapshot is deeply immutable", () => {
      const timeline = CaseTimeline.create("sc-test-001");
      const { timeline: updated } = timeline.append(appendTimelineCommand());
      const snapshot = updated.toSnapshot();
      assert.throws(() => {
        (snapshot as unknown as { entries: unknown[] }).entries = [];
      }, TypeError);
    });

    it("proves timeline does not mutate business aggregates", () => {
      const externalState = {
        journeyOrder: { id: "ord-123", status: "Confirmed" },
        capacityHold: { id: "hold-1", status: "Released" },
        paymentIntent: { id: "pi-123", status: "Captured" },
        entitlement: { id: "ent-123", status: "Issued" },
      };
      const before = structuredClone(externalState);

      const timeline = CaseTimeline.create("sc-test-001");
      timeline.append(appendTimelineCommand());

      assert.deepEqual(externalState, before);
    });
  });

  // ─── CustomerService boundary proof ────────────────────────────────────────

  describe("customerServiceBoundaryProof", () => {
    it("returns a frozen proof that customer service does not mutate business aggregates", () => {
      const proof = customerServiceBoundaryProof();
      assert.equal(proof.journeyOrderMutated, false);
      assert.equal(proof.capacityHoldMutated, false);
      assert.equal(proof.paymentIntentMutated, false);
      assert.equal(proof.entitlementMutated, false);
      assert.equal(proof.postSalesCaseMutated, false);
      assert.deepEqual(proof.crossContextWriteTargets, []);
      assert.throws(() => {
        (proof as { journeyOrderMutated: boolean }).journeyOrderMutated = true;
      }, TypeError);
    });
  });

  // ─── Full lifecycle scenario ───────────────────────────────────────────────

  describe("Full support case lifecycle", () => {
    it("walks through a complete case from open to close with evidence, manual action, and timeline", () => {
      // 1. Open case
      const { case: supportCase, event: openedEvent } = SupportCase.open(
        openCommand({
          businessReferences: {
            journeyOrderId: "ord-123",
            paymentRef: "pi-123",
          },
        }),
      );
      assert.equal(supportCase.status, "Opened");
      assert.equal(openedEvent.type, "SupportCaseOpened");
      assert.deepEqual(openedEvent.boundaryProof, customerServiceBoundaryProof());

      // 2. Attach evidence
      const { evidence } = EvidenceRef.attach(attachEvidenceCommand());
      assert.equal(evidence.caseId, supportCase.id);

      // 3. Classify case
      const { case: classified } = supportCase.classify(classifyCommand());
      assert.equal(classified.status, "Classifying");

      // 4. Assign case
      const { case: assigned } = classified.assign(assignCommand());
      assert.equal(assigned.status, "Assigned");

      // 5. Request manual action
      const { action } = ManualActionRequest.request(
        manualActionCommand({ evidenceRefs: [evidence.id] }),
      );
      assert.equal(action.status, "Requested");
      assert.equal(action.targetDomain, "payment");

      // 6. Append timeline entry for the manual action request
      const timeline = CaseTimeline.create(supportCase.id);
      const { timeline: t1 } = timeline.append(
        appendTimelineCommand({
          entryId: "tl-action-001",
          eventType: "MANUAL_ACTION_REQUESTED",
          payload: { manualActionId: action.id },
        }),
      );
      assert.equal(t1.entries.length, 1);

      // 7. Record outcome
      const { action: succeeded } = action.recordOutcome(
        recordOutcomeCommand({ outcome: "Succeeded" }),
      );
      assert.equal(succeeded.status, "Succeeded");

      // 8. Append timeline entry for the outcome
      const { timeline: t2 } = t1.append(
        appendTimelineCommand({
          entryId: "tl-action-result-001",
          eventType: "MANUAL_ACTION_SUCCEEDED",
          payload: { manualActionId: action.id, outcome: "Succeeded" },
        }),
      );
      assert.equal(t2.entries.length, 2);

      // 9. Resolve case
      const { case: resolved } = assigned.resolve(resolveCommand());
      assert.equal(resolved.status, "Resolved");

      // 10. Close case
      const { case: closed } = resolved.close(closeCommand());
      assert.equal(closed.status, "Closed");
      assert.equal(closed.toSnapshot().closeReason, "RESOLVED");

      // Verify no business state was mutated
      assert.deepEqual(openedEvent.boundaryProof, customerServiceBoundaryProof());
    });
  });
});

describe("customer-service persistence duplicate signature", () => {
  it("normalizes undefined and null business references to the same duplicate signature", async () => {
    const { duplicateBusinessRef } = await import("./adapters/storage/customer-service-repository.js");
    const withoutReference = SupportCase.open(openCommand({ businessReferences: undefined })).case.toSnapshot();
    const withNullReference = {
      ...withoutReference,
      businessReferences: { journeyOrderId: null } as never,
    };

    assert.equal(duplicateBusinessRef(withoutReference), "__NO_BUSINESS_REF__");
    assert.equal(duplicateBusinessRef(withNullReference), duplicateBusinessRef(withoutReference));
  });
});
