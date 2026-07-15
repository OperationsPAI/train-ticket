import { createHash } from "node:crypto";

import {
  createEventEnvelope,
  type EventEnvelope,
  type EventPublisher,
} from "@trainticket/ts-kit";

import {
  AncillaryCatalogItem,
  AncillaryOffer,
  AncillaryOrderItem,
  DomainError,
  offerCatalogSnapshotForEvent,
  orderCatalogSnapshotForEvent,
  type AncillaryCatalogItemSnapshot,
  type AncillaryOfferSnapshot,
  type AncillaryOrderItemSnapshot,
  type CatalogInput,
  type CancelSource,
  type FactType,
  type FulfillmentFact,
  type Money,
  type OrderItemStatus,
  type PerformedBy,
  type RefundRecommendation,
} from "./domain.js";
import {
  ancillarySegmentRefs,
  farePricingInputHash,
  type AncillaryPricingGateway,
  type PricingContext,
} from "./pricing.js";

export interface AncillaryRepository {
  saveCatalog(snapshot: AncillaryCatalogItemSnapshot): Promise<void>;
  deleteCatalog(catalogItemId: string): Promise<void>;
  getCatalog(
    catalogItemId: string,
  ): Promise<AncillaryCatalogItemSnapshot | undefined>;
  listCatalog(
    filters: CatalogFilters,
  ): Promise<readonly AncillaryCatalogItemSnapshot[]>;
  saveOffer(snapshot: AncillaryOfferSnapshot): Promise<void>;
  getOffer(
    ancillaryOfferId: string,
  ): Promise<AncillaryOfferSnapshot | undefined>;
  listExpiredQuotedOffers(
    now: Date,
    limit: number,
  ): Promise<readonly AncillaryOfferSnapshot[]>;
  saveOrderItem(snapshot: AncillaryOrderItemSnapshot): Promise<void>;
  getOrderItem(
    ancillaryOrderItemId: string,
  ): Promise<AncillaryOrderItemSnapshot | undefined>;
  listOrderItems(
    filters: OrderItemFilters,
  ): Promise<readonly AncillaryOrderItemSnapshot[]>;
  clear(): void;
}

export type CatalogFilters = Readonly<{
  status?: string;
  serviceType?: string;
  attachmentScope?: string;
  limit: number;
  offset: number;
}>;
export type OrderItemFilters = Readonly<{
  journeyOrderId: string;
  status?: string;
  travelerRef?: string;
  segmentRef?: string;
  limit: number;
  offset: number;
}>;

export class InMemoryAncillaryRepository implements AncillaryRepository {
  private readonly catalogs = new Map<string, AncillaryCatalogItemSnapshot>();
  private readonly offers = new Map<string, AncillaryOfferSnapshot>();
  private readonly orderItems = new Map<string, AncillaryOrderItemSnapshot>();

