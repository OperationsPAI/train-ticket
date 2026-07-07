import { type PoolClient, type QueryResult } from "pg";

import { SnapshotRepository, type SnapshotRecord } from "@trainticket/ts-kit";

import {
  CaseTimeline,
  EvidenceRef,
  ManualActionRequest,
  SupportCase,
  type CaseTimelineSnapshot,
  type EvidenceRefSnapshot,
  type ManualActionRequestSnapshot,
  type SupportCaseSnapshot,
} from "../../domain.js";
import { type CustomerServiceRepository } from "../../application/ports/customer-service-repository.js";

export class PostgresCustomerServiceRepository implements CustomerServiceRepository {
  constructor(private readonly client: PoolClient) {}

  async findCase(caseId: string): Promise<Readonly<{ aggregate: SupportCase; version: bigint }> | undefined> {
    const record = await new SnapshotRepository<StoredSupportCaseSnapshot>(this.client, "support_case_snapshots").get(caseId);
    return record ? { aggregate: SupportCase.fromSnapshot(reviveSupportCase(record.data)), version: record.version } : undefined;
  }

  async saveNewCase(snapshot: SupportCaseSnapshot): Promise<SnapshotRecord<StoredSupportCaseSnapshot>> {
    return new SnapshotRepository<StoredSupportCaseSnapshot>(this.client, "support_case_snapshots").save(snapshot.caseId, serializeSupportCase(snapshot));
  }

  async saveCase(snapshot: SupportCaseSnapshot, expectedVersion: bigint): Promise<SnapshotRecord<StoredSupportCaseSnapshot>> {
    return new SnapshotRepository<StoredSupportCaseSnapshot>(this.client, "support_case_snapshots").save(snapshot.caseId, serializeSupportCase(snapshot), expectedVersion);
  }

  async listCases(): Promise<SupportCase[]> {
    const result = await this.client.query(
      `SELECT data FROM support_case_snapshots ORDER BY updated_at`,
    ) as QueryResult<{ data: StoredSupportCaseSnapshot }>;
    return result.rows.map((row) => SupportCase.fromSnapshot(reviveSupportCase(row.data)));
  }

  async findDuplicateOpenCase(snapshot: SupportCaseSnapshot): Promise<SupportCaseSnapshot | undefined> {
    const result = await this.client.query(
      `SELECT data
       FROM support_case_snapshots
       WHERE data->>'requesterRef' = $1
         AND duplicate_classification(data) = $2
         AND duplicate_business_ref(data) = $3
       ORDER BY updated_at DESC
       LIMIT 1`,
      [snapshot.requesterRef, duplicateClassification(snapshot), duplicateBusinessRef(snapshot)],
    ) as QueryResult<{ data: StoredSupportCaseSnapshot }>;
    const row = result.rows[0];
    return row ? reviveSupportCase(row.data) : undefined;
  }

  async findTimeline(caseId: string): Promise<Readonly<{ aggregate: CaseTimeline; version: bigint }> | undefined> {
    const record = await new SnapshotRepository<StoredCaseTimelineSnapshot>(this.client, "case_timelines").get(caseId);
    return record ? { aggregate: CaseTimeline.fromSnapshot(reviveTimeline(record.data)), version: record.version } : undefined;
  }

  async saveNewTimeline(snapshot: CaseTimelineSnapshot): Promise<SnapshotRecord<StoredCaseTimelineSnapshot>> {
    return new SnapshotRepository<StoredCaseTimelineSnapshot>(this.client, "case_timelines").save(snapshot.caseId, serializeTimeline(snapshot));
  }

  async saveTimeline(snapshot: CaseTimelineSnapshot, expectedVersion: bigint): Promise<SnapshotRecord<StoredCaseTimelineSnapshot>> {
    return new SnapshotRepository<StoredCaseTimelineSnapshot>(this.client, "case_timelines").save(snapshot.caseId, serializeTimeline(snapshot), expectedVersion);
  }

  async evidenceForCase(caseId: string): Promise<EvidenceRefSnapshot[]> {
    const result = await this.client.query(
      `SELECT data FROM case_evidence_refs WHERE case_id = $1 ORDER BY updated_at`,
      [caseId],
    ) as QueryResult<{ data: StoredEvidenceRefSnapshot }>;
    return result.rows.map((row) => reviveEvidence(row.data));
  }

  async saveEvidence(snapshot: EvidenceRefSnapshot): Promise<void> {
    await this.client.query(
      `INSERT INTO case_evidence_refs (evidence_id, case_id, data)
       VALUES ($1, $2, $3)
       ON CONFLICT (evidence_id)
       DO UPDATE SET data = EXCLUDED.data, updated_at = now()`,
      [snapshot.evidenceId, snapshot.caseId, serializeSnapshot(snapshot)],
    );
  }

  async findManualAction(manualActionId: string): Promise<Readonly<{ aggregate: ManualActionRequest; version: bigint }> | undefined> {
    const record = await new SnapshotRepository<StoredManualActionRequestSnapshot>(this.client, "manual_action_snapshots").get(manualActionId);
    return record ? { aggregate: ManualActionRequest.fromSnapshot(reviveManualAction(record.data)), version: record.version } : undefined;
  }

