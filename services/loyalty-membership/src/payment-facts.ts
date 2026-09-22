import { tracedFetch, type EventEnvelope } from "@trainticket/ts-kit";
import type { Money } from "./domain.js";

export type CapturedPaymentFacts = Readonly<{
  paymentIntentId: string;
  businessRef: string;
  payerRef: string;
  purpose: string;
  status: string;
  capturedAmount: Money;
  refundedAmount: Money;
  events: readonly EventEnvelope[];
}>;

export async function resolveCapturedPaymentFacts(paymentIntentId: string): Promise<CapturedPaymentFacts> {
  const base = process.env.PAYMENT_BASE_URL ?? "http://payment:8080";
  const response = await tracedFetch(`${base}/api/v1/internal/payment-intents/${encodeURIComponent(paymentIntentId)}/captured-facts`, { signal: AbortSignal.timeout(5000) });
  if (!response.ok) throw new Error(`Payment fact lookup returned HTTP ${response.status}`);
  const facts = await response.json() as CapturedPaymentFacts;
  if (facts.paymentIntentId !== paymentIntentId || !facts.businessRef || !facts.payerRef || !Array.isArray(facts.events)) {
    throw new Error("Payment fact lookup returned inconsistent references");
  }
  return facts;
}
