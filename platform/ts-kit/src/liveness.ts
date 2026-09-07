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

export type LivenessSnapshot = Readonly<{
  name: string;
  healthy: boolean;
  reason?: string;
  unhealthyForMs: number;
  gracePeriodMs: number;
  /** True when the component has been continuously unhealthy past its grace period. */
  expired: boolean;
}>;

export type LivenessState = Readonly<{
  live: boolean;
  components: readonly LivenessSnapshot[];
  /** Components that have exceeded their grace period, i.e. the reason liveness fails. */
  failed: readonly LivenessSnapshot[];
}>;

export type Clock = () => number;

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

export function livenessGraceMs(): number {
  const configured = Number.parseInt(process.env.REDIS_LIVENESS_GRACE_MS ?? "", 10);
  return Number.isFinite(configured) && configured > 0 ? configured : DEFAULT_LIVENESS_GRACE_MS;
}

export class LivenessComponent {
  private healthy = false;
  private reason: string | undefined = "not yet connected";
  private unhealthySince: number;
  private disposed = false;
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
  private everHealthy = false;

  constructor(
    public readonly name: string,
    public readonly gracePeriodMs: number,
    private readonly clock: Clock = Date.now,
    private readonly onDispose: (component: LivenessComponent) => void = () => {},
  ) {
    this.unhealthySince = clock();
  }

  markHealthy(): void {
    this.healthy = true;
    this.everHealthy = true;
    this.reason = undefined;
  }

  markUnhealthy(reason: string): void {
    if (this.healthy) {
      this.unhealthySince = this.clock();
    }
    this.healthy = false;
    this.reason = reason;
  }

  isHealthy(): boolean {
    return this.healthy;
  }

  snapshot(): LivenessSnapshot {
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

  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.onDispose(this);
  }
}

export type RegisterLivenessOptions = Readonly<{
  gracePeriodMs?: number;
  clock?: Clock;
}>;

export class LivenessRegistry {
  private readonly components = new Set<LivenessComponent>();

  register(name: string, options: RegisterLivenessOptions = {}): LivenessComponent {
    const component = new LivenessComponent(
      name,
      options.gracePeriodMs ?? livenessGraceMs(),
      options.clock ?? Date.now,
      (disposed) => this.components.delete(disposed),
    );
    this.components.add(component);
    return component;
  }

  state(): LivenessState {
    const components = [...this.components].map((component) => component.snapshot());
    const failed = components.filter((component) => component.expired);
    return { live: failed.length === 0, components, failed };
  }

  clear(): void {
    this.components.clear();
  }
}

export const livenessRegistry = new LivenessRegistry();

export function registerLivenessComponent(name: string, options?: RegisterLivenessOptions): LivenessComponent {
  return livenessRegistry.register(name, options);
}

export function livenessState(): LivenessState {
  return livenessRegistry.state();
}

export function isProcessLive(): boolean {
  return livenessRegistry.state().live;
}

/**
 * Body + status code for a `/healthz` (liveness) probe.
 *
 * Returns 503 only when a registered component has been continuously
 * unhealthy past its grace period, i.e. when the process is genuinely
 * incapable of doing work and only a restart can fix it.
 */
export function livenessProbe(): Readonly<{ statusCode: number; live: boolean; failed: readonly LivenessSnapshot[] }> {
  const state = livenessState();
  return { statusCode: state.live ? 200 : 503, live: state.live, failed: state.failed };
}
