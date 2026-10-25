import type { Logger } from 'pino';

/**
 * Fire-and-forget work that must not hold up an HTTP response (e.g. deleting a couple's storage
 * objects after unlink). Failures are logged, never thrown. `drain()` waits for everything in
 * flight: the app awaits it on close, and tests await it before asserting.
 */
export class BackgroundTasks {
  private readonly pending = new Set<Promise<void>>();

  constructor(private readonly logger: Logger) {}

  run(name: string, task: () => Promise<unknown>): void {
    const p: Promise<void> = (async () => {
      try {
        await task();
      } catch (err) {
        this.logger.error({ task: name, err: (err as Error).message }, 'background task failed');
      }
    })();
    this.pending.add(p);
    void p.then(() => this.pending.delete(p));
  }

  async drain(): Promise<void> {
    while (this.pending.size > 0) await Promise.all([...this.pending]);
  }
}
