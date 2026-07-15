import { type PoolClient, type QueryResult } from "pg";

import {
  fareQuoteStorageKey,
  type StoredAvailability,
  type StoredAncillaryCatalogItem,
  type StoredAncillaryOffer,
  type StoredConnectionContract,
  type StoredFareQuote,
  type StoredItinerary,
  type StoredMctRule,
  type StoredTransferPlanEvaluation,
  type StoredTraveler,
  type UpstreamStateRepository,
} from "../../application/upstream-state.js";
import { type OfferItem } from "../../domain.js";

export class PostgresUpstreamStateRepository implements UpstreamStateRepository {
  constructor(private readonly client: PoolClient) {}

  async saveItinerary(itinerary: StoredItinerary): Promise<void> {
    await upsertSnapshot(this.client, "offer_upstream_itineraries", itinerary.itineraryRef, serializeItinerary(itinerary));
  }

  async findItinerary(itineraryRef: string): Promise<StoredItinerary | undefined> {
    const row = await findSnapshot<StoredItinerarySnapshot>(this.client, "offer_upstream_itineraries", itineraryRef);
    return row ? reviveItinerary(row) : undefined;
  }

  async saveFareQuote(fareQuote: StoredFareQuote): Promise<void> {
    await this.saveFareQuotes([fareQuote]);
  }

  async saveFareQuotes(fareQuotes: readonly StoredFareQuote[]): Promise<void> {
    await upsertSnapshots(
      this.client,
      "offer_upstream_fare_quotes",
      fareQuotes.map((fareQuote) => ({
        id: fareQuoteStorageKey(fareQuote.inputHash, fareQuote.channelId, fareQuote.travelerRefs),
        data: serializeFareQuote(fareQuote),
      })),
    );
  }

  async findFareQuote(inputHash: string, channelId: string, travelerRefs: readonly string[]): Promise<StoredFareQuote | undefined> {
    const row = await findSnapshot<StoredFareQuoteSnapshot>(this.client, "offer_upstream_fare_quotes", fareQuoteStorageKey(inputHash, channelId, travelerRefs));
    return row ? reviveFareQuote(row) : undefined;
  }

  async saveTraveler(traveler: StoredTraveler): Promise<void> {
    await upsertSnapshot(this.client, "offer_upstream_travelers", traveler.travelerId, serializeTraveler(traveler));
  }

  async findTraveler(travelerId: string): Promise<StoredTraveler | undefined> {
    const row = await findSnapshot<StoredTravelerSnapshot>(this.client, "offer_upstream_travelers", travelerId);
    return row ? reviveTraveler(row) : undefined;
  }

  async removeTravelerEligibility(travelerId: string, eligibilityId: string): Promise<void> {
    const existing = await this.findTraveler(travelerId);
    if (existing?.eligibilityRef?.eligibilityId !== eligibilityId) {
      return;
    }
    await this.saveTraveler({ ...existing, eligibilityRef: undefined });
  }

  async saveTransferPlanEvaluation(evaluation: StoredTransferPlanEvaluation): Promise<void> {
    await upsertSnapshot(this.client, "offer_upstream_transfer_plans", evaluation.itineraryRef, serializeTransferPlanEvaluation(evaluation));
  }

  async findTransferPlanEvaluation(itineraryRef: string): Promise<StoredTransferPlanEvaluation | undefined> {
    const row = await findSnapshot<StoredTransferPlanEvaluationSnapshot>(this.client, "offer_upstream_transfer_plans", itineraryRef);
    return row ? reviveTransferPlanEvaluation(row) : undefined;
  }

  async saveConnectionContract(contract: StoredConnectionContract): Promise<void> {
    await upsertSnapshot(this.client, "offer_upstream_connection_contracts", contract.connectionContractId, serializeConnectionContract(contract));
  }

  async findConnectionContracts(connectionIds: readonly string[]): Promise<readonly StoredConnectionContract[]> {
    if (connectionIds.length === 0) return [];
    const result = await this.client.query(
      "SELECT data FROM offer_upstream_connection_contracts WHERE data->>'connectionId' = ANY($1::text[])",
      [connectionIds],
    ) as QueryResult<{ data: StoredConnectionContractSnapshot }>;
    return result.rows.map((row) => reviveConnectionContract(row.data));
  }

  async saveMctRule(rule: StoredMctRule): Promise<void> {
    await upsertSnapshot(this.client, "offer_upstream_mct_rules", `${rule.mctRuleId}:${rule.version}`, serializeMctRule(rule));
  }

  async findPublishedMctRules(): Promise<readonly StoredMctRule[]> {
    const rows = await findSnapshots<StoredMctRuleSnapshot>(this.client, "offer_upstream_mct_rules");
    return rows.map(reviveMctRule).filter((rule) => rule.status === "PUBLISHED");
  }

  async saveAncillaryCatalogItem(item: StoredAncillaryCatalogItem): Promise<void> {
    await upsertSnapshot(this.client, "offer_upstream_ancillary_catalog", item.catalogItemId, serializeAncillaryCatalogItem(item));
  }

