import type { Clock } from './time.js';

/**
 * In-process sliding-window attempt limiter (e.g. 5 invitation-accept attempts per 15 minutes per
 * user+IP). Single-instance by design (see COUNCIL_DECISIONS D-04 known limit).
 */
export class AttemptLimiter {
  private readonly buckets = new Map<string, number[]>();

  constructor(
    private readonly max: number,
    private readonly windowMs: number,
    private readonly now: Clock,
  ) {}

  /** Records an attempt. Returns `{ allowed: true }` or the seconds until the window frees up. */
  attempt(key: string): { allowed: true } | { allowed: false; retryAfterSeconds: number } {
    const nowMs = this.now().getTime();
    const cutoff = nowMs - this.windowMs;
    const list = (this.buckets.get(key) ?? []).filter((t) => t > cutoff);
    if (list.length >= this.max) {
      const oldest = list[0] ?? nowMs;
      this.buckets.set(key, list);
      return {
        allowed: false,
        retryAfterSeconds: Math.max(1, Math.ceil((oldest + this.windowMs - nowMs) / 1000)),
      };
    }
    list.push(nowMs);
    this.buckets.set(key, list);
    this.sweep(cutoff);
    return { allowed: true };
  }

  reset(key: string): void {
    this.buckets.delete(key);
  }

  private sweep(cutoff: number): void {
    if (this.buckets.size < 1000) return;
    for (const [k, v] of this.buckets) {
      if (v.every((t) => t <= cutoff)) this.buckets.delete(k);
    }
  }
}
