/**
 * Process-level liveness tracking.
 *
 * Background: a Kubernetes liveness probe that always returns 200 defeats
 * kubelet self-healing. On 2026-09-06 the shared Redis pod was OOMKilled; the
 * `account` pod's Redis client never reconnected, so the pod consumed nothing
 * for 17 hours while `/healthz` happily answered 200 and readiness answered
 * 503. Readiness pulled the pod out of the Service endpoints, liveness kept it
 * alive, and nothing ever restarted it.
 *
 * The fix is a liveness signal that is *eventually* dependency-aware:
 *
 *  - Components (Redis connections, stream-consumer loops, outbox relays)
 *    register here and continuously report healthy/unhealthy.
 *  - A component being unhealthy does NOT immediately fail liveness. It only
 *    fails once it has been *continuously* unhealthy for longer than its grace
 *    period. A transient Redis blip therefore never restarts the pod, while a
 *    permanently wedged process does get restarted.
 *
 * Grace period default: 5 minutes (see DEFAULT_LIVENESS_GRACE_MS).
 */
/**
 * Five minutes.
 *
 * Chosen deliberately, and materially longer than any legitimate reconnect:
 *
 *  - A healthy ioredis reconnect after a Redis pod restart completes in
 *    seconds. During the 2026-09-06 incident Redis was back within ~10s and
 *    the Lettuce-based Java services reconnected 4s after the drop.
 *  - `redisRetryStrategy` caps its backoff at 30s, so the worst-case delay
 *    between "Redis is reachable again" and "we noticed" is ~30s.
 *  - 300s therefore leaves ~10x headroom over the worst-case legitimate
 *    reconnect, including a full Redis pod reschedule.
 *  - The k8s liveness probe adds its own delay on top (account:
 *    periodSeconds 20 x failureThreshold 3 = ~60s of continuous failure), so
 *    the real time-to-restart is ~6 minutes of a genuinely dead consumer.
 *    That is short enough to self-heal a 20-hour outage and far too long to
 *    flap on a blip.
 */
export const DEFAULT_LIVENESS_GRACE_MS = 5 * 60 * 1_000;
export function livenessGraceMs() {
    const configured = Number.parseInt(process.env.REDIS_LIVENESS_GRACE_MS ?? "", 10);
    return Number.isFinite(configured) && configured > 0 ? configured : DEFAULT_LIVENESS_GRACE_MS;
}
export class LivenessComponent {
    name;
    gracePeriodMs;
    clock;
    onDispose;
    healthy = false;
    reason = "not yet connected";
    unhealthySince;
    disposed = false;
    /**
     * Liveness only judges components that have worked at least once.
     *
     * A component that has never been healthy is still starting up (or belongs
     * to a client nobody ever connected). Failing liveness for it would turn a
     * slow dependency at boot into a restart loop, and would let an unused
     * client kill an otherwise healthy process. Kubernetes has readiness and
     * `initialDelaySeconds` for startup; liveness is strictly about "this
     * process used to work and is now permanently stuck".
     */
    everHealthy = false;
    constructor(name, gracePeriodMs, clock = Date.now, onDispose = () => { }) {
        this.name = name;
        this.gracePeriodMs = gracePeriodMs;
        this.clock = clock;
        this.onDispose = onDispose;
        this.unhealthySince = clock();
    }
    markHealthy() {
        this.healthy = true;
        this.everHealthy = true;
        this.reason = undefined;
    }
    markUnhealthy(reason) {
        if (this.healthy) {
            this.unhealthySince = this.clock();
        }
        this.healthy = false;
        this.reason = reason;
    }
    isHealthy() {
        return this.healthy;
    }
    snapshot() {
        const unhealthyForMs = this.healthy ? 0 : Math.max(0, this.clock() - this.unhealthySince);
        return {
            name: this.name,
            healthy: this.healthy,
            reason: this.reason,
            unhealthyForMs,
            gracePeriodMs: this.gracePeriodMs,
            expired: this.everHealthy && !this.healthy && unhealthyForMs >= this.gracePeriodMs,
        };
    }
    dispose() {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        this.onDispose(this);
    }
}
export class LivenessRegistry {
    components = new Set();
    register(name, options = {}) {
        const component = new LivenessComponent(name, options.gracePeriodMs ?? livenessGraceMs(), options.clock ?? Date.now, (disposed) => this.components.delete(disposed));
        this.components.add(component);
        return component;
    }
    state() {
        const components = [...this.components].map((component) => component.snapshot());
        const failed = components.filter((component) => component.expired);
        return { live: failed.length === 0, components, failed };
    }
    clear() {
        this.components.clear();
    }
}
export const livenessRegistry = new LivenessRegistry();
export function registerLivenessComponent(name, options) {
    return livenessRegistry.register(name, options);
}
export function livenessState() {
    return livenessRegistry.state();
}
export function isProcessLive() {
    return livenessRegistry.state().live;
}
/**
 * Body + status code for a `/healthz` (liveness) probe.
 *
 * Returns 503 only when a registered component has been continuously
 * unhealthy past its grace period, i.e. when the process is genuinely
 * incapable of doing work and only a restart can fix it.
 */
export function livenessProbe() {
    const state = livenessState();
    return { statusCode: state.live ? 200 : 503, live: state.live, failed: state.failed };
}