  async saveCatalog(snapshot: AncillaryCatalogItemSnapshot): Promise<void> {
    this.catalogs.set(snapshot.catalogItemId, cloneDefined(snapshot));
  }
  async deleteCatalog(catalogItemId: string): Promise<void> {
    this.catalogs.delete(catalogItemId);
  }
  async getCatalog(
    catalogItemId: string,
  ): Promise<AncillaryCatalogItemSnapshot | undefined> {
    return clone(this.catalogs.get(catalogItemId));
  }
  async listCatalog(
    filters: CatalogFilters,
  ): Promise<readonly AncillaryCatalogItemSnapshot[]> {
    return [...this.catalogs.values()]
      .filter((item) => !filters.status || item.status === filters.status)
      .filter(
        (item) =>
          !filters.serviceType || item.serviceType === filters.serviceType,
      )
      .filter(
        (item) =>
          !filters.attachmentScope ||
          item.attachmentScope === filters.attachmentScope,
      )
      .slice(filters.offset, filters.offset + filters.limit)
      .map((item) => cloneDefined(item));
  }
  async saveOffer(snapshot: AncillaryOfferSnapshot): Promise<void> {
    this.offers.set(snapshot.ancillaryOfferId, cloneDefined(snapshot));
  }
  async getOffer(
    ancillaryOfferId: string,
  ): Promise<AncillaryOfferSnapshot | undefined> {
    return clone(this.offers.get(ancillaryOfferId));
  }
  async listExpiredQuotedOffers(
    now: Date,
    limit: number,
  ): Promise<readonly AncillaryOfferSnapshot[]> {
    return [...this.offers.values()]
      .filter(
        (offer) =>
          ["QUOTED", "SELECTED"].includes(offer.status) &&
          new Date(offer.expiresAt).getTime() <= now.getTime(),
      )
      .slice(0, limit)
      .map((offer) => cloneDefined(offer));
  }
  async saveOrderItem(snapshot: AncillaryOrderItemSnapshot): Promise<void> {
    this.orderItems.set(snapshot.ancillaryOrderItemId, cloneDefined(snapshot));
  }
  async getOrderItem(
    ancillaryOrderItemId: string,
  ): Promise<AncillaryOrderItemSnapshot | undefined> {
    return clone(this.orderItems.get(ancillaryOrderItemId));
  }
  async listOrderItems(
    filters: OrderItemFilters,
  ): Promise<readonly AncillaryOrderItemSnapshot[]> {
    return [...this.orderItems.values()]
      .filter((item) => item.journeyOrderId === filters.journeyOrderId)
      .filter((item) => !filters.status || item.status === filters.status)
      .filter(
        (item) =>
          !filters.travelerRef || item.travelerRef === filters.travelerRef,
      )
      .filter(
        (item) => !filters.segmentRef || item.segmentRef === filters.segmentRef,
      )
      .slice(filters.offset, filters.offset + filters.limit)
      .map((item) => cloneDefined(item));
  }
  clear(): void {
    this.catalogs.clear();
    this.offers.clear();
    this.orderItems.clear();
  }
}

export type Paged<T> = Readonly<{
  items: readonly T[];
  total: number;
  limit: number;
  offset: number;
}>;

export class AncillaryApplicationService {
  private readonly processedJourneyOrderCancelledEvents = new Set<string>();

  constructor(
    private readonly repository: AncillaryRepository,
    private readonly publisher?: EventPublisher,
    private readonly pricingGateway?: AncillaryPricingGateway,
  ) {}

  async createCatalogItem(
    input: CatalogInput,
  ): Promise<AncillaryCatalogItemSnapshot> {
    const item = AncillaryCatalogItem.create(input);
    const snapshot = item.toSnapshot();
    await this.repository.saveCatalog(snapshot);
    return snapshot;
  }

  async updateCatalogItem(
    catalogItemId: string,
    input: CatalogInput & { expectedVersion: number },
  ): Promise<AncillaryCatalogItemSnapshot> {
    const item = AncillaryCatalogItem.fromSnapshot(
      await this.requireCatalog(catalogItemId),
    );
    item.updateDraft(input, input.expectedVersion);
    const snapshot = item.toSnapshot();
    await this.repository.saveCatalog(snapshot);
    return snapshot;
  }

  async deleteDraftCatalogItem(catalogItemId: string): Promise<void> {
    const snapshot = await this.requireCatalog(catalogItemId);
    if (snapshot.status !== "DRAFT")
      throw new DomainError(
        "CATALOG_NOT_DRAFT",
        "Only DRAFT catalog items may be deleted",
      );
    await this.repository.deleteCatalog(catalogItemId);
  }

  async publishCatalogItem(
    catalogItemId: string,
    input: {
      approvalRef: string;
      effectiveAt?: string;
      expectedVersion: number;
    },
    correlationId: string,
  ): Promise<AncillaryCatalogItemSnapshot> {
    const item = AncillaryCatalogItem.fromSnapshot(
      await this.requireCatalog(catalogItemId),
    );
    item.publish(input.expectedVersion, dateOrNow(input.effectiveAt));
    const snapshot = item.toSnapshot();
    await this.repository.saveCatalog(snapshot);
    await this.publish(
      correlationId,
      "AncillaryCatalogItemPublished",
      catalogItemId,
      snapshot.version,
      {
        ...catalogEventBase(snapshot),
        status: "PUBLISHED",
        publishedAt: snapshot.updatedAt,
        aggregateVersion: snapshot.version,
      },
    );
    return snapshot;
  }

