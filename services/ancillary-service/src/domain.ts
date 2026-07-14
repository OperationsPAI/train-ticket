import { uuidV7 } from "@trainticket/ts-kit";

import {
  multiplyPriceComponent,
  type PriceComponent,
  type PriceQuoteRef,
  type RuleSnapshot,
} from "./pricing.js";

export class DomainError extends Error {
  constructor(
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "DomainError";
  }
}

export type Money = Readonly<{ currency: string; minorUnits: number }>;
export type ServiceType =
  | "INSURANCE"
  | "MEAL"
  | "BAGGAGE"
  | "CONSIGN"
  | "SEAT_SELECTION"
  | "TRANSFER_PICKUP"
  | "LOUNGE"
  | "FAST_TRACK"
  | "BUNDLE";
export type AttachmentScope =
  "JOURNEY" | "SEGMENT" | "TRAVELER" | "ENTITLEMENT" | "PLACE" | "TRANSFER";
export type CatalogStatus =
  "DRAFT" | "PUBLISHED" | "SUSPENDED" | "SUPERSEDED" | "EXPIRED";
export type OfferStatus =
  "DRAFTING" | "QUOTED" | "SELECTED" | "EXPIRED" | "INELIGIBLE" | "FAILED";
export type OrderItemStatus =
  | "SELECTED"
  | "PENDING_CONFIRMATION"
  | "CONFIRMED"
  | "FULFILLMENT_READY"
  | "FULFILLED"
  | "FAILED"
  | "CANCELLED"
  | "REFUND_PENDING"
  | "REFUNDED";
export type FactType =
  | "MEAL_ISSUED"
  | "BAGGAGE_CHECKED"
  | "CONSIGN_ACCEPTED"
  | "CONSIGN_DELIVERED"
  | "SEAT_ASSIGNED"
  | "LOUNGE_REDEEMED"
  | "FAST_TRACK_USED"
  | "PICKUP_COMPLETED"
  | "INSURANCE_ACTIVATED"
  | "INSURANCE_VOIDED"
  | "SERVICE_VOUCHER_ISSUED"
  | "SERVICE_VOUCHER_REDEEMED"
  | "PROVIDER_FULFILLMENT_FAILED";
export type EligibilityStatus = "ELIGIBLE" | "INELIGIBLE" | "UNKNOWN";
export type PrimaryTicketStatus =
  "ISSUED" | "CONFIRMED" | "PENDING" | "CANCELLED" | "VOIDED" | "UNKNOWN";
export type RefundRecommendation =
  "FULL_REFUND" | "PARTIAL_REFUND" | "NO_REFUND" | "MANUAL_REVIEW";
export type FulfillmentMethod =
  "VOUCHER" | "PROVIDER_CONFIRMATION" | "MANUAL_OPS" | "NONE";
export type PerformedBy = "SYSTEM" | "PROVIDER" | "OPS" | "CUSTOMER_SERVICE";
export type CancelSource =
  "USER" | "OPS" | "SYSTEM" | "SUPPLIER" | "JOURNEY_ORDER_CANCELLED";

export const FACT_TYPES: readonly FactType[] = [
  "MEAL_ISSUED",
  "BAGGAGE_CHECKED",
  "CONSIGN_ACCEPTED",
  "CONSIGN_DELIVERED",
  "SEAT_ASSIGNED",
  "LOUNGE_REDEEMED",
  "FAST_TRACK_USED",
  "PICKUP_COMPLETED",
  "INSURANCE_ACTIVATED",
  "INSURANCE_VOIDED",
  "SERVICE_VOUCHER_ISSUED",
  "SERVICE_VOUCHER_REDEEMED",
  "PROVIDER_FULFILLMENT_FAILED",
];
export const ORDER_TERMINAL_STATUSES: readonly OrderItemStatus[] = [
  "FULFILLED",
  "REFUNDED",
  "CANCELLED",
];

export type TimeWindow = Readonly<{ startAt: string; endAt: string }>;
export type EligibilityReason = Readonly<{ code: string; message: string }>;
export type EligibilityResult = Readonly<{
  status: EligibilityStatus;
  primaryTicketStatus: PrimaryTicketStatus;
  entitlementRef?: string;
  departureAt: string;
  evaluatedAt: string;
  purchaseCutoffHoursBeforeDeparture: number;
  segmentRefRequired: boolean;
  segmentRefPresent: boolean;
  reasons: readonly EligibilityReason[];
}>;

