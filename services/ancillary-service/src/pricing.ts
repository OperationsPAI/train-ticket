import { createHash } from "node:crypto";

import { uuidV7 } from "@trainticket/ts-kit";

import type { Money } from "./domain.js";

export type PriceExplanation = Readonly<{
  code: string;
  parameters: Readonly<Record<string, string>>;
}>;

export type PriceComponent = Readonly<{
  ruleId: string;
  amount: Money;
  explanation: PriceExplanation;
  refundable: boolean;
}>;

export type RuleSnapshot = Readonly<{
  ruleSetId: string;
  ruleSetVersion: string;
  capturedAt: string;
  ruleIds: readonly string[];
  explanationCodes: readonly string[];
  digest: string;
}>;

export type PriceQuoteRef = Readonly<{
  quoteId: string;
  inputHash: string;
  ruleSnapshot?: RuleSnapshot;
  source: "FARE_PRICING" | "CATALOG_FALLBACK";
}>;

export type PricingContext = Readonly<{
  channel?: string;
  productCode?: string;
  fareRuleRefs?: readonly string[];
  seatClass?: string;
  distanceKm?: number;
  departureTime?: string;
}>;

export type AncillaryPricingInput = Readonly<{
  ancillaryOfferId: string;
  offerVersion: number;
  catalogItemId: string;
  travelerRef: string;
  segmentRef?: string;
  departureAt: string;
  quantity: number;
  catalogUnitPrice: Money;
  context: PricingContext;
  correlationId: string;
  idempotencyKey?: string;
}>;

export type AncillaryPricingResult = Readonly<{
  quoteId: string;
  inputHash: string;
  unitPrice: Money;
  validFrom?: string;
  validUntil?: string;
  ruleSnapshot?: RuleSnapshot;
  fees: readonly PriceComponent[];
}>;

export interface AncillaryPricingGateway {
  quoteAncillaryPrice(
    input: AncillaryPricingInput,
  ): Promise<AncillaryPricingResult | undefined>;
}

export function farePricingInputHash(
  segmentRefs: readonly string[],
  channel: string,
  travelerRefs: readonly string[],
): string {
  const material = `${[...segmentRefs].sort().join(",")}|${channel}|${[
    ...travelerRefs,
  ]
    .sort()
    .join(",")}`;
  return createHash("sha256").update(material, "utf8").digest("hex");
}

export function ancillarySegmentRefs(
  input: Pick<AncillaryPricingInput, "segmentRef" | "catalogItemId">,
): readonly string[] {
  return [input.segmentRef ?? input.catalogItemId];
}

export function multiplyPriceComponent(
  component: PriceComponent,
  quantity: number,
): PriceComponent {
  return {
    ...component,
    amount: {
      currency: component.amount.currency,
      minorUnits: component.amount.minorUnits * quantity,
    },
  };
}

export class HttpFarePricingGateway implements AncillaryPricingGateway {
  constructor(
    private readonly baseUrl: string,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  async quoteAncillaryPrice(
    input: AncillaryPricingInput,
  ): Promise<AncillaryPricingResult | undefined> {
    const channel = input.context.channel ?? "DIRECT";
    const segmentRefs = ancillarySegmentRefs(input);
    const travelerRefs = [input.travelerRef];
    const requestBody: Record<string, unknown> = {
      travelerRefs,
      channel,
      segmentRefs,
      productCode: input.context.productCode ?? input.catalogItemId,
      fareRuleRefs: input.context.fareRuleRefs,
      seatClass: input.context.seatClass,
      distanceKm: input.context.distanceKm,
      departureTime: input.context.departureTime ?? input.departureAt,
    };
    for (const key of Object.keys(requestBody))
      if (requestBody[key] === undefined) delete requestBody[key];

    let response: Response;
    try {
      response = await this.fetchImpl(
        `${this.baseUrl.replace(/\/$/, "")}/api/v1/fare-quotes`,
        {
          method: "POST",
          headers: {
            "content-type": "application/json",
            "idempotency-key": input.idempotencyKey ?? uuidV7(),
            "x-correlation-id": input.correlationId,
          },
          body: JSON.stringify(requestBody),
        },
      );
    } catch {
      return undefined;
    }
    if (!response.ok) return undefined;
    const payload = (await response.json()) as FareQuoteResponse;
    if (payload.status !== "QUOTED" || !payload.breakdown?.total)
      return undefined;
    return {
      quoteId: payload.quoteId,
      inputHash: farePricingInputHash(segmentRefs, channel, travelerRefs),
      unitPrice: payload.breakdown.total,
      validFrom: payload.validFrom,
      validUntil: payload.validUntil,
      ruleSnapshot: payload.ruleSnapshot,
      fees: payload.breakdown.fees ?? [],
    };
  }
}

type FareQuoteResponse = Readonly<{
  quoteId: string;
  status: "QUOTED" | "FAILED";
  validFrom: string;
  validUntil: string;
  breakdown?: Readonly<{
    total: Money;
    fees?: readonly PriceComponent[];
  }>;
  ruleSnapshot?: RuleSnapshot;
}>;