  async suspendCatalogItem(
    catalogItemId: string,
    input: {
      reasonCode: string;
      effectiveAt?: string;
      expectedVersion: number;
    },
    correlationId: string,
  ): Promise<AncillaryCatalogItemSnapshot> {
    const item = AncillaryCatalogItem.fromSnapshot(
      await this.requireCatalog(catalogItemId),
    );
    const previousStatus = item.suspend(
      input.expectedVersion,
      dateOrNow(input.effectiveAt),
    );
    const snapshot = item.toSnapshot();
    await this.repository.saveCatalog(snapshot);
    await this.publish(
      correlationId,
      "AncillaryCatalogItemSuspended",
      catalogItemId,
      snapshot.version,
      {
        catalogItemId,
        version: snapshot.version,
        serviceType: snapshot.serviceType,
        reasonCode: input.reasonCode,
        previousStatus,
        status: "SUSPENDED",
        suspendedAt: snapshot.updatedAt,
        aggregateVersion: snapshot.version,
      },
    );
    return snapshot;
  }

  async supersedeCatalogItem(
    catalogItemId: string,
    input: {
      replacementCatalogItemId: string;
      reasonCode: string;
      effectiveAt?: string;
      expectedVersion: number;
    },
    correlationId: string,
  ): Promise<AncillaryCatalogItemSnapshot> {
    const item = AncillaryCatalogItem.fromSnapshot(
      await this.requireCatalog(catalogItemId),
    );
    const previousStatus = item.supersede(
      input.replacementCatalogItemId,
      input.expectedVersion,
      dateOrNow(input.effectiveAt),
    );
    const snapshot = item.toSnapshot();
    await this.repository.saveCatalog(snapshot);
    await this.publish(
      correlationId,
      "AncillaryCatalogItemSuperseded",
      catalogItemId,
      snapshot.version,
      {
        catalogItemId,
        version: snapshot.version,
        replacementCatalogItemId: input.replacementCatalogItemId,
        serviceType: snapshot.serviceType,
        reasonCode: input.reasonCode,
        previousStatus,
        status: "SUPERSEDED",
        supersededAt: snapshot.updatedAt,
        aggregateVersion: snapshot.version,
      },
    );
    return snapshot;
  }

  async expireCatalogItem(
    catalogItemId: string,
    input: { reasonCode: string; expiredAt: string; expectedVersion: number },
  ): Promise<AncillaryCatalogItemSnapshot> {
    const item = AncillaryCatalogItem.fromSnapshot(
      await this.requireCatalog(catalogItemId),
    );
    item.expire(input.expectedVersion, new Date(input.expiredAt));
    const snapshot = item.toSnapshot();
    await this.repository.saveCatalog(snapshot);
    return snapshot;
  }

  async getCatalogItem(
    catalogItemId: string,
  ): Promise<AncillaryCatalogItemSnapshot> {
    return this.requireCatalog(catalogItemId);
  }
  async listCatalogItems(
    filters: CatalogFilters,
  ): Promise<Paged<AncillaryCatalogItemSnapshot>> {
    const items = await this.repository.listCatalog(filters);
    return {
      items,
      total: items.length,
      limit: filters.limit,
      offset: filters.offset,
    };
  }

  async draftOffer(input: {
    catalogItemId: string;
    journeyOrderId?: string;
    offerRef?: string;
    travelerRef: string;
    segmentRef?: string;
    entitlementRef?: string;
    primaryTicketStatus?: any;
    departureAt: string;
    quantity: number;
    pricing?: PricingContext;
  }): Promise<AncillaryOfferSnapshot> {
    const catalog = await this.requireCatalog(input.catalogItemId);
    const offer = AncillaryOffer.draft(catalog, input);
    const snapshot = offer.toSnapshot();
    const snapshotWithPricingContext = input.pricing
      ? { ...snapshot, futurePricingRefs: { pricingContext: input.pricing } }
      : snapshot;
    await this.repository.saveOffer(snapshotWithPricingContext);
    return snapshotWithPricingContext;
  }