export type FulfillmentFact = Readonly<{
  fulfillmentFactId: string;
  factType: FactType;
  providerRef?: string;
  placeRef?: string;
  occurredAt: string;
  recordedAt: string;
  performedBy: PerformedBy;
  idempotencyRef: string;
  compensable: boolean;
  notes?: string;
}>;

export type FeeAssessment = Readonly<{
  assessmentId: string;
  purpose: "ANCILLARY_PURCHASE";
  assessedAt: string;
  originalQuoteId: string;
  fee: Money;
  currency: string;
  succeeded: boolean;
  failedReason?: string;
}>;

export type CatalogSnapshot = Readonly<{
  catalogItemId: string;
  catalogItemVersion: number;
  serviceType: ServiceType;
  attachmentScope: AttachmentScope;
  displayName: string;
  modalities: readonly string[];
  supplierRef?: string;
  unitPrice: Money;
  salesWindow: TimeWindow;
  serviceWindow?: TimeWindow;
  purchaseCutoffHoursBeforeDeparture: number;
  eligibilityRuleVersion: string;
  requiresEntitlementRef: boolean;
  requiresSegmentRef: boolean;
  fulfillmentMethod: FulfillmentMethod;
}>;

export type AncillaryCatalogItemSnapshot = Readonly<{
  catalogItemId: string;
  version: number;
  serviceType: ServiceType;
  displayName: string;
  attachmentScope: AttachmentScope;
  modalities: readonly string[];
  supplierRef?: string;
  price: Money;
  currency: string;
  salesWindow: TimeWindow;
  serviceWindow?: TimeWindow;
  purchaseCutoffHoursBeforeDeparture: number;
  eligibilityRuleVersion: string;
  requiresEntitlementRef: boolean;
  requiresSegmentRef: boolean;
  fulfillmentMethod: FulfillmentMethod;
  status: CatalogStatus;
  supersededByCatalogItemId?: string;
  createdAt: string;
  updatedAt: string;
}>;

export type AncillaryOfferSnapshot = Readonly<{
  ancillaryOfferId: string;
  offerVersion: number;
  status: OfferStatus;
  journeyOrderId?: string;
  offerRef?: string;
  travelerRef: string;
  segmentRef?: string;
  entitlementRef?: string;
  catalogItemId: string;
  catalogItemVersion: number;
  catalogSnapshot: CatalogSnapshot;
  serviceType: ServiceType;
  attachmentScope: AttachmentScope;
  displayName: string;
  fulfillmentMethod: FulfillmentMethod;
  quantity: number;
  unitPrice: Money;
  totalPrice: Money;
  eligibility: EligibilityResult;
  validFrom: string;
  expiresAt: string;
  ruleSummary?: string;
  priceQuoteRef?: PriceQuoteRef;
  assessedFees?: readonly PriceComponent[];
  feeAssessment?: FeeAssessment;
  futurePricingRefs?: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}>;

export type AncillaryOrderItemSnapshot = Readonly<{
  ancillaryOrderItemId: string;
  journeyOrderId: string;
  travelerRef: string;
  segmentRef?: string;
  entitlementRef?: string;
  ancillaryOfferId: string;
  offerVersion: number;
  catalogItemId: string;
  catalogItemVersion: number;
  catalogSnapshot: CatalogSnapshot;
  serviceType: ServiceType;
  attachmentScope: AttachmentScope;
  displayName: string;
  fulfillmentMethod: FulfillmentMethod;
  quantity: number;
  payableAmount: Money;
  refundableAmount: Money;
  assessedFees: readonly PriceComponent[];
  feeAssessment?: FeeAssessment;
  priceQuoteRef?: PriceQuoteRef;
  refundRecommendation?: RefundRecommendation;
  refundReason?: string;
  status: OrderItemStatus;
  statusReason?: string;
  fulfillmentFacts: readonly FulfillmentFact[];
  selectedAt: string;
  updatedAt: string;
  aggregateVersion: number;
}>;

export type CatalogInput = Omit<
  AncillaryCatalogItemSnapshot,
  | "catalogItemId"
  | "version"
  | "currency"
  | "status"
  | "createdAt"
  | "updatedAt"
  | "supersededByCatalogItemId"
>;

