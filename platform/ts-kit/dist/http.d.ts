export type ErrorEnvelope = Readonly<{
    code: string;
    message: string;
    correlationId: string;
    details: Readonly<Record<string, unknown>>;
}>;
export type RequestContext = Readonly<{
    requestId: string;
    correlationId: string;
}>;
export type IdempotencyRecord = Readonly<{
    fingerprint: string;
    statusCode: number;
    body: unknown;
}>;
export interface IdempotencyStore {
    get(key: string): IdempotencyRecord | undefined | Promise<IdempotencyRecord | undefined>;
    set(key: string, record: IdempotencyRecord): void | Promise<void>;
}
export declare class InMemoryIdempotencyStore implements IdempotencyStore {
    private readonly records;
    get(key: string): IdempotencyRecord | undefined;
    set(key: string, record: IdempotencyRecord): void;
    save(key: string, record: {
        bodyHash?: string;
        statusCode: number;
        payload?: unknown;
        body?: unknown;
        fingerprint?: string;
    }): void;
    clear(): void;
}
export type HeaderBag = Record<string, string | string[] | undefined>;
export declare function headerValue(value: string | string[] | undefined): string | undefined;
export declare function requestContext(input: {
    headers: HeaderBag;
    id?: string;
}): RequestContext;
export declare function canonicalHttpCorrelationId(value: string | undefined): string;
export declare function errorBody(code: string, message: string, context: RequestContext, details?: Readonly<Record<string, unknown>>): ErrorEnvelope;
export declare function sendError(reply: {
    status: (statusCode: number) => {
        send: (body: ErrorEnvelope) => unknown;
    };
}, statusCode: number, code: string, message: string, context: RequestContext, details?: Readonly<Record<string, unknown>>): void;
export declare function isFrameworkValidationError(error: unknown): boolean;
export declare function errorMessage(error: unknown): string;
export declare function requestFingerprint(method: string, path: string, body: unknown): string;
export declare const fingerprintRequest: typeof requestFingerprint;
export declare function handleIdempotency<T>(options: {
    key: string | undefined;
    store: IdempotencyStore;
    fingerprint: string;
    context: RequestContext;
    reply: {
        status: (statusCode: number) => {
            send: (body: unknown) => unknown;
        };
    };
    operation: () => Promise<Readonly<{
        statusCode: number;
        body: T;
    }>>;
}): Promise<void>;
export declare function handleIdempotentPost<T>(request: {
    headers: HeaderBag;
    id?: string;
    method: string;
    url: string;
    body?: unknown;
}, reply: {
    status: (statusCode: number) => {
        send: (body: unknown) => unknown;
    };
}, store: IdempotencyStore, handler: () => Promise<Readonly<{
    statusCode: number;
    payload: T;
}>>): Promise<T | ErrorEnvelope | undefined>;
