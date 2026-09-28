import { readFileSync } from "node:fs";

import { DomainError, type CatalogInput } from "./domain.js";

/**
 * The catalogue intake policy the release carries.
 *
 * The values live in `config/catalog.properties` (`CATALOG_POLICY_FILE`
 * overrides the path) so a deployment states its own notice floor instead of
 * every caller agreeing on one. The file is read once and cached: the policy is
 * a property of the process, not of a request.
 */
export type CatalogPolicy = Readonly<{
  /** Hours before departure an item of an enforced method must close its sales. */
  noticeFloorHours: number;
  /** Fulfillment methods the floor is enforced for. */
  noticeFloorMethods: readonly string[];
  /** Where the values were read from, for the record. */
  source: string;
}>;

/**
 * The values a build without a policy file answers with, so an isolated unit
 * test or a run outside the image keeps the behaviour it had before the file
 * existed. The release's own file is what decides in the cluster.
 */
const BUILT_IN_POLICY: CatalogPolicy = {
  noticeFloorHours: 1,
  noticeFloorMethods: [],
  source: "built-in",
};

export function catalogPolicyFile(): string {
  return process.env.CATALOG_POLICY_FILE?.trim() || "/app/config/catalog.properties";
}

/**
 * The `key=value` pairs a properties file holds, read the way the other
 * services' files are read: comments and blank lines dropped, the first `=`
 * separating a key from its value, later whitespace trimmed from both.
 */
export function readProperties(text: string): ReadonlyMap<string, string> {
  const values = new Map<string, string>();
  for (const line of text.split("\n")) {
    const entry = line.trim();
    if (!entry || entry.startsWith("#")) continue;
    const separator = entry.indexOf("=");
    if (separator < 0) continue;
    values.set(entry.slice(0, separator).trim(), entry.slice(separator + 1).trim());
  }
  return values;
}

export function parseCatalogPolicy(text: string, source: string): CatalogPolicy {
  const values = readProperties(text);
  const hours = Number.parseInt(values.get("catalog.notice.floor.hours") ?? "", 10);
  const methods = (values.get("catalog.notice.floor.methods") ?? "")
    .split(",")
    .map((method) => method.trim())
    .filter((method) => method.length > 0);
  return {
    noticeFloorHours: Number.isFinite(hours) ? hours : BUILT_IN_POLICY.noticeFloorHours,
    noticeFloorMethods: methods,
    source,
  };
}

export function loadCatalogPolicy(file = catalogPolicyFile()): CatalogPolicy {
  try {
    return parseCatalogPolicy(readFileSync(file, "utf8"), file);
  } catch {
    return BUILT_IN_POLICY;
  }
}

let cached: CatalogPolicy | undefined;

export function catalogPolicy(): CatalogPolicy {
  cached ??= loadCatalogPolicy();
  return cached;
}

/** Drops the cached policy; the tests read a different file per case. */
export function resetCatalogPolicy(): void {
  cached = undefined;
}

/** The floor an item's method is held to; 0 when the release enforces none. */
export function noticeFloorHoursFor(method: string): number {
  const policy = catalogPolicy();
  return policy.noticeFloorMethods.includes(method) ? policy.noticeFloorHours : 0;
}

export function requireNoticeFloor(
  input: Pick<
    CatalogInput,
    "fulfillmentMethod" | "purchaseCutoffHoursBeforeDeparture"
  >,
): void {
  const floor = noticeFloorHoursFor(input.fulfillmentMethod);
  if (floor > 0 && input.purchaseCutoffHoursBeforeDeparture < floor)
    throw new DomainError(
      "NOTICE_FLOOR_NOT_MET",
      `a catalogue item fulfilled by ${input.fulfillmentMethod} must close its sales at least ${floor} hours before departure`,
    );
}