export class AncillaryCatalogItem {
  private constructor(private snapshot: AncillaryCatalogItemSnapshot) {}
  static create(input: CatalogInput, now = new Date()): AncillaryCatalogItem {
    validateCatalog(input);
    const at = iso(now);
    return new AncillaryCatalogItem({
      ...input,
      catalogItemId: `aci-${uuidV7(now)}`,
      version: 1,
      currency: input.price.currency,
      status: "DRAFT",
      createdAt: at,
      updatedAt: at,
    });
  }
  static fromSnapshot(
    snapshot: AncillaryCatalogItemSnapshot,
  ): AncillaryCatalogItem {
    return new AncillaryCatalogItem(snapshot);
  }
  toSnapshot(): AncillaryCatalogItemSnapshot {
    return this.snapshot;
  }
  updateDraft(
    input: CatalogInput,
    expectedVersion: number,
    now = new Date(),
  ): void {
    this.expectVersion(expectedVersion);
    if (this.snapshot.status !== "DRAFT")
      throw new DomainError(
        "CATALOG_NOT_DRAFT",
        "Only DRAFT catalog items may be edited",
      );
    validateCatalog(input);
    this.snapshot = {
      ...this.snapshot,
      ...input,
      currency: input.price.currency,
      version: this.snapshot.version + 1,
      updatedAt: iso(now),
    };
  }
  publish(expectedVersion: number, now = new Date()): CatalogStatus {
    this.expectVersion(expectedVersion);
    return this.transition("PUBLISHED", ["DRAFT", "SUSPENDED"], now);
  }
  suspend(expectedVersion: number, now = new Date()): CatalogStatus {
    this.expectVersion(expectedVersion);
    return this.transition("SUSPENDED", ["DRAFT", "PUBLISHED"], now);
  }
  supersede(
    replacementCatalogItemId: string,
    expectedVersion: number,
    now = new Date(),
  ): CatalogStatus {
    this.expectVersion(expectedVersion);
    if (!["PUBLISHED", "SUSPENDED"].includes(this.snapshot.status))
      throw new DomainError(
        "INVALID_CATALOG_TRANSITION",
        "Only PUBLISHED or SUSPENDED catalog items may be superseded",
      );
    const previous = this.snapshot.status;
    this.snapshot = {
      ...this.snapshot,
      status: "SUPERSEDED",
      supersededByCatalogItemId: replacementCatalogItemId,
      version: this.snapshot.version + 1,
      updatedAt: iso(now),
    };
    return previous;
  }
  expire(expectedVersion: number, now = new Date()): CatalogStatus {
    this.expectVersion(expectedVersion);
    return this.transition("EXPIRED", ["PUBLISHED"], now);
  }
  private transition(
    status: CatalogStatus,
    allowed: readonly CatalogStatus[],
    now: Date,
  ): CatalogStatus {
    if (!allowed.includes(this.snapshot.status))
      throw new DomainError(
        "INVALID_CATALOG_TRANSITION",
        `Cannot move catalog item from ${this.snapshot.status} to ${status}`,
      );
    const previous = this.snapshot.status;
    this.snapshot = {
      ...this.snapshot,
      status,
      version: this.snapshot.version + 1,
      updatedAt: iso(now),
    };
    return previous;
  }
  private expectVersion(expectedVersion: number): void {
    if (this.snapshot.version !== expectedVersion)
      throw new DomainError(
        "PRECONDITION_FAILED",
        "Catalog version precondition failed",
      );
  }
}

