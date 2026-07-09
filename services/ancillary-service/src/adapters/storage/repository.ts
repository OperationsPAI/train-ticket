import { SnapshotRepository, type Database } from "@trainticket/ts-kit";
import { type AncillaryRepository, type CatalogFilters, type OrderItemFilters } from "../../application.js";
import { type AncillaryCatalogItemSnapshot, type AncillaryOfferSnapshot, type AncillaryOrderItemSnapshot } from "../../domain.js";

export class PostgresAncillaryRepository implements AncillaryRepository {
  constructor(private readonly db: Database) {}
  async saveCatalog(snapshot: AncillaryCatalogItemSnapshot): Promise<void> { await save(new SnapshotRepository<AncillaryCatalogItemSnapshot>(this.db, "ancillary_catalog_items"), snapshot.catalogItemId, snapshot, snapshot.version); }
  async deleteCatalog(catalogItemId: string): Promise<void> { await this.db.query("DELETE FROM ancillary_catalog_items WHERE id = $1", [catalogItemId]); }
  async getCatalog(catalogItemId: string): Promise<AncillaryCatalogItemSnapshot | undefined> { return (await new SnapshotRepository<AncillaryCatalogItemSnapshot>(this.db, "ancillary_catalog_items").get(catalogItemId))?.data; }
  async listCatalog(filters: CatalogFilters): Promise<readonly AncillaryCatalogItemSnapshot[]> { return listJson<AncillaryCatalogItemSnapshot>(this.db, "ancillary_catalog_items", filters, ["status", "serviceType", "attachmentScope"]); }
  async saveOffer(snapshot: AncillaryOfferSnapshot): Promise<void> { await save(new SnapshotRepository<AncillaryOfferSnapshot>(this.db, "ancillary_offers"), snapshot.ancillaryOfferId, snapshot, snapshot.offerVersion); }
  async getOffer(ancillaryOfferId: string): Promise<AncillaryOfferSnapshot | undefined> { return (await new SnapshotRepository<AncillaryOfferSnapshot>(this.db, "ancillary_offers").get(ancillaryOfferId))?.data; }
  async listExpiredQuotedOffers(now: Date, limit: number): Promise<readonly AncillaryOfferSnapshot[]> {
    // Text comparison matches the text-ordered index; RFC3339 UTC compares
    // chronologically as text (REQ-081A ruling).
    const result = await this.db.query("SELECT data FROM ancillary_offers WHERE data->>'status' IN ('QUOTED','SELECTED') AND data->>'expiresAt' <= $1 ORDER BY id LIMIT $2", [now.toISOString(), limit]);
    return result.rows.map((row: { data: AncillaryOfferSnapshot }) => row.data);
  }
  async saveOrderItem(snapshot: AncillaryOrderItemSnapshot): Promise<void> { await save(new SnapshotRepository<AncillaryOrderItemSnapshot>(this.db, "ancillary_order_items"), snapshot.ancillaryOrderItemId, snapshot, snapshot.aggregateVersion); }
  async getOrderItem(ancillaryOrderItemId: string): Promise<AncillaryOrderItemSnapshot | undefined> { return (await new SnapshotRepository<AncillaryOrderItemSnapshot>(this.db, "ancillary_order_items").get(ancillaryOrderItemId))?.data; }
  async listOrderItems(filters: OrderItemFilters): Promise<readonly AncillaryOrderItemSnapshot[]> {
    const clauses = ["data->>'journeyOrderId' = $1"];
    const values: unknown[] = [filters.journeyOrderId];
    for (const key of ["status", "travelerRef", "segmentRef"] as const) if (filters[key]) { values.push(filters[key]); clauses.push(`data->>'${key}' = $${values.length}`); }
    values.push(filters.limit, filters.offset);
    const result = await this.db.query(`SELECT data FROM ancillary_order_items WHERE ${clauses.join(" AND ")} ORDER BY id LIMIT $${values.length - 1} OFFSET $${values.length}`, values);
    return result.rows.map((row: { data: AncillaryOrderItemSnapshot }) => row.data);
  }
  clear(): void { void this.db.query("TRUNCATE ancillary_catalog_items, ancillary_offers, ancillary_order_items"); }
}

export async function save<T>(repo: SnapshotRepository<T>, id: string, snapshot: T, version: number): Promise<void> {
  const existing = await repo.get(id);
  if (!existing) {
    await repo.save(id, snapshot, undefined);
    return;
  }
  if (existing.version >= BigInt(version)) {
    // Replayed/duplicate command that slipped past the idempotency layers:
    // the stored aggregate already advanced at least this far. Same-version
    // replays are harmless no-ops (trip-planning/wallet OCC ruling), not
    // loud OptimisticConcurrencyConflict failures.
    return;
  }
  // Guard against concurrent writers using the STORED version, not an
  // expectation derived from the incoming snapshot.
  await repo.save(id, snapshot, existing.version);
}
async function listJson<T>(db: Database, table: string, filters: Record<string, unknown>, keys: readonly string[]): Promise<readonly T[]> {
  const clauses: string[] = [];
  const values: unknown[] = [];
  for (const key of keys) if (filters[key]) { values.push(filters[key]); clauses.push(`data->>'${key}' = $${values.length}`); }
  values.push(filters.limit, filters.offset);
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  const result = await db.query(`SELECT data FROM ${table} ${where} ORDER BY id LIMIT $${values.length - 1} OFFSET $${values.length}`, values);
  return result.rows.map((row: { data: T }) => row.data);
}
