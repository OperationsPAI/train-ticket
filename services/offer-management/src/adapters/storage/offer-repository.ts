import { type PoolClient } from "pg";

import { SnapshotRepository, type SnapshotRecord } from "@trainticket/ts-kit";

import { type OfferSnapshot } from "../../domain.js";
import { type OfferRepository } from "../../application/offers.js";

export class PostgresOfferRepository implements OfferRepository {
  constructor(private readonly client: PoolClient) {}

  async save(snapshot: OfferSnapshot, expectedVersion?: bigint): Promise<SnapshotRecord<StoredOfferSnapshot>> {
    return new SnapshotRepository<StoredOfferSnapshot>(this.client, "offer_snapshots").save(snapshot.offerId, serializeOffer(snapshot), expectedVersion);
  }

  async findById(offerId: string): Promise<OfferSnapshot | undefined> {
    const record = await new SnapshotRepository<StoredOfferSnapshot>(this.client, "offer_snapshots").get(offerId);
    return record ? reviveOffer(record.data) : undefined;
  }

  clear(): void {
    // PostgreSQL-backed repositories are scoped to the database lifecycle.
  }
}

type StoredOfferSnapshot = Omit<OfferSnapshot, "validityWindow" | "items" | "priceSnapshot" | "quotedAt" | "expiredAt"> & Readonly<{
  validityWindow: Readonly<{ startsAt: string; expiresAt: string }>;
  items: readonly StoredOfferItem[];
  priceSnapshot: StoredPriceSnapshot;
  quotedAt: string;
  expiredAt?: string;
}>;
type StoredOfferItem = Omit<OfferSnapshot["items"][number], "fareSnapshot" | "availabilitySnapshot"> & Readonly<{
  fareSnapshot: Omit<OfferSnapshot["items"][number]["fareSnapshot"], "capturedAt" | "expiresAt"> & Readonly<{ capturedAt: string; expiresAt: string }>;
  availabilitySnapshot: Omit<OfferSnapshot["items"][number]["availabilitySnapshot"], "capturedAt" | "expiresAt"> & Readonly<{ capturedAt: string; expiresAt: string }>;
}>;
type StoredPriceSnapshot = Omit<OfferSnapshot["priceSnapshot"], "capturedAt" | "expiresAt"> & Readonly<{ capturedAt: string; expiresAt: string }>;

function serializeSnapshot<T>(snapshot: T): T {
  return JSON.parse(JSON.stringify(snapshot)) as T;
}

function serializeOffer(snapshot: OfferSnapshot): StoredOfferSnapshot {
  return serializeSnapshot(snapshot) as unknown as StoredOfferSnapshot;
}

function reviveOffer(snapshot: StoredOfferSnapshot): OfferSnapshot {
  return {
    ...snapshot,
    validityWindow: {
      startsAt: new Date(snapshot.validityWindow.startsAt),
      expiresAt: new Date(snapshot.validityWindow.expiresAt),
    },
    items: snapshot.items.map((item) => ({
      ...item,
      fareSnapshot: {
        ...item.fareSnapshot,
        capturedAt: new Date(item.fareSnapshot.capturedAt),
        expiresAt: new Date(item.fareSnapshot.expiresAt),
      },
      availabilitySnapshot: {
        ...item.availabilitySnapshot,
        capturedAt: new Date(item.availabilitySnapshot.capturedAt),
        expiresAt: new Date(item.availabilitySnapshot.expiresAt),
      },
    })),
    priceSnapshot: {
      ...snapshot.priceSnapshot,
      capturedAt: new Date(snapshot.priceSnapshot.capturedAt),
      expiresAt: new Date(snapshot.priceSnapshot.expiresAt),
    },
    quotedAt: new Date(snapshot.quotedAt),
    expiredAt: snapshot.expiredAt ? new Date(snapshot.expiredAt) : undefined,
  };
}