export class AncillaryOffer {
  private constructor(private snapshot: AncillaryOfferSnapshot) {}
  static draft(
    catalog: AncillaryCatalogItemSnapshot,
    input: {
      journeyOrderId?: string;
      offerRef?: string;
      travelerRef: string;
      segmentRef?: string;
      entitlementRef?: string;
      primaryTicketStatus?: PrimaryTicketStatus;
      departureAt: string;
      quantity: number;
    },
    now = new Date(),
  ): AncillaryOffer {
    if (catalog.status !== "PUBLISHED")
      throw new DomainError(
        "CATALOG_NOT_PUBLISHED",
        "Catalog item must be PUBLISHED to draft an offer",
      );
    if (input.quantity < 1 || !Number.isInteger(input.quantity))
      throw new DomainError(
        "INVALID_QUANTITY",
        "Quantity must be a positive integer",
      );
    const eligibility = evaluateEligibility(catalog, input, now);
    const status: OfferStatus =
      eligibility.status === "ELIGIBLE" ? "DRAFTING" : "INELIGIBLE";
    const at = iso(now);
    const catalogSnapshot = catalogSnapshotForEvent(catalog);
    return new AncillaryOffer({
      ancillaryOfferId: `aof-${uuidV7(now)}`,
      offerVersion: 1,
      status,
      journeyOrderId: input.journeyOrderId,
      offerRef: input.offerRef,
      travelerRef: input.travelerRef,
      segmentRef: input.segmentRef,
      entitlementRef: eligibility.entitlementRef,
      catalogItemId: catalog.catalogItemId,
      catalogItemVersion: catalog.version,
      catalogSnapshot,
      serviceType: catalog.serviceType,
      attachmentScope: catalog.attachmentScope,
      displayName: catalog.displayName,
      fulfillmentMethod: catalog.fulfillmentMethod,
      quantity: input.quantity,
      unitPrice: catalog.price,
      totalPrice: multiplyMoney(catalog.price, input.quantity),
      eligibility,
      validFrom: at,
      expiresAt: iso(new Date(now.getTime() + 15 * 60_000)),
      ruleSummary:
        eligibility.reasons.map((reason) => reason.code).join(",") ||
        "MINIMUM_ELIGIBILITY_PASSED",
      createdAt: at,
      updatedAt: at,
    });
  }
  static fromSnapshot(snapshot: AncillaryOfferSnapshot): AncillaryOffer {
    return new AncillaryOffer(withOfferCatalogSnapshot(snapshot));
  }
  toSnapshot(): AncillaryOfferSnapshot {
    return this.snapshot;
  }
  quote(
    expectedVersion: number,
    validitySeconds = 900,
    now = new Date(),
    pricing?: {
      unitPrice: Money;
      quoteId: string;
      inputHash: string;
      validFrom?: string;
      validUntil?: string;
      ruleSnapshot?: RuleSnapshot;
      fees?: readonly PriceComponent[];
      source: PriceQuoteRef["source"];
    },
  ): void {
    this.expectVersion(expectedVersion);
    if (
      this.snapshot.status === "INELIGIBLE" ||
      this.snapshot.eligibility.status !== "ELIGIBLE"
    )
      return;
    if (this.snapshot.status !== "DRAFTING")
      throw new DomainError(
        "INVALID_OFFER_TRANSITION",
        "Only DRAFTING offers may be quoted",
      );
    const priced = pricing ?? {
      unitPrice: this.snapshot.catalogSnapshot.unitPrice,
      quoteId: `catalog-${this.snapshot.ancillaryOfferId}`,
      inputHash: "",
      source: "CATALOG_FALLBACK" as const,
      fees: [],
    };
    const unitPrice = priced.unitPrice;
    const feeAssessment = assessFees(
      priced.quoteId,
      priced.fees ?? [],
      unitPrice.currency,
      now,
    );
    this.snapshot = {
      ...this.snapshot,
      status: "QUOTED",
      offerVersion: this.snapshot.offerVersion + 1,
      unitPrice,
      totalPrice: multiplyMoney(unitPrice, this.snapshot.quantity),
      priceQuoteRef: {
        quoteId: priced.quoteId,
        inputHash: priced.inputHash,
        ruleSnapshot: priced.ruleSnapshot,
        source: priced.source,
      },
      assessedFees: priced.fees ?? [],
      feeAssessment,
      futurePricingRefs: undefined,
      validFrom: priced.validFrom ?? iso(now),
      expiresAt:
        priced.validUntil ?? iso(new Date(now.getTime() + validitySeconds * 1000)),
      updatedAt: iso(now),
    };
  }
  select(
    journeyOrderId: string,
    expectedVersion: number,
    now = new Date(),
  ): AncillaryOrderItemSnapshot {
    this.expectVersion(expectedVersion);
    if (this.snapshot.status !== "QUOTED")
      throw new DomainError(
        "INVALID_OFFER_TRANSITION",
        "Only QUOTED offers may be selected",
      );
    if (new Date(this.snapshot.expiresAt).getTime() <= now.getTime())
      throw new DomainError("OFFER_EXPIRED", "Offer has expired");
    this.snapshot = {
      ...this.snapshot,
      status: "SELECTED",
      journeyOrderId,
      offerVersion: this.snapshot.offerVersion + 1,
      updatedAt: iso(now),
    };
    return AncillaryOrderItem.fromOffer(
      this.snapshot,
      journeyOrderId,
      now,
    ).toSnapshot();
  }
  expire(
    now = new Date(),
    reason:
      | "VALIDITY_WINDOW_ELAPSED"
      | "EXPLICIT_EXPIRE_COMMAND" = "VALIDITY_WINDOW_ELAPSED",
  ): { previousStatus: OfferStatus; reason: string } | undefined {
    if (!["QUOTED", "SELECTED"].includes(this.snapshot.status))
      return undefined;
    if (
      reason === "VALIDITY_WINDOW_ELAPSED" &&
      new Date(this.snapshot.expiresAt).getTime() > now.getTime()
    )
      return undefined;
    const previousStatus = this.snapshot.status;
    this.snapshot = {
      ...this.snapshot,
      status: "EXPIRED",
      offerVersion: this.snapshot.offerVersion + 1,
      updatedAt: iso(now),
    };
    return { previousStatus, reason };
  }
  private expectVersion(expectedVersion: number): void {
    if (this.snapshot.offerVersion !== expectedVersion)
      throw new DomainError(
        "PRECONDITION_FAILED",
        "Offer version precondition failed",
      );
  }
}

