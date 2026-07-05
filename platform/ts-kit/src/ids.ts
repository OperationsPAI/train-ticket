import { randomBytes } from "node:crypto";

export type IdPrefix = "evt" | "cmd" | "corr";

const PREFIXED_UUID_V7 = /^(?<prefix>evt|cmd|corr)-(?<uuid>[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$/iu;

export function uuidV7(now: Date = new Date()): string {
  const bytes = randomBytes(16);
  const timestamp = BigInt(now.getTime());

  bytes[0] = Number((timestamp >> 40n) & 0xffn);
  bytes[1] = Number((timestamp >> 32n) & 0xffn);
  bytes[2] = Number((timestamp >> 24n) & 0xffn);
  bytes[3] = Number((timestamp >> 16n) & 0xffn);
  bytes[4] = Number((timestamp >> 8n) & 0xffn);
  bytes[5] = Number(timestamp & 0xffn);
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;

  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function parseUuid(value: string): { version: number } | undefined {
  const match = /^(?<timeLow>[0-9a-f]{8})-(?<timeMid>[0-9a-f]{4})-(?<version>[0-9a-f])(?<timeHigh>[0-9a-f]{3})-(?<variant>[89ab][0-9a-f]{3})-(?<node>[0-9a-f]{12})$/iu.exec(value);
  if (!match?.groups) {
    return undefined;
  }
  return { version: Number.parseInt(match.groups.version, 16) };
}

export function isUuidV7(value: string): boolean {
  return parseUuid(value)?.version === 7;
}

export function prefixedId(prefix: IdPrefix, id: string = uuidV7()): string {
  return `${prefix}-${validatedUuidV7(id, prefix)}`;
}

export function newEventId(): string {
  return prefixedId("evt");
}

export function newCommandId(): string {
  return prefixedId("cmd");
}

export function newCorrelationId(): string {
  return prefixedId("corr");
}

export function canonicalEventId(value: string): string {
  return prefixedId("evt", value);
}

export function canonicalCorrelationId(value: string): string {
  return prefixedId("corr", value);
}

export function canonicalCausationId(value: string): string {
  const prefix = prefixOf(value);
  if (prefix !== undefined && prefix !== "cmd" && prefix !== "evt") {
    throw new Error("Causation ID must use cmd- or evt- prefix");
  }
  return `${prefix ?? "cmd"}-${validatedUuidV7(value, prefix ?? "cmd")}`;
}

export function isPrefixedUuidV7(value: string, allowedPrefixes: readonly IdPrefix[] = ["evt", "cmd", "corr"]): boolean {
  const match = PREFIXED_UUID_V7.exec(value);
  return match?.groups !== undefined && allowedPrefixes.includes(match.groups.prefix as IdPrefix);
}

function validatedUuidV7(value: string, expectedPrefix: IdPrefix): string {
  const uuid = stripExpectedPrefix(value, expectedPrefix);
  if (!isUuidV7(uuid)) {
    throw new Error(`${expectedPrefix}- ID must contain a UUID v7`);
  }
  return uuid.toLowerCase();
}

function stripExpectedPrefix(value: string, expectedPrefix: IdPrefix): string {
  const prefix = prefixOf(value);
  if (prefix === undefined) {
    return value;
  }
  if (prefix !== expectedPrefix) {
    throw new Error(`ID must use ${expectedPrefix}- prefix`);
  }
  return value.slice(value.indexOf("-") + 1);
}

function prefixOf(value: string): IdPrefix | undefined {
  const match = /^(evt|cmd|corr)-/u.exec(value);
  return match?.[1] as IdPrefix | undefined;
}
