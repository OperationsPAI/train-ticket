import { createHash } from "node:crypto";

export function deliveryErrorEvidence(error: unknown): Readonly<{ name: string; code?: string; reason: string; fingerprint: string }> {
  const details = error !== null && typeof error === "object" ? error as Record<string, unknown> : {};
  const message = error instanceof Error ? error.message : typeof error === "string" ? error : "Non-error rejection";
  const code = typeof details.code === "string" && /^(E[A-Z_]{2,40}|[0-9A-Z]{5})$/.test(details.code) ? details.code : undefined;
  const name = error instanceof TypeError ? "TypeError" : error instanceof RangeError ? "RangeError" : error instanceof Error ? "Error" : "NonError";
  const reasons: readonly [RegExp, string][] = [
    [/refused/i, "Connection refused"], [/timed? ?out|timeout/i, "Connection or operation timed out"],
    [/reset|socket closed|connection closed|unexpected end/i, "Connection interrupted"],
    [/auth|credential/i, "Authentication rejected"], [/dns|getaddrinfo|ENOTFOUND/i, "Name resolution failed"],
    [/tls|certificate/i, "TLS negotiation failed"], [/pool.*closed|pool.*ended/i, "Connection pool closed"],
    [/claim/i, "Delivery claim conflict"], [/missing/i, "Required delivery record missing"],
    [/constraint|duplicate/i, "Database constraint rejected operation"], [/permission|denied/i, "Permission denied"],
  ];
  return { name, code, reason: reasons.find(([pattern]) => pattern.test(message))?.[1] ?? "Unclassified exception; correlate fingerprint with restricted diagnostics",
    fingerprint: createHash("sha256").update(message).digest("hex") };
}