export class AncillaryOrderItem {
  private constructor(private snapshot: AncillaryOrderItemSnapshot) {}
  static fromOffer(
    offer: AncillaryOfferSnapshot,
    journeyOrderId: string,
    now = new Date(),
  ): AncillaryOrderItem {
    const at = iso(now);
    return new AncillaryOrderItem({
      ancillaryOrderItemId: `aoi-${uuidV7(now)}`,
      journeyOrderId,
      travelerRef: offer.travelerRef,
      segmentRef: offer.segmentRef,
      entitlementRef: offer.entitlementRef,
      ancillaryOfferId: offer.ancillaryOfferId,
      offerVersion: offer.offerVersion,
      catalogItemId: offer.catalogItemId,
      catalogItemVersion: offer.catalogItemVersion,
      catalogSnapshot: offer.catalogSnapshot,
      serviceType: offer.serviceType,
      attachmentScope: offer.attachmentScope,
      displayName: offer.displayName,
      fulfillmentMethod: offer.fulfillmentMethod,
      quantity: offer.quantity,
      payableAmount: offer.totalPrice,
      refundableAmount: zeroMoney(offer.totalPrice.currency),
      assessedFees: offer.assessedFees
        ? offer.assessedFees.map((fee) =>
            multiplyPriceComponent(fee, offer.quantity),
          )
        : [],
      feeAssessment: offer.feeAssessment
        ? multiplyFeeAssessment(offer.feeAssessment, offer.quantity)
        : undefined,
      priceQuoteRef: offer.priceQuoteRef,
      status: "SELECTED",
      fulfillmentFacts: [],
      selectedAt: at,
      updatedAt: at,
      aggregateVersion: 1,
    });
  }
  static fromSnapshot(
    snapshot: AncillaryOrderItemSnapshot,
  ): AncillaryOrderItem {
    return new AncillaryOrderItem(withOrderCatalogSnapshot(snapshot));
  }
  toSnapshot(): AncillaryOrderItemSnapshot {
    return this.snapshot;
  }
  isTerminal(): boolean {
    return ORDER_TERMINAL_STATUSES.includes(this.snapshot.status);
  }
  confirm(
    input: {
      confirmationRef?: string;
      entitlementRef?: string;
      expectedStatus?: OrderItemStatus;
      reasonCode?: string;
    },
    now = new Date(),
  ): OrderItemStatus {
    this.expectStatus(input.expectedStatus);
    const previous = this.snapshot.status;
    if (previous === "SELECTED") {
      this.snapshot = {
        ...this.snapshot,
        status: "PENDING_CONFIRMATION",
        statusReason: input.reasonCode,
        updatedAt: iso(now),
        aggregateVersion: this.snapshot.aggregateVersion + 1,
      };
      return previous;
    }
    if (previous !== "PENDING_CONFIRMATION")
      throw new DomainError(
        "INVALID_ORDER_ITEM_TRANSITION",
        "Order item cannot be confirmed from current status",
      );
    this.snapshot = {
      ...this.snapshot,
      status: "CONFIRMED",
      entitlementRef: input.entitlementRef ?? this.snapshot.entitlementRef,
      statusReason: input.reasonCode ?? input.confirmationRef,
      updatedAt: iso(now),
      aggregateVersion: this.snapshot.aggregateVersion + 1,
    };
    return previous;
  }
  fulfillmentReady(
    input: {
      providerRef?: string;
      entitlementRef?: string;
      reasonCode?: string;
      readyAt?: string;
    },
    now = new Date(),
  ): OrderItemStatus {
    if (this.snapshot.status !== "CONFIRMED")
      throw new DomainError(
        "INVALID_ORDER_ITEM_TRANSITION",
        "Only CONFIRMED items may become FULFILLMENT_READY",
      );
    const previous = this.snapshot.status;
    this.snapshot = {
      ...this.snapshot,
      status: "FULFILLMENT_READY",
      entitlementRef: input.entitlementRef ?? this.snapshot.entitlementRef,
      statusReason: input.reasonCode ?? input.providerRef,
      updatedAt: input.readyAt ?? iso(now),
      aggregateVersion: this.snapshot.aggregateVersion + 1,
    };
    return previous;
  }
  cancel(
    input: {
      reasonCode: string;
      source: CancelSource;
      sourceEventId?: string;
      cancelledAt?: string;
      expectedStatus?: OrderItemStatus;
    },
    now = new Date(),
  ): OrderItemStatus | undefined {
    this.expectStatus(input.expectedStatus);
    if (this.isTerminal()) return undefined;
    const previous = this.snapshot.status;
    this.snapshot = {
      ...this.snapshot,
      status: "CANCELLED",
      statusReason: input.reasonCode,
      updatedAt: input.cancelledAt ?? iso(now),
      aggregateVersion: this.snapshot.aggregateVersion + 1,
    };
    return previous;
  }
  suggestRefund(
    input: { reasonCode: string; requestedAt?: string },
    now = new Date(),
  ):
    | { previousStatus: OrderItemStatus; recommendation: RefundRecommendation }
    | undefined {
    if (
      ["REFUNDED", "FULFILLED", "REFUND_PENDING"].includes(this.snapshot.status)
    )
      return undefined;
    const previousStatus = this.snapshot.status;
    const recommendation: RefundRecommendation =
      this.snapshot.status === "CANCELLED" || this.snapshot.status === "FAILED"
        ? "FULL_REFUND"
        : "MANUAL_REVIEW";
    this.snapshot = {
      ...this.snapshot,
      status: "REFUND_PENDING",
      refundRecommendation: recommendation,
      refundReason: input.reasonCode,
      refundableAmount:
        recommendation === "FULL_REFUND"
          ? this.snapshot.payableAmount
          : this.snapshot.refundableAmount,
      updatedAt: input.requestedAt ?? iso(now),
      aggregateVersion: this.snapshot.aggregateVersion + 1,
    };
    return { previousStatus, recommendation };
  }
  refunded(input: {
    refundRef: string;
    refundedAmount: Money;
    refundedAt: string;
    reasonCode?: string;
  }): OrderItemStatus {
    if (this.snapshot.status !== "REFUND_PENDING")
      throw new DomainError(
        "INVALID_ORDER_ITEM_TRANSITION",
        "Only REFUND_PENDING items may be marked REFUNDED",
      );
    const previous = this.snapshot.status;
    this.snapshot = {
      ...this.snapshot,
      status: "REFUNDED",
      refundableAmount: input.refundedAmount,
      statusReason: input.reasonCode ?? input.refundRef,
      updatedAt: input.refundedAt,
      aggregateVersion: this.snapshot.aggregateVersion + 1,
    };
    return previous;
  }
  recordFact(
    input: Omit<FulfillmentFact, "fulfillmentFactId" | "recordedAt">,
    now = new Date(),
  ): {
    fact?: FulfillmentFact;
    previousStatus: OrderItemStatus;
    lifecycle?: OrderItemStatus;
  } {
    if (!FACT_TYPES.includes(input.factType))
      throw new DomainError(
        "VALIDATION_FAILED",
        "factType is not supported by the ancillary contract",
      );
    const existing = this.snapshot.fulfillmentFacts.find(
      (fact) =>
        fact.factType === input.factType &&
        fact.idempotencyRef === input.idempotencyRef,
    );
    if (existing) return { previousStatus: this.snapshot.status };
    if (["CANCELLED", "REFUNDED", "FULFILLED"].includes(this.snapshot.status))
      throw new DomainError(
        "INVALID_ORDER_ITEM_TRANSITION",
        "Cannot record fulfillment facts for terminal order item",
      );
    const previousStatus = this.snapshot.status;
    const fact: FulfillmentFact = {
      ...input,
      fulfillmentFactId: `aff-${uuidV7(now)}`,
      recordedAt: iso(now),
    };
    const lifecycle =
      fact.factType === "PROVIDER_FULFILLMENT_FAILED"
        ? "FAILED"
        : completionStatusForFact(fact.factType);
    this.snapshot = {
      ...this.snapshot,
      fulfillmentFacts: [...this.snapshot.fulfillmentFacts, fact],
      status: lifecycle ?? this.snapshot.status,
      updatedAt: fact.recordedAt,
      aggregateVersion: this.snapshot.aggregateVersion + 1,
    };
    return { fact, previousStatus, lifecycle };
  }
  private expectStatus(expectedStatus?: OrderItemStatus): void {
    if (expectedStatus && this.snapshot.status !== expectedStatus)
      throw new DomainError(
        "PRECONDITION_FAILED",
        "Order item status precondition failed",
      );
  }
}

