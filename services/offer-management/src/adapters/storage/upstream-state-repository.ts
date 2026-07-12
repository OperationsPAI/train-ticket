import { type PoolClient, type QueryResult } from "pg";

import {
  fareQuoteStorageKey,
  type StoredAvailability,
  type StoredFareQuote,
  type StoredItinerary,
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

type UpstreamSnapshot = Readonly<{ id: string; data: unknown }>;
type UpstreamTableName = "offer_upstream_itineraries" | "offer_upstream_fare_quotes" | "offer_upstream_travelers";