  async quoteOffer(
    ancillaryOfferId: string,
    input: {
      expectedVersion: number;
      validitySeconds?: number;
      pricing?: PricingContext;
      priceQuoteIdempotencyKey?: string;
    },
    correlationId: string,
  ): Promise<AncillaryOfferSnapshot> {
    const offer = AncillaryOffer.fromSnapshot(
      await this.requireOffer(ancillaryOfferId),
    );
    const offerSnapshot = offer.toSnapshot();
    const pricingContext =
      input.pricing ??
      ((offerSnapshot.futurePricingRefs?.pricingContext as
        | PricingContext
        | undefined) ??
        {});
    const pricing = await this.dynamicPricingOrFallback(
      offerSnapshot,
      pricingContext,
      correlationId,
      input.priceQuoteIdempotencyKey,
    );
    offer.quote(input.expectedVersion, input.validitySeconds, new Date(), pricing);
    const snapshot = offer.toSnapshot();
    await this.repository.saveOffer(snapshot);
    if (snapshot.status === "QUOTED") {
      await this.publish(
        correlationId,
        "AncillaryOfferQuoted",
        snapshot.ancillaryOfferId,
        snapshot.offerVersion,
        {
          ...offerEventBase(snapshot),
          catalogSnapshot: offerCatalogSnapshotForEvent(snapshot),
          status: "QUOTED",
          quotedAt: snapshot.updatedAt,
          aggregateVersion: snapshot.offerVersion,
        },
      );
    }
    return snapshot;
  }

  async selectOffer(
    ancillaryOfferId: string,
    input: { journeyOrderId: string; expectedVersion: number },
    correlationId: string,
  ): Promise<AncillaryOrderItemSnapshot> {
    const offer = AncillaryOffer.fromSnapshot(
      await this.requireOffer(ancillaryOfferId),
    );
    const orderItem = offer.select(input.journeyOrderId, input.expectedVersion);
    await this.repository.saveOffer(offer.toSnapshot());
    await this.repository.saveOrderItem(orderItem);
    await this.publish(
      correlationId,
      "AncillaryOrderItemSelected",
      orderItem.ancillaryOrderItemId,
      orderItem.aggregateVersion,
      {
        ...orderEventBase(orderItem),
        status: "SELECTED",
        selectedAt: orderItem.selectedAt,
        aggregateVersion: orderItem.aggregateVersion,
      },
    );
    return orderItem;
  }

  async getOffer(ancillaryOfferId: string): Promise<AncillaryOfferSnapshot> {
    return this.requireOffer(ancillaryOfferId);
  }

  async confirmOrderItem(
    ancillaryOrderItemId: string,
    input: {
      confirmationRef?: string;
      entitlementRef?: string;
      expectedStatus?: OrderItemStatus;
      reasonCode?: string;
    },
    correlationId: string,
  ): Promise<AncillaryOrderItemSnapshot> {
    const item = AncillaryOrderItem.fromSnapshot(
      await this.requireOrderItem(ancillaryOrderItemId),
    );
    const previousStatus = item.confirm(input);
    const snapshot = item.toSnapshot();
    await this.repository.saveOrderItem(snapshot);
    const eventType =
      snapshot.status === "PENDING_CONFIRMATION"
        ? "AncillaryOrderItemPendingConfirmation"
        : "AncillaryOrderItemConfirmed";
    await this.publish(
      correlationId,
      eventType,
      snapshot.ancillaryOrderItemId,
      snapshot.aggregateVersion,
      {
        ...orderEventBase(snapshot),
        confirmationRef: input.confirmationRef,
        entitlementRef: snapshot.entitlementRef,
        reasonCode: input.reasonCode,
        previousStatus,
        status: snapshot.status,
        transitionedAt: snapshot.updatedAt,
        confirmedAt: snapshot.updatedAt,
        aggregateVersion: snapshot.aggregateVersion,
      },
    );
    return snapshot;
  }

  async fulfillmentReady(
    ancillaryOrderItemId: string,
    input: {
      providerRef?: string;
      entitlementRef?: string;
      reasonCode?: string;
      readyAt?: string;
    },
    correlationId: string,
  ): Promise<AncillaryOrderItemSnapshot> {
    const item = AncillaryOrderItem.fromSnapshot(
      await this.requireOrderItem(ancillaryOrderItemId),
    );
    const previousStatus = item.fulfillmentReady(input);
    const snapshot = item.toSnapshot();
    await this.repository.saveOrderItem(snapshot);
    await this.publish(
      correlationId,
      "AncillaryOrderItemFulfillmentReady",
      snapshot.ancillaryOrderItemId,
      snapshot.aggregateVersion,
      {
        ...orderEventBase(snapshot),
        providerRef: input.providerRef,
        entitlementRef: snapshot.entitlementRef,
        previousStatus,
        status: "FULFILLMENT_READY",
        readyAt: snapshot.updatedAt,
        aggregateVersion: snapshot.aggregateVersion,
      },
    );
    return snapshot;
  }

