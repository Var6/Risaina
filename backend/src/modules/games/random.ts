import { randomInt } from 'node:crypto';

/** `random(n)` returns an integer in [0, n). crypto.randomInt by default; tests may inject one. */
export type RandomInt = (n: number) => number;
export const cryptoRandom: RandomInt = (n) => randomInt(n);

/** Fisher–Yates shuffle into a new array. */
export function shuffled<T>(items: readonly T[], random: RandomInt = cryptoRandom): T[] {
  const out = [...items];
  for (let i = out.length - 1; i > 0; i--) {
    const j = random(i + 1);
    [out[i], out[j]] = [out[j] as T, out[i] as T];
  }
  return out;
}

/**
 * Picks `count` items at random, preferring ones whose id is not in `avoid` (recently played), and
 * only filling up with avoided ones when there are not enough fresh ones. The result order is random.
 */
export function sampleAvoiding<T>(
  pool: readonly T[],
  count: number,
  avoid: ReadonlySet<string>,
  idOf: (item: T) => string,
  random: RandomInt = cryptoRandom,
): T[] {
  const fresh = shuffled(
    pool.filter((x) => !avoid.has(idOf(x))),
    random,
  );
  const stale = shuffled(
    pool.filter((x) => avoid.has(idOf(x))),
    random,
  );
  return shuffled([...fresh, ...stale].slice(0, count), random);
}
