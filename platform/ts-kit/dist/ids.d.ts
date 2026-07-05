export type IdPrefix = "evt" | "cmd" | "corr";
export declare function uuidV7(now?: Date): string;
export declare function parseUuid(value: string): {
    version: number;
} | undefined;
export declare function isUuidV7(value: string): boolean;
export declare function prefixedId(prefix: IdPrefix, id?: string): string;
export declare function newEventId(): string;
export declare function newCommandId(): string;
export declare function newCorrelationId(): string;
export declare function canonicalEventId(value: string): string;
export declare function canonicalCorrelationId(value: string): string;
export declare function canonicalCausationId(value: string): string;
export declare function isPrefixedUuidV7(value: string, allowedPrefixes?: readonly IdPrefix[]): boolean;