  async findPublishedAncillaryCatalogItems(): Promise<readonly StoredAncillaryCatalogItem[]> {
    const rows = await findSnapshots<StoredAncillaryCatalogItemSnapshot>(this.client, "offer_upstream_ancillary_catalog");
    return rows.map(reviveAncillaryCatalogItem).filter((item) => item.status === "PUBLISHED");
  }

  async saveAncillaryOffer(offer: StoredAncillaryOffer): Promise<void> {
    await upsertSnapshot(this.client, "offer_upstream_ancillary_offers", offer.ancillaryOfferId, serializeAncillaryOffer(offer));
  }

  async findAncillaryOffers(travelerRefs: readonly string[]): Promise<readonly StoredAncillaryOffer[]> {
    if (travelerRefs.length === 0) return [];
    const result = await this.client.query(
      "SELECT data FROM offer_upstream_ancillary_offers WHERE data->>'travelerRef' = ANY($1::text[])",
      [travelerRefs],
    ) as QueryResult<{ data: StoredAncillaryOfferSnapshot }>;
    return result.rows.map((row) => reviveAncillaryOffer(row.data));
  }

  clear(): void {
    // PostgreSQL-backed repositories are scoped to the database lifecycle.
  }
}

type StoredItinerarySnapshot = Omit<StoredItinerary, "modeBySegment" | "availabilityBySegment"> & Readonly<{
  modeBySegment: readonly (readonly [string, OfferItem["mode"]])[];
  availabilityBySegment: readonly (readonly [string, StoredAvailabilitySnapshot])[];
}>;

type StoredAvailabilitySnapshot = Omit<StoredAvailability, "capturedAt" | "expiresAt"> & Readonly<{
  capturedAt: string;
  expiresAt: string;
}>;

type StoredFareQuoteSnapshot = Omit<StoredFareQuote, "validFrom" | "validUntil"> & Readonly<{
  validFrom: string;
  validUntil: string;
}>;

type StoredTravelerSnapshot = StoredTraveler;
type StoredTransferPlanEvaluationSnapshot = Omit<StoredTransferPlanEvaluation, "evaluatedAt"> & Readonly<{ evaluatedAt: string }>;
type StoredConnectionContractSnapshot = Omit<StoredConnectionContract, "proposedAt"> & Readonly<{ proposedAt: string }>;
type StoredMctRuleSnapshot = Omit<StoredMctRule, "validFrom" | "validUntil" | "publishedAt"> & Readonly<{ validFrom: string; validUntil?: string; publishedAt: string }>;
type StoredAncillaryCatalogItemSnapshot = Omit<StoredAncillaryCatalogItem, "updatedAt"> & Readonly<{ updatedAt: string }>;
type StoredAncillaryOfferSnapshot = Omit<StoredAncillaryOffer, "validFrom" | "expiresAt" | "quotedAt" | "expiredAt"> & Readonly<{ validFrom: string; expiresAt: string; quotedAt?: string; expiredAt?: string }>;

async function upsertSnapshot(client: PoolClient, tableName: UpstreamTableName, id: string, data: unknown): Promise<void> {
  await upsertSnapshots(client, tableName, [{ id, data }]);
}

async function upsertSnapshots(client: PoolClient, tableName: UpstreamTableName, snapshots: readonly UpstreamSnapshot[]): Promise<void> {
  const uniqueSnapshots = [...new Map(snapshots.map((snapshot) => [snapshot.id, snapshot])).values()];
  if (uniqueSnapshots.length === 0) {
    return;
  }

  const values: string[] = [];
  const parameters: unknown[] = [];
  for (const [index, snapshot] of uniqueSnapshots.entries()) {
    parameters.push(snapshot.id, snapshot.data);
    const first = index * 2 + 1;
    values.push(`($${first}, 1, $${first + 1})`);
  }

  await client.query(
    `INSERT INTO ${tableName} (id, version, data)
     VALUES ${values.join(", ")}
     ON CONFLICT (id)
     DO UPDATE SET version = ${tableName}.version + 1, data = EXCLUDED.data, updated_at = now()`,
    parameters,
  );
}

async function findSnapshot<T>(client: PoolClient, tableName: UpstreamTableName, id: string): Promise<T | undefined> {
  const result = await client.query(
    `SELECT data FROM ${tableName} WHERE id = $1`,
    [id],
  ) as QueryResult<{ data: T }>;
  return result.rows[0]?.data;
}

async function findSnapshots<T>(client: PoolClient, tableName: UpstreamTableName): Promise<readonly T[]> {
  const result = await client.query(`SELECT data FROM ${tableName}`) as QueryResult<{ data: T }>;
  return result.rows.map((row) => row.data);
}

function serializeItinerary(itinerary: StoredItinerary): StoredItinerarySnapshot {
  return {
    itineraryRef: itinerary.itineraryRef,
    itineraryVersion: itinerary.itineraryVersion,
    segmentRefs: itinerary.segmentRefs,
    modeBySegment: [...itinerary.modeBySegment.entries()],
    availabilityBySegment: [...itinerary.availabilityBySegment.entries()].map(([segmentRef, availability]) => [segmentRef, serializeAvailability(availability)] as const),
    inputHash: itinerary.inputHash,
  };
}

