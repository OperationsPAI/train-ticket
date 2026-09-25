import type { PoolClient } from "pg";

import type { NotificationTaskSnapshot } from "./domain.js";

export type DeliveryPolicyDecision = Readonly<{
  action: "expire" | "supersede";
  reasonCode:
    | "ttl_action_required"
    | "ttl_order_state"
    | "ttl_operational"
    | "ttl_financial"
    | "ttl_default"
    | "newer_terminal_state";
}>;

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;

const ACTION_REQUIRED = new Set(["PAYMENT_REMINDER", "WAITLIST_PROMOTED"]);
const ORDER_STATE = new Set(["order_created", "order_payment_recorded", "ORDER_CONFIRMED", "order_adjusted", "order_cancelled"]);
const FINANCIAL = new Set(["payment_captured", "payment_failed", "REFUND_COMPLETED", "ticket_issued", "wallet_benefit_issued"]);

const SUPERSEDERS: Readonly<Record<string, readonly Readonly<{ eventType: string; templateCode: string }>[]>> = Object.freeze({
  order_created: Object.freeze([
    { eventType: "JourneyOrderConfirmed", templateCode: "ORDER_CONFIRMED" },
    { eventType: "JourneyOrderCancelled", templateCode: "order_cancelled" },
  ]),
  order_payment_recorded: Object.freeze([
    { eventType: "JourneyOrderConfirmed", templateCode: "ORDER_CONFIRMED" },
    { eventType: "JourneyOrderCancelled", templateCode: "order_cancelled" },
  ]),
  ORDER_CONFIRMED: Object.freeze([
    { eventType: "JourneyOrderCancelled", templateCode: "order_cancelled" },
    { eventType: "JourneyOrderPostSalesAdjusted", templateCode: "order_adjusted" },
    { eventType: "JourneyOrderAdjusted", templateCode: "order_adjusted" },
  ]),
});

export function notificationTtl(task: NotificationTaskSnapshot): Readonly<{ milliseconds: number; reasonCode: DeliveryPolicyDecision["reasonCode"] }> {
  if (ACTION_REQUIRED.has(task.templateCode)) return { milliseconds: 30 * MINUTE_MS, reasonCode: "ttl_action_required" };
  if (ORDER_STATE.has(task.templateCode)) return { milliseconds: 6 * HOUR_MS, reasonCode: "ttl_order_state" };
  if (FINANCIAL.has(task.templateCode)) return { milliseconds: 72 * HOUR_MS, reasonCode: "ttl_financial" };
  if (/^(ANCILLARY_|DISPATCH_|DRIVER_|RIDE_|TRANSFER_)/u.test(task.templateCode)) {
    return { milliseconds: 6 * HOUR_MS, reasonCode: "ttl_operational" };
  }
  return { milliseconds: 24 * HOUR_MS, reasonCode: "ttl_default" };
}

export function expiryDecision(task: NotificationTaskSnapshot, now: Date): DeliveryPolicyDecision | undefined {
  const scheduledAt = new Date(task.scheduledAt);
  if (!Number.isFinite(scheduledAt.getTime())) return { action: "expire", reasonCode: "ttl_default" };
  const ttl = notificationTtl(task);
  return scheduledAt.getTime() + ttl.milliseconds <= now.getTime()
    ? { action: "expire", reasonCode: ttl.reasonCode }
    : undefined;
}

export function supersedingSignatures(task: NotificationTaskSnapshot): readonly Readonly<{ triggerBusinessRef: string; templateCode: string }>[] {
  const superseders = SUPERSEDERS[task.templateCode];
  const separator = task.triggerBusinessRef?.indexOf(":") ?? -1;
  if (!superseders || separator < 1 || separator === task.triggerBusinessRef!.length - 1) return [];
  const businessId = task.triggerBusinessRef!.slice(separator + 1);
  return superseders.map(candidate => ({
    triggerBusinessRef: `${candidate.eventType}:${businessId}`,
    templateCode: candidate.templateCode,
  }));
}

export async function deliveryPolicyDecision(
  client: Pick<PoolClient, "query">,
  task: NotificationTaskSnapshot,
  now = new Date(),
): Promise<DeliveryPolicyDecision | undefined> {
  const signatures = supersedingSignatures(task);
  if (signatures.length > 0) {
    const result = await client.query(
      `SELECT 1
       FROM notification_task_snapshots
       WHERE data->>'recipientRef' = $1
         AND data->>'scheduledAt' > $2
         AND (data->>'triggerBusinessRef', data->>'templateCode') IN (
           SELECT signature.trigger_business_ref, signature.template_code
           FROM jsonb_to_recordset($3::jsonb) AS signature(trigger_business_ref text, template_code text)
         )
       LIMIT 1`,
      [task.recipientRef, new Date(task.scheduledAt).toISOString(), JSON.stringify(signatures.map(signature => ({
        trigger_business_ref: signature.triggerBusinessRef,
        template_code: signature.templateCode,
      })))],
    );
    if ((result.rowCount ?? 0) > 0) return { action: "supersede", reasonCode: "newer_terminal_state" };
  }
  return expiryDecision(task, now);
}