export function evaluateEligibility(
  catalog: AncillaryCatalogItemSnapshot,
  input: {
    entitlementRef?: string;
    primaryTicketStatus?: PrimaryTicketStatus;
    departureAt: string;
    segmentRef?: string;
  },
  now = new Date(),
): EligibilityResult {
  const reasons: EligibilityReason[] = [];
  const departureTime = new Date(input.departureAt).getTime();
  const status =
    input.primaryTicketStatus ?? (input.entitlementRef ? "ISSUED" : "UNKNOWN");
  if (
    catalog.requiresEntitlementRef &&
    !input.entitlementRef &&
    !["ISSUED", "CONFIRMED"].includes(status)
  )
    reasons.push({
      code: "PRIMARY_TICKET_NOT_CONFIRMED",
      message: "Primary ticket or entitlement proof is required",
    });
  const cutoffAt =
    departureTime - catalog.purchaseCutoffHoursBeforeDeparture * 3_600_000;
  if (!Number.isFinite(departureTime) || now.getTime() > cutoffAt)
    reasons.push({
      code: "PURCHASE_CUTOFF_PASSED",
      message: "Purchase cutoff is outside the allowed window before departure",
    });
  const segmentRequired =
    catalog.requiresSegmentRef || catalog.attachmentScope === "SEGMENT";
  if (segmentRequired && !input.segmentRef)
    reasons.push({
      code: "SEGMENT_REF_REQUIRED",
      message: "Segment reference is required for this ancillary",
    });
  return {
    status: reasons.length === 0 ? "ELIGIBLE" : "INELIGIBLE",
    primaryTicketStatus: status,
    entitlementRef: input.entitlementRef,
    departureAt: new Date(input.departureAt).toISOString(),
    evaluatedAt: iso(now),
    purchaseCutoffHoursBeforeDeparture:
      catalog.purchaseCutoffHoursBeforeDeparture,
    segmentRefRequired: segmentRequired,
    segmentRefPresent: !!input.segmentRef,
    reasons,
  };
}