  async saveNewManualAction(snapshot: ManualActionRequestSnapshot): Promise<SnapshotRecord<StoredManualActionRequestSnapshot>> {
    return new SnapshotRepository<StoredManualActionRequestSnapshot>(this.client, "manual_action_snapshots").save(snapshot.manualActionId, serializeManualAction(snapshot));
  }

  async saveManualAction(snapshot: ManualActionRequestSnapshot, expectedVersion: bigint): Promise<SnapshotRecord<StoredManualActionRequestSnapshot>> {
    return new SnapshotRepository<StoredManualActionRequestSnapshot>(this.client, "manual_action_snapshots").save(snapshot.manualActionId, serializeManualAction(snapshot), expectedVersion);
  }
}

type StoredSupportCaseSnapshot = Omit<SupportCaseSnapshot, "openedAt" | "resolvedAt" | "closedAt" | "resolution" | "escalation"> & Readonly<{
  openedAt: string;
  resolvedAt?: string;
  closedAt?: string;
  resolution?: Omit<NonNullable<SupportCaseSnapshot["resolution"]>, "resolvedAt"> & Readonly<{ resolvedAt: string }>;
  escalation?: Omit<NonNullable<SupportCaseSnapshot["escalation"]>, "escalatedAt"> & Readonly<{ escalatedAt: string }>;
}>;
type StoredEvidenceRefSnapshot = Omit<EvidenceRefSnapshot, "attachedAt"> & Readonly<{ attachedAt: string }>;
type StoredManualActionRequestSnapshot = Omit<ManualActionRequestSnapshot, "requestedAt" | "outcomeRecordedAt"> & Readonly<{ requestedAt: string; outcomeRecordedAt?: string }>;
type StoredCaseTimelineSnapshot = Omit<CaseTimelineSnapshot, "entries"> & Readonly<{ entries: readonly StoredTimelineEntrySnapshot[] }>;
type StoredTimelineEntrySnapshot = Omit<CaseTimelineSnapshot["entries"][number], "occurredAt"> & Readonly<{ occurredAt: string }>;

function serializeSnapshot<T>(snapshot: T): T {
  return JSON.parse(JSON.stringify(snapshot)) as T;
}

function serializeSupportCase(snapshot: SupportCaseSnapshot): StoredSupportCaseSnapshot {
  return serializeSnapshot(snapshot) as unknown as StoredSupportCaseSnapshot;
}

function serializeTimeline(snapshot: CaseTimelineSnapshot): StoredCaseTimelineSnapshot {
  return serializeSnapshot(snapshot) as unknown as StoredCaseTimelineSnapshot;
}

function serializeManualAction(snapshot: ManualActionRequestSnapshot): StoredManualActionRequestSnapshot {
  return serializeSnapshot(snapshot) as unknown as StoredManualActionRequestSnapshot;
}

function reviveSupportCase(snapshot: StoredSupportCaseSnapshot): SupportCaseSnapshot {
  return {
    ...snapshot,
    openedAt: new Date(snapshot.openedAt),
    resolvedAt: snapshot.resolvedAt ? new Date(snapshot.resolvedAt) : undefined,
    closedAt: snapshot.closedAt ? new Date(snapshot.closedAt) : undefined,
    resolution: snapshot.resolution ? { ...snapshot.resolution, resolvedAt: new Date(snapshot.resolution.resolvedAt) } : undefined,
    escalation: snapshot.escalation ? { ...snapshot.escalation, escalatedAt: new Date(snapshot.escalation.escalatedAt) } : undefined,
  };
}

function reviveEvidence(snapshot: StoredEvidenceRefSnapshot): EvidenceRefSnapshot {
  return { ...snapshot, attachedAt: new Date(snapshot.attachedAt) };
}

function reviveManualAction(snapshot: StoredManualActionRequestSnapshot): ManualActionRequestSnapshot {
  return {
    ...snapshot,
    requestedAt: new Date(snapshot.requestedAt),
    outcomeRecordedAt: snapshot.outcomeRecordedAt ? new Date(snapshot.outcomeRecordedAt) : undefined,
  };
}

function reviveTimeline(snapshot: StoredCaseTimelineSnapshot): CaseTimelineSnapshot {
  return {
    ...snapshot,
    entries: snapshot.entries.map((entry) => ({ ...entry, occurredAt: new Date(entry.occurredAt) })),
  };
}

export function duplicateClassification(snapshot: SupportCaseSnapshot): string {
  return snapshot.classification?.trim() || "__UNCLASSIFIED__";
}

export function duplicateBusinessRef(snapshot: SupportCaseSnapshot): string {
  const refs = snapshot.businessReferences;
  return stringBusinessRef(refs.journeyOrderId)
    ?? stringBusinessRef(refs.postSalesCaseId)
    ?? stringBusinessRef(refs.paymentRef)
    ?? stringBusinessRef(refs.recoveryCaseId)
    ?? stringBusinessRef(refs.accountRef)
    ?? "__NO_BUSINESS_REF__";
}

function stringBusinessRef(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}