  async cancelOrderItem(
    ancillaryOrderItemId: string,
    input: {
      reasonCode: string;
      source: CancelSource;
      sourceEventId?: string;
      cancelledAt?: string;
      expectedStatus?: OrderItemStatus;
    },
    correlationId: string,
  ): Promise<AncillaryOrderItemSnapshot> {
    const item = AncillaryOrderItem.fromSnapshot(
      await this.requireOrderItem(ancillaryOrderItemId),
    );
    const previousStatus = item.cancel(input);
    const snapshot = item.toSnapshot();
    await this.repository.saveOrderItem(snapshot);
    if (previousStatus)
      await this.publish(
        correlationId,
        "AncillaryOrderItemCancelled",
        snapshot.ancillaryOrderItemId,
        snapshot.aggregateVersion,
        {
          ...cancelEventBase(snapshot),
          reasonCode: input.reasonCode,
          source: input.source,
          sourceEventId: input.sourceEventId,
          previousStatus,
          status: "CANCELLED",
          cancelledAt: snapshot.updatedAt,
          aggregateVersion: snapshot.aggregateVersion,
        },
      );
    return snapshot;
  }

  async suggestRefund(
    ancillaryOrderItemId: string,
    input: {
      reasonCode: string;
      postSalesCaseId?: string;
      waiverRef?: string;
      requestedAt?: string;
    },
    correlationId: string,
  ): Promise<{
    ancillaryOrderItem: AncillaryOrderItemSnapshot;
    recommendation: RefundRecommendation;
    refundableAmount: Money;
    reasonCode: string;
  }> {
    const item = AncillaryOrderItem.fromSnapshot(
      await this.requireOrderItem(ancillaryOrderItemId),
    );
    const result = item.suggestRefund(input);
    const snapshot = item.toSnapshot();
    await this.repository.saveOrderItem(snapshot);
    if (result)
      await this.publish(
        correlationId,
        "AncillaryOrderItemRefundPending",
        snapshot.ancillaryOrderItemId,
        snapshot.aggregateVersion,
        {
          ...cancelEventBase(snapshot),
          recommendation: result.recommendation,
          reasonCode: input.reasonCode,
          postSalesCaseId: input.postSalesCaseId,
          previousStatus: result.previousStatus,
          status: snapshot.status,
          requestedAt: snapshot.updatedAt,
          aggregateVersion: snapshot.aggregateVersion,
        },
      );
    return {
      ancillaryOrderItem: snapshot,
      recommendation: snapshot.refundRecommendation ?? "NO_REFUND",
      refundableAmount: snapshot.refundableAmount,
      reasonCode: input.reasonCode,
    };
  }

  async refunded(
    ancillaryOrderItemId: string,
    input: {
      refundRef: string;
      refundedAmount: Money;
      refundedAt: string;
      reasonCode?: string;
    },
    correlationId: string,
  ): Promise<AncillaryOrderItemSnapshot> {
    const item = AncillaryOrderItem.fromSnapshot(
      await this.requireOrderItem(ancillaryOrderItemId),
    );
    const previousStatus = item.refunded(input);
    const snapshot = item.toSnapshot();
    await this.repository.saveOrderItem(snapshot);
    await this.publish(
      correlationId,
      "AncillaryOrderItemRefunded",
      ancillaryOrderItemId,
      snapshot.aggregateVersion,
      {
        ancillaryOrderItemId,
        journeyOrderId: snapshot.journeyOrderId,
        travelerRef: snapshot.travelerRef,
        catalogItemId: snapshot.catalogItemId,
        serviceType: snapshot.serviceType,
        refundRef: input.refundRef,
        refundedAmount: input.refundedAmount,
        previousStatus,
        status: "REFUNDED",
        refundedAt: input.refundedAt,
        aggregateVersion: snapshot.aggregateVersion,
      },
    );
    return snapshot;
  }