export function multiplyMoney(money: Money, quantity: number): Money {
  return { currency: money.currency, minorUnits: money.minorUnits * quantity };
}
export function zeroMoney(currency: string): Money {
  return { currency, minorUnits: 0 };
}
export function addMoney(first: Money, second: Money): Money {
  if (first.currency !== second.currency)
    throw new DomainError(
      "CURRENCY_MISMATCH",
      "Money values must share currency",
    );
  return {
    currency: first.currency,
    minorUnits: first.minorUnits + second.minorUnits,
  };
}
export function assessFees(
  originalQuoteId: string,
  fees: readonly PriceComponent[],
  currency: string,
  now = new Date(),
): FeeAssessment {
  const totalFee = fees.reduce(
    (total, fee) => addMoney(total, fee.amount),
    zeroMoney(currency),
  );
  return {
    assessmentId: `fas-${uuidV7(now)}`,
    purpose: "ANCILLARY_PURCHASE",
    assessedAt: iso(now),
    originalQuoteId,
    fee: totalFee,
    currency: totalFee.currency,
    succeeded: true,
  };
}
export function multiplyFeeAssessment(
  assessment: FeeAssessment,
  quantity: number,
): FeeAssessment {
  const fee = multiplyMoney(assessment.fee, quantity);
  return { ...assessment, fee, currency: fee.currency };
}
export function iso(date: Date): string {
  return date.toISOString();
}
export function catalogSnapshotForEvent(
  catalog: AncillaryCatalogItemSnapshot,
): CatalogSnapshot {
  return {
    catalogItemId: catalog.catalogItemId,
    catalogItemVersion: catalog.version,
    serviceType: catalog.serviceType,
    attachmentScope: catalog.attachmentScope,
    displayName: catalog.displayName,
    modalities: catalog.modalities,
    supplierRef: catalog.supplierRef,
    unitPrice: catalog.price,
    salesWindow: catalog.salesWindow,
    serviceWindow: catalog.serviceWindow,
    purchaseCutoffHoursBeforeDeparture:
      catalog.purchaseCutoffHoursBeforeDeparture,
    eligibilityRuleVersion: catalog.eligibilityRuleVersion,
    requiresEntitlementRef: catalog.requiresEntitlementRef,
    requiresSegmentRef: catalog.requiresSegmentRef,
    fulfillmentMethod: catalog.fulfillmentMethod,
  };
}
export function offerCatalogSnapshotForEvent(
  offer: AncillaryOfferSnapshot,
): CatalogSnapshot {
  return offer.catalogSnapshot;
}
export function orderCatalogSnapshotForEvent(
  orderItem: AncillaryOrderItemSnapshot,
): CatalogSnapshot {
  return orderItem.catalogSnapshot;
}

