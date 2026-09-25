import { HandlerError } from "./messaging.js";
import { PostgresDeadLetterEvidenceStore, withTransaction } from "./storage.js";
import { tracedFetch } from "./outbound.js";
export class ReplayRetentionGap extends Error {
    source;
    detail;
    constructor(source, detail) {
        super(`event replay retention gap for ${source}`);
        this.source = source;
        this.detail = detail;
        this.name = "ReplayRetentionGap";
    }
}
export class DurableEventReplayWorker {
    pool;
    sources;
    handler;
    options;
    stopped = true;
    loop;
    checkpoints;
    evidence;
    constructor(pool, sources, handler, options) {
        this.pool = pool;
        this.sources = sources;
        this.handler = handler;
        this.options = options;
        if (!options.consumerGroup.trim())
            throw new Error("consumerGroup is required");
        this.checkpoints = new PostgresReplayCheckpointStore(pool);
        this.evidence = options.deadLetterEvidenceSink ?? new PostgresDeadLetterEvidenceStore(pool);
    }
    start() {
        if (!this.stopped)
            return;
        this.stopped = false;
        this.loop = this.run();
    }
    async stop() {
        this.stopped = true;
        await this.loop;
    }
    async runSourceOnce(source) {
        const lock = await this.pool.connect();
        const lockKey = `event-replay:${this.options.consumerGroup}:${source.name}`;
        try {
            const acquired = await lock.query("SELECT pg_try_advisory_lock(hashtextextended($1,0)) AS acquired", [lockKey]);
            if (!acquired.rows[0]?.acquired)
                return 0;
            const checkpoint = await this.checkpoints.getOrCreate(this.options.consumerGroup, source);
            let page;
            try {
                page = await (this.options.fetchPage ?? fetchReplayPage)(source, checkpoint, this.pageSize());
                validateReplayPage(source, checkpoint, page);
            }
            catch (error) {
                if (error instanceof ReplayRetentionGap) {
                    await this.checkpoints.recordGap(this.options.consumerGroup, source, error.detail);
                }
                else {
                    await this.checkpoints.recordError(this.options.consumerGroup, source, error);
                }
                throw error;
            }
            let handled = 0;
            for (const archived of page.events) {
                let envelope;
                try {
                    envelope = parseArchivedEnvelope(source, archived);
                }
                catch (error) {
                    await this.checkpoints.recordError(this.options.consumerGroup, source, error);
                    throw error;
                }
                await this.checkpoints.beginRecovery(this.options.consumerGroup, source, archived.sequence, envelope.eventId);
                let result;
                let failure;
                try {
                    const output = await this.handler(envelope, archived.stream);
                    result = replayResult(output);
                    failure = replayFailure(output);
                }
                catch (error) {
                    result = error instanceof HandlerError && error.kind === "fatal" ? "dlq" : "retry";
                    failure = error;
                }
                if (result === "retry") {
                    await this.checkpoints.recordRecoveryError(this.options.consumerGroup, source, archived.sequence, envelope.eventId, failure);
                    throw failure instanceof Error ? failure : new Error(String(failure ?? "replay handler requested retry"));
                }
                if (result === "dlq") {
                    await this.evidence.recordDeadLetter({
                        sourceStream: archived.stream,
                        sourceMessageId: `replay:${source.name}:${archived.sequence}`,
                        consumerGroup: this.options.consumerGroup,
                        consumerName: `replay-${source.name}`,
                        eventId: envelope.eventId,
                        eventType: envelope.eventType,
                        producer: envelope.producer,
                        originalEnvelope: archived.envelope,
                        failureKind: "FATAL_HANDLER",
                        failureReason: failureText(failure),
                        attempts: 1,
                        deadLetteredAt: new Date().toISOString(),
                    });
                }
                await this.checkpoints.advance(this.options.consumerGroup, source, archived.sequence, envelope.eventId, result === "ack");
                handled += 1;
            }
            await this.checkpoints.recordSuccess(this.options.consumerGroup, source);
            return handled;
        }
        finally {
            try {
                await lock.query("SELECT pg_advisory_unlock(hashtextextended($1,0))", [lockKey]);
            }
            catch { }
            lock.release();
        }
    }
    async run() {
        const log = this.options.log ?? ((entry) => console.warn(entry));
        while (!this.stopped) {
            for (const source of this.sources) {
                if (this.stopped)
                    break;
                try {
                    for (let page = 0; page < (this.options.maxPagesPerCycle ?? 10); page += 1) {
                        if (await this.runSourceOnce(source) < this.pageSize())
                            break;
                    }
                }
                catch (error) {
                    log({
                        service: this.options.consumerGroup,
                        replaySource: source.name,
                        error: failureText(error),
                        message: "durable event replay failed; checkpoint was not advanced and will retry",
                    });
                }
            }
            await interruptibleDelay(this.options.pollIntervalMs ?? 1_000, () => this.stopped);
        }
    }
    pageSize() {
        return Math.max(1, Math.min(this.options.pageSize ?? 100, 500));
    }
}
export class PostgresReplayCheckpointStore {
    pool;
    constructor(pool) {
        this.pool = pool;
    }
    async getOrCreate(consumerGroup, source) {
        const result = await this.pool.query(`INSERT INTO event_replay_checkpoints(consumer_group,source_name,source_endpoint,after_sequence,status)
       VALUES($1,$2,$3,0,'ACTIVE')
       ON CONFLICT(consumer_group,source_name) DO UPDATE SET source_endpoint=EXCLUDED.source_endpoint
       RETURNING after_sequence`, [consumerGroup, source.name, source.endpoint]);
        return Number(result.rows[0].after_sequence);
    }
    async beginRecovery(consumerGroup, source, sequence, eventId) {
        await this.pool.query(`UPDATE event_dead_letters SET recovery_status='REPLAYING',recovery_source=$1,recovery_sequence=$2,
         recovery_started_at=coalesce(recovery_started_at,now()),recovery_error=NULL
       WHERE consumer_group=$3 AND event_id=$4 AND recovery_status IN ('OPEN','REPLAYING')`, [source.name, sequence, consumerGroup, eventId]);
    }
    async advance(consumerGroup, source, sequence, eventId, recovered) {
        await withTransaction(this.pool, async (client) => {
            await client.query(`UPDATE event_replay_checkpoints SET after_sequence=greatest(after_sequence,$3),status='ACTIVE',
           last_event_id=$4,last_attempt_at=now(),last_error=NULL,last_success_at=now(),updated_at=now()
         WHERE consumer_group=$1 AND source_name=$2`, [consumerGroup, source.name, sequence, eventId]);
            if (recovered) {
                await client.query(`UPDATE event_dead_letters SET recovery_status='RECOVERED',recovery_source=$1,recovery_sequence=$2,
             recovery_finished_at=now(),recovery_error=NULL
           WHERE consumer_group=$3 AND event_id=$4 AND recovery_status='REPLAYING'`, [source.name, sequence, consumerGroup, eventId]);
            }
            else {
                await client.query(`UPDATE event_dead_letters SET recovery_status='OPEN',recovery_source=$1,recovery_sequence=$2,
             recovery_finished_at=now()
           WHERE consumer_group=$3 AND event_id=$4 AND recovery_status='REPLAYING'`, [source.name, sequence, consumerGroup, eventId]);
            }
        });
    }
    async recordRecoveryError(consumerGroup, source, sequence, eventId, error) {
        await this.pool.query(`UPDATE event_dead_letters SET recovery_status='OPEN',recovery_source=$1,recovery_sequence=$2,
         recovery_error=$3,recovery_finished_at=now()
       WHERE consumer_group=$4 AND event_id=$5 AND recovery_status='REPLAYING'`, [source.name, sequence, failureText(error), consumerGroup, eventId]);
        await this.recordError(consumerGroup, source, error);
    }
    async recordSuccess(consumerGroup, source) {
        await this.pool.query(`UPDATE event_replay_checkpoints SET status='ACTIVE',last_error=NULL,last_success_at=now(),updated_at=now()
       WHERE consumer_group=$1 AND source_name=$2`, [consumerGroup, source.name]);
    }
    async recordError(consumerGroup, source, error) {
        await this.pool.query(`UPDATE event_replay_checkpoints SET status='ERROR',last_error=$3,last_attempt_at=now(),updated_at=now()
       WHERE consumer_group=$1 AND source_name=$2`, [consumerGroup, source.name, failureText(error)]);
    }
    async recordGap(consumerGroup, source, detail) {
        await this.pool.query(`UPDATE event_replay_checkpoints SET status='GAP',last_error=$3,last_attempt_at=now(),updated_at=now()
       WHERE consumer_group=$1 AND source_name=$2`, [consumerGroup, source.name, failureText(detail)]);
    }
}
async function fetchReplayPage(source, after, limit) {
    const url = new URL(source.endpoint);
    url.searchParams.set("after", String(after));
    url.searchParams.set("limit", String(limit));
    const response = await tracedFetch(url, { signal: AbortSignal.timeout(5_000) });
    const body = await response.json().catch(() => ({ message: `HTTP ${response.status}` }));
    if (response.status === 410)
        throw new ReplayRetentionGap(source.name, body);
    if (!response.ok)
        throw new Error(`event replay source ${source.name} returned HTTP ${response.status}: ${failureText(body)}`);
    return body;
}
function validateReplayPage(source, after, page) {
    if (!page || page.producer !== source.producer || page.afterSequence !== after || !Array.isArray(page.events)) {
        throw new Error(`event replay source ${source.name} returned inconsistent page metadata`);
    }
    let previous = after;
    for (const event of page.events) {
        if (!Number.isSafeInteger(event.sequence) || event.sequence <= previous || typeof event.envelope !== "string") {
            throw new Error(`event replay source ${source.name} returned an invalid sequence`);
        }
        previous = event.sequence;
    }
}
function parseArchivedEnvelope(source, archived) {
    const parsed = JSON.parse(archived.envelope);
    if (!parsed || parsed.eventId !== archived.eventId || parsed.producer !== source.producer
        || typeof parsed.eventType !== "string" || !parsed.payload || typeof parsed.payload !== "object") {
        throw new Error(`event replay source ${source.name} returned inconsistent event identity`);
    }
    return parsed;
}
function replayResult(output) {
    if (output === undefined || output === "ack" || (typeof output === "object" && output.ok === true))
        return "ack";
    if (output === "retry")
        return "retry";
    if (output === "dlq")
        return "dlq";
    const kind = output.kind ?? output.errorType ?? "transient";
    return kind === "fatal" ? "dlq" : "retry";
}
function replayFailure(output) {
    return typeof output === "object" && output && "error" in output ? output.error : output;
}
function failureText(value) {
    if (value instanceof Error)
        return `${value.name}: ${value.message}`.slice(0, 2_000);
    try {
        return JSON.stringify(value).slice(0, 2_000);
    }
    catch {
        return String(value).slice(0, 2_000);
    }
}
async function interruptibleDelay(milliseconds, stopped) {
    const end = Date.now() + milliseconds;
    while (!stopped() && Date.now() < end) {
        await new Promise(resolve => setTimeout(resolve, Math.min(100, end - Date.now())));
    }
}