function reviveItinerary(snapshot: StoredItinerarySnapshot): StoredItinerary {
  return {
    ...snapshot,
    modeBySegment: new Map(snapshot.modeBySegment),
    availabilityBySegment: new Map(snapshot.availabilityBySegment.map(([segmentRef, availability]) => [segmentRef, reviveAvailability(availability)] as const)),
  };
}

function serializeAvailability(availability: StoredAvailability): StoredAvailabilitySnapshot {
  return {
    ...availability,
    capturedAt: availability.capturedAt.toISOString(),
    expiresAt: availability.expiresAt.toISOString(),
  };
}

function reviveAvailability(snapshot: StoredAvailabilitySnapshot): StoredAvailability {
  return {
    ...snapshot,
    capturedAt: new Date(snapshot.capturedAt),
    expiresAt: new Date(snapshot.expiresAt),
  };
}

function serializeFareQuote(fareQuote: StoredFareQuote): StoredFareQuoteSnapshot {
  return {
    ...fareQuote,
    validFrom: fareQuote.validFrom.toISOString(),
    validUntil: fareQuote.validUntil.toISOString(),
  };
}

function reviveFareQuote(snapshot: StoredFareQuoteSnapshot): StoredFareQuote {
  return {
    ...snapshot,
    validFrom: new Date(snapshot.validFrom),
    validUntil: new Date(snapshot.validUntil),
  };
}

function serializeTraveler(traveler: StoredTraveler): StoredTravelerSnapshot {
  return JSON.parse(JSON.stringify(traveler)) as StoredTravelerSnapshot;
}

function reviveTraveler(snapshot: StoredTravelerSnapshot): StoredTraveler {
  return snapshot;
}

function serializeTransferPlanEvaluation(evaluation: StoredTransferPlanEvaluation): StoredTransferPlanEvaluationSnapshot {
  return { ...evaluation, evaluatedAt: evaluation.evaluatedAt.toISOString() };
}

function reviveTransferPlanEvaluation(snapshot: StoredTransferPlanEvaluationSnapshot): StoredTransferPlanEvaluation {
  return { ...snapshot, evaluatedAt: new Date(snapshot.evaluatedAt) };
}

function serializeConnectionContract(contract: StoredConnectionContract): StoredConnectionContractSnapshot {
  return { ...contract, proposedAt: contract.proposedAt.toISOString() };
}

function reviveConnectionContract(snapshot: StoredConnectionContractSnapshot): StoredConnectionContract {
  return { ...snapshot, proposedAt: new Date(snapshot.proposedAt) };
}

function serializeMctRule(rule: StoredMctRule): StoredMctRuleSnapshot {
  return { ...rule, validFrom: rule.validFrom.toISOString(), validUntil: rule.validUntil?.toISOString(), publishedAt: rule.publishedAt.toISOString() };
}

function reviveMctRule(snapshot: StoredMctRuleSnapshot): StoredMctRule {
  return { ...snapshot, validFrom: new Date(snapshot.validFrom), validUntil: snapshot.validUntil ? new Date(snapshot.validUntil) : undefined, publishedAt: new Date(snapshot.publishedAt) };
}

function serializeAncillaryCatalogItem(item: StoredAncillaryCatalogItem): StoredAncillaryCatalogItemSnapshot {
  return { ...item, updatedAt: item.updatedAt.toISOString() };
}

function reviveAncillaryCatalogItem(snapshot: StoredAncillaryCatalogItemSnapshot): StoredAncillaryCatalogItem {
  return { ...snapshot, updatedAt: new Date(snapshot.updatedAt) };
}

function serializeAncillaryOffer(offer: StoredAncillaryOffer): StoredAncillaryOfferSnapshot {
  return { ...offer, validFrom: offer.validFrom.toISOString(), expiresAt: offer.expiresAt.toISOString(), quotedAt: offer.quotedAt?.toISOString(), expiredAt: offer.expiredAt?.toISOString() };
}

function reviveAncillaryOffer(snapshot: StoredAncillaryOfferSnapshot): StoredAncillaryOffer {
  return {
    ...snapshot,
    validFrom: new Date(snapshot.validFrom),
    expiresAt: new Date(snapshot.expiresAt),
    quotedAt: snapshot.quotedAt ? new Date(snapshot.quotedAt) : undefined,
    expiredAt: snapshot.expiredAt ? new Date(snapshot.expiredAt) : undefined,
  };
}

type UpstreamSnapshot = Readonly<{ id: string; data: unknown }>;
type UpstreamTableName =
  | "offer_upstream_itineraries"
  | "offer_upstream_fare_quotes"
  | "offer_upstream_travelers"
  | "offer_upstream_transfer_plans"
  | "offer_upstream_connection_contracts"
  | "offer_upstream_mct_rules"
  | "offer_upstream_ancillary_catalog"
  | "offer_upstream_ancillary_offers";
