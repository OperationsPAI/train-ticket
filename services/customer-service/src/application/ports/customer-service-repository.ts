import {
  CaseTimeline,
  ManualActionRequest,
  SupportCase,
  CompensationOffer,
  type CaseTimelineSnapshot,
  type EvidenceRefSnapshot,
  type ManualActionRequestSnapshot,
  type SupportCaseSnapshot,
  type CompensationOfferSnapshot,
} from "../../domain.js";

export interface CustomerServiceRepository {
  findCase(caseId: string): Promise<Readonly<{ aggregate: SupportCase; version: bigint }> | undefined>;
  saveNewCase(snapshot: SupportCaseSnapshot): Promise<{ version: bigint }>;
  saveCase(snapshot: SupportCaseSnapshot, expectedVersion: bigint): Promise<{ version: bigint }>;
  listCases(): Promise<SupportCase[]>;
  listOpenCasesForEvaluation(): Promise<SupportCase[]>;
  findDuplicateOpenCase(snapshot: SupportCaseSnapshot): Promise<SupportCaseSnapshot | undefined>;
  findTimeline(caseId: string): Promise<Readonly<{ aggregate: CaseTimeline; version: bigint }> | undefined>;
  saveNewTimeline(snapshot: CaseTimelineSnapshot): Promise<{ version: bigint }>;
  saveTimeline(snapshot: CaseTimelineSnapshot, expectedVersion: bigint): Promise<{ version: bigint }>;
  evidenceForCase(caseId: string): Promise<EvidenceRefSnapshot[]>;
  saveEvidence(snapshot: EvidenceRefSnapshot): Promise<void>;
  findManualAction(manualActionId: string): Promise<Readonly<{ aggregate: ManualActionRequest; version: bigint }> | undefined>;
  saveNewManualAction(snapshot: ManualActionRequestSnapshot): Promise<{ version: bigint }>;
  saveManualAction(snapshot: ManualActionRequestSnapshot, expectedVersion: bigint): Promise<{ version: bigint }>;
  findCompensationOffer(offerId: string): Promise<Readonly<{ aggregate: CompensationOffer; version: bigint }> | undefined>;
  saveNewCompensationOffer(snapshot: CompensationOfferSnapshot): Promise<{ version: bigint }>;
  saveCompensationOffer(snapshot: CompensationOfferSnapshot, expectedVersion: bigint): Promise<{ version: bigint }>;
}