  async recordFulfillmentFact(
    ancillaryOrderItemId: string,
    input: {
      factType: FactType;
      providerRef?: string;
      placeRef?: string;
      occurredAt: string;
      performedBy: PerformedBy;
      idempotencyRef: string;
      compensable?: boolean;
      notes?: string;
    },
    correlationId: string,
  ): Promise<AncillaryOrderItemSnapshot> {
    const item = AncillaryOrderItem.fromSnapshot(
      await this.requireOrderItem(ancillaryOrderItemId),
    );
    const result = item.recordFact({
      ...input,
      compensable: input.compensable ?? false,
    });
    const snapshot = item.toSnapshot();
    await this.repository.saveOrderItem(snapshot);
    if (result.fact) {
      await this.publishFact(correlationId, result.fact.fulfillmentFactId, {
        ancillaryOrderItemId,
        journeyOrderId: snapshot.journeyOrderId,
        travelerRef: snapshot.travelerRef,
        segmentRef: snapshot.segmentRef,
        catalogItemId: snapshot.catalogItemId,
        serviceType: snapshot.serviceType,
        fulfillmentFact: result.fact,
        previousStatus: result.previousStatus,
        status: snapshot.status,
        aggregateVersion: snapshot.aggregateVersion,
      });
      if (result.lifecycle === "FULFILLED")
        await this.publish(
          correlationId,
          "AncillaryOrderItemFulfilled",
          snapshot.ancillaryOrderItemId,
          snapshot.aggregateVersion,
          {
            ...orderEventBase(snapshot),
            fulfillmentFact: result.fact,
            previousStatus: result.previousStatus,
            status: "FULFILLED",
            fulfilledAt: snapshot.updatedAt,
            aggregateVersion: snapshot.aggregateVersion,
          },
        );
      if (result.lifecycle === "FAILED")
        await this.publish(
          correlationId,
          "AncillaryOrderItemFailed",
          snapshot.ancillaryOrderItemId,
          snapshot.aggregateVersion,
          {
            ...orderEventBase(snapshot),
            failureCode: result.fact.factType,
            fulfillmentFact: result.fact,
            compensable: result.fact.compensable,
            previousStatus: result.previousStatus,
            status: "FAILED",
            failedAt: snapshot.updatedAt,
            aggregateVersion: snapshot.aggregateVersion,
          },
        );
    }
    return snapshot;
  }

  async getOrderItem(
    ancillaryOrderItemId: string,
  ): Promise<AncillaryOrderItemSnapshot> {
    return this.requireOrderItem(ancillaryOrderItemId);
  }
  async listOrderItems(
    filters: OrderItemFilters,
  ): Promise<Paged<AncillaryOrderItemSnapshot>> {
    const items = await this.repository.listOrderItems(filters);
    return {
      items,
      total: items.length,
      limit: filters.limit,
      offset: filters.offset,
    };
  }

  async expireOffers(
    now = new Date(),
    correlationId = "corr-018f0000-0000-7000-8000-000000000000",
  ): Promise<number> {
    let count = 0;
    for (const snapshot of await this.repository.listExpiredQuotedOffers(
      now,
      100,
    )) {
      const offer = AncillaryOffer.fromSnapshot(snapshot);
      const result = offer.expire(now);
      if (!result) continue;
      const expired = offer.toSnapshot();
      await this.repository.saveOffer(expired);
      await this.publish(
        correlationId,
        "AncillaryOfferExpired",
        expired.ancillaryOfferId,
        expired.offerVersion,
        {
          ancillaryOfferId: expired.ancillaryOfferId,
          offerVersion: expired.offerVersion,
          travelerRef: expired.travelerRef,
          journeyOrderId: expired.journeyOrderId,
          catalogItemId: expired.catalogItemId,
          previousStatus: result.previousStatus,
          reason: result.reason,
          expiredAt: expired.updatedAt,
          status: "EXPIRED",
          aggregateVersion: expired.offerVersion,
        },
      );
      count += 1;
    }
    return count;
  }