function validateCatalog(input: CatalogInput): void {
  if (!input.displayName?.trim())
    throw new DomainError("VALIDATION_FAILED", "displayName is required");
  if (
    input.price.minorUnits < 0 ||
    !Number.isInteger(input.price.minorUnits) ||
    !input.price.currency
  )
    throw new DomainError(
      "VALIDATION_FAILED",
      "price must use Money {currency, minorUnits}",
    );
  if (input.requiresSegmentRef !== true && input.attachmentScope === "SEGMENT")
    throw new DomainError(
      "SEGMENT_RULE_REQUIRED",
      "SEGMENT attachment scope requires requiresSegmentRef=true",
    );
  if (
    new Date(input.salesWindow.startAt).getTime() >=
    new Date(input.salesWindow.endAt).getTime()
  )
    throw new DomainError(
      "VALIDATION_FAILED",
      "salesWindow must have startAt before endAt",
    );
}

function completionStatusForFact(
  factType: FactType,
): OrderItemStatus | undefined {
  return factType === "SERVICE_VOUCHER_ISSUED"
    ? "FULFILLMENT_READY"
    : "FULFILLED";
}
function withOfferCatalogSnapshot(
  snapshot: AncillaryOfferSnapshot,
): AncillaryOfferSnapshot {
  return snapshot.catalogSnapshot
    ? snapshot
    : {
        ...snapshot,
        catalogSnapshot: {
          catalogItemId: snapshot.catalogItemId,
          catalogItemVersion: snapshot.catalogItemVersion,
          serviceType: snapshot.serviceType,
          attachmentScope: snapshot.attachmentScope,
          displayName: snapshot.displayName,
          modalities: [],
          unitPrice: snapshot.unitPrice,
          salesWindow: {
            startAt: snapshot.createdAt,
            endAt: snapshot.expiresAt,
          },
          purchaseCutoffHoursBeforeDeparture:
            snapshot.eligibility.purchaseCutoffHoursBeforeDeparture,
          eligibilityRuleVersion: "captured",
          requiresEntitlementRef: !!snapshot.entitlementRef,
          requiresSegmentRef: snapshot.eligibility.segmentRefRequired,
          fulfillmentMethod: snapshot.fulfillmentMethod,
        },
      };
}
function withOrderCatalogSnapshot(
  snapshot: AncillaryOrderItemSnapshot,
): AncillaryOrderItemSnapshot {
  return snapshot.catalogSnapshot
    ? snapshot
    : {
        ...snapshot,
        catalogSnapshot: {
          catalogItemId: snapshot.catalogItemId,
          catalogItemVersion: snapshot.catalogItemVersion,
          serviceType: snapshot.serviceType,
          attachmentScope: snapshot.attachmentScope,
          displayName: snapshot.displayName,
          modalities: [],
          unitPrice: {
            currency: snapshot.payableAmount.currency,
            minorUnits: Math.floor(
              snapshot.payableAmount.minorUnits /
                Math.max(snapshot.quantity, 1),
            ),
          },
          salesWindow: {
            startAt: snapshot.selectedAt,
            endAt: snapshot.selectedAt,
          },
          purchaseCutoffHoursBeforeDeparture: 0,
          eligibilityRuleVersion: "captured",
          requiresEntitlementRef: !!snapshot.entitlementRef,
          requiresSegmentRef: snapshot.attachmentScope === "SEGMENT",
          fulfillmentMethod: snapshot.fulfillmentMethod,
        },
      };
}
