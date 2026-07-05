import { randomBytes } from "node:crypto";
export function uuidV7(now = new Date()) {
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
export function parseUuid(value) {
    const match = /^(?<timeLow>[0-9a-f]{8})-(?<timeMid>[0-9a-f]{4})-(?<version>[0-9a-f])(?<timeHigh>[0-9a-f]{3})-(?<variant>[89ab][0-9a-f]{3})-(?<node>[0-9a-f]{12})$/iu.exec(value);
    if (!match?.groups) {
        return undefined;
    }
    return { version: Number.parseInt(match.groups.version, 16) };
}
export function isUuidV7(value) {
    return parseUuid(value)?.version === 7;
}
export function prefixedId(prefix, id = uuidV7()) {
    return id.startsWith(`${prefix}-`) ? id : `${prefix}-${id}`;
}
export function newEventId() {
    return prefixedId("evt");
}
export function newCommandId() {
    return prefixedId("cmd");
}
export function newCorrelationId() {
    return prefixedId("corr");
}
export function canonicalEventId(value) {
    return prefixedId("evt", stripKnownPrefix(value));
}
export function canonicalCorrelationId(value) {
    return prefixedId("corr", stripKnownPrefix(value));
}
export function canonicalCausationId(value) {
    if (value.startsWith("cmd-") || value.startsWith("evt-")) {
        return value;
    }
    return `cmd-${value}`;
}
function stripKnownPrefix(value) {
    return /^(evt|cmd|corr)-/.test(value) ? value.slice(value.indexOf("-") + 1) : value;
}