  async handleJourneyOrderCancelled(
    envelope: EventEnvelope,
  ): Promise<"ack" | "retry" | "dlq"> {
    if (envelope.eventType !== "JourneyOrderCancelled") return "ack";
    if (this.processedJourneyOrderCancelledEvents.has(envelope.eventId)) return "ack";
    const orderId =
      typeof envelope.payload.orderId === "string"
        ? envelope.payload.orderId
        : undefined;
    if (!orderId) return "ack";
    this.processedJourneyOrderCancelledEvents.add(envelope.eventId);
    const items = await this.repository.listOrderItems({
      journeyOrderId: orderId,
      limit: 1000,
      offset: 0,
    });
    for (const item of items) {
      await this.cancelOrderItem(
        item.ancillaryOrderItemId,
        {
          reasonCode: "JOURNEY_ORDER_CANCELLED",
          source: "JOURNEY_ORDER_CANCELLED",
          sourceEventId: envelope.eventId,
          cancelledAt: envelope.occurredAt,
        },
        envelope.correlationId,
      );
    }
    return "ack";
  }

  private async dynamicPricingOrFallback(
    snapshot: AncillaryOfferSnapshot,
    context: PricingContext,
    correlationId: string,
    idempotencyKey?: string,
  ): Promise<Parameters<AncillaryOffer["quote"]>[3]> {
    const result = await this.pricingGateway?.quoteAncillaryPrice({
      ancillaryOfferId: snapshot.ancillaryOfferId,
      offerVersion: snapshot.offerVersion,
      catalogItemId: snapshot.catalogItemId,
      travelerRef: snapshot.travelerRef,
      segmentRef: snapshot.segmentRef,
      departureAt: snapshot.eligibility.departureAt,
      quantity: snapshot.quantity,
      catalogUnitPrice: snapshot.catalogSnapshot.unitPrice,
      context,
      correlationId,
      idempotencyKey,
    });
    if (result) return { ...result, source: "FARE_PRICING" };
    const channel = context.channel ?? "DIRECT";
    const segmentRefs = ancillarySegmentRefs(snapshot);
    const inputHash = farePricingInputHash(segmentRefs, channel, [
      snapshot.travelerRef,
    ]);
    return {
      unitPrice: snapshot.catalogSnapshot.unitPrice,
      quoteId: `catalog-${snapshot.ancillaryOfferId}`,
      inputHash,
      fees: [],
      source: "CATALOG_FALLBACK",
    };
  }

  private async requireCatalog(
    catalogItemId: string,
  ): Promise<AncillaryCatalogItemSnapshot> {
    const snapshot = await this.repository.getCatalog(catalogItemId);
    if (!snapshot)
      throw new DomainError("NOT_FOUND", "Catalog item was not found");
    return snapshot;
  }
  private async requireOffer(
    ancillaryOfferId: string,
  ): Promise<AncillaryOfferSnapshot> {
    const snapshot = await this.repository.getOffer(ancillaryOfferId);
    if (!snapshot)
      throw new DomainError("NOT_FOUND", "Ancillary offer was not found");
    return snapshot;
  }
  private async requireOrderItem(
    ancillaryOrderItemId: string,
  ): Promise<AncillaryOrderItemSnapshot> {
    const snapshot = await this.repository.getOrderItem(ancillaryOrderItemId);
    if (!snapshot)
      throw new DomainError("NOT_FOUND", "Ancillary order item was not found");
    return snapshot;
  }
  private async publish(
    correlationId: string,
    eventType: string,
    aggregateId: string,
    aggregateVersion: number,
    payload: Record<string, unknown>,
  ): Promise<void> {
    await this.publisher?.publish(
      createEventEnvelope({
        eventId: deterministicEventId(eventType, aggregateId, aggregateVersion),
        eventType,
        producer: "ancillary-service",
        correlationId,
        payload,
      }),
    );
  }
  private async publishFact(
    correlationId: string,
    fulfillmentFactId: string,
    payload: Record<string, unknown>,
  ): Promise<void> {
    const aggregateId = String(payload.ancillaryOrderItemId);
    await this.publisher?.publish(
      createEventEnvelope({
        eventId: deterministicEventIdFromSeed(
          `ancillary-service:AncillaryFulfillmentFactRecorded:${aggregateId}:${fulfillmentFactId}`,
        ),
        eventType: "AncillaryFulfillmentFactRecorded",
        producer: "ancillary-service",
        correlationId,
        payload,
      }),
    );
  }
}

export function isDomainError(error: unknown): error is DomainError {
  return error instanceof DomainError;
}

function catalogEventBase(
  snapshot: AncillaryCatalogItemSnapshot,
): Record<string, unknown> {
  return {
    catalogItemId: snapshot.catalogItemId,
    version: snapshot.version,
    serviceType: snapshot.serviceType,
    displayName: snapshot.displayName,
    attachmentScope: snapshot.attachmentScope,
    modalities: snapshot.modalities,
    supplierRef: snapshot.supplierRef,
    price: snapshot.price,
    salesWindow: snapshot.salesWindow,
    serviceWindow: snapshot.serviceWindow,
    purchaseCutoffHoursBeforeDeparture:
      snapshot.purchaseCutoffHoursBeforeDeparture,
    eligibilityRuleVersion: snapshot.eligibilityRuleVersion,
    requiresEntitlementRef: snapshot.requiresEntitlementRef,
    requiresSegmentRef: snapshot.requiresSegmentRef,
    fulfillmentMethod: snapshot.fulfillmentMethod,
  };
}
function offerEventBase(
  snapshot: AncillaryOfferSnapshot,
): Record<string, unknown> {
  return {
    ancillaryOfferId: snapshot.ancillaryOfferId,
    offerVersion: snapshot.offerVersion,
    journeyOrderId: snapshot.journeyOrderId,
    offerRef: snapshot.offerRef,
    travelerRef: snapshot.travelerRef,
    segmentRef: snapshot.segmentRef,
    entitlementRef: snapshot.entitlementRef,
    quantity: snapshot.quantity,
    unitPrice: snapshot.unitPrice,
    totalPrice: snapshot.totalPrice,
    assessedFees: snapshot.assessedFees,
    feeAssessment: snapshot.feeAssessment,
    priceQuoteRef: snapshot.priceQuoteRef,
    eligibility: snapshot.eligibility,
    validFrom: snapshot.validFrom,
    expiresAt: snapshot.expiresAt,
  };
}
function orderEventBase(
  snapshot: AncillaryOrderItemSnapshot,
): Record<string, unknown> {
  return {
    ancillaryOrderItemId: snapshot.ancillaryOrderItemId,
    journeyOrderId: snapshot.journeyOrderId,
    travelerRef: snapshot.travelerRef,
    segmentRef: snapshot.segmentRef,
    entitlementRef: snapshot.entitlementRef,
    ancillaryOfferId: snapshot.ancillaryOfferId,
    offerVersion: snapshot.offerVersion,
    catalogSnapshot: orderCatalogSnapshotForEvent(snapshot),
    quantity: snapshot.quantity,
    payableAmount: snapshot.payableAmount,
    refundableAmount: snapshot.refundableAmount,
    assessedFees: snapshot.assessedFees,
    feeAssessment: snapshot.feeAssessment,
    priceQuoteRef: snapshot.priceQuoteRef,
  };
}
function cancelEventBase(
  snapshot: AncillaryOrderItemSnapshot,
): Record<string, unknown> {
  return {
    ancillaryOrderItemId: snapshot.ancillaryOrderItemId,
    journeyOrderId: snapshot.journeyOrderId,
    travelerRef: snapshot.travelerRef,
    segmentRef: snapshot.segmentRef,
    catalogItemId: snapshot.catalogItemId,
    serviceType: snapshot.serviceType,
    payableAmount: snapshot.payableAmount,
    refundableAmount: snapshot.refundableAmount,
    assessedFees: snapshot.assessedFees,
    feeAssessment: snapshot.feeAssessment,
    priceQuoteRef: snapshot.priceQuoteRef,
  };
}
function deterministicEventId(
  eventType: string,
  aggregateId: string,
  aggregateVersion: number,
): string {
  return deterministicEventIdFromSeed(
    `ancillary-service:${eventType}:${aggregateId}:${aggregateVersion}`,
  );
}
function deterministicEventIdFromSeed(seed: string): string {
  const bytes = createHash("sha256").update(seed).digest().subarray(0, 16);
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `evt-${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
function dateOrNow(value?: string): Date {
  return value ? new Date(value) : new Date();
}
function clone<T>(value: T | undefined): T | undefined {
  return value ? cloneDefined(value) : undefined;
}
function cloneDefined<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
