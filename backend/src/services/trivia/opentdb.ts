import { createHash } from 'node:crypto';
import type { Logger } from 'pino';
import { z } from 'zod';
import { type QuizItem, TRIVIA_DIFFICULTIES, type TriviaDifficulty } from '../../db/types.js';
import type { Clock } from '../../lib/time.js';
import { randomTriviaCategory, triviaCategory } from '../../modules/games/catalog.js';
import { cryptoRandom, type RandomInt, shuffled } from '../../modules/games/random.js';
import type { TriviaRequest, TriviaResult, TriviaSource } from './types.js';

export const OPENTDB_API = 'https://opentdb.com/api.php';

export interface OpenTdbOptions {
  /** Used whenever OpenTDB cannot serve a full, valid set (never throws to the caller). */
  fallback: TriviaSource;
  logger: Logger;
  now: Clock;
  timeoutMs: number;
  /** Process-wide gap between calls. OpenTDB allows one call per IP every 5 s. */
  minIntervalMs: number;
  /** Injectable for tests; defaults to the global fetch. */
  fetch?: typeof fetch;
  random?: RandomInt;
}

/**
 * With encode=base64 EVERY string field is base64, including type, difficulty and category.
 * The rate-limit answer (code 5) comes as HTTP 429 with `result` instead of `results`.
 */
const ApiBody = z.object({
  response_code: z.number().int(),
  results: z
    .array(
      z.object({
        type: z.string(),
        difficulty: z.string(),
        category: z.string(),
        question: z.string(),
        correct_answer: z.string(),
        incorrect_answers: z.array(z.string()),
      }),
    )
    .optional(),
});
type ApiQuestion = NonNullable<z.infer<typeof ApiBody>['results']>[number];

// biome-ignore lint/suspicious/noControlCharactersInRegex: stripping control characters is the point
const CONTROL_CHARS = /[\u0000-\u001f\u007f]/g;

export function decodeBase64Field(s: string): string {
  return Buffer.from(s, 'base64').toString('utf8').normalize('NFC').replace(CONTROL_CHARS, '').trim();
}

/** OpenTDB labels some categories "Entertainment: Film" or "Science: Computers"; keep the short name. */
export function shortCategoryLabel(label: string): string {
  return label.replace(/^(Entertainment|Science):\s*/, '');
}

/** Decodes and validates one question; null when it is not a usable 4-choice question. */
export function toQuizItem(q: ApiQuestion, random: RandomInt = cryptoRandom): QuizItem | null {
  const type = decodeBase64Field(q.type);
  const difficulty = decodeBase64Field(q.difficulty) as TriviaDifficulty;
  const text = decodeBase64Field(q.question);
  const correct = decodeBase64Field(q.correct_answer);
  const wrong = q.incorrect_answers.map(decodeBase64Field);
  const all = [correct, ...wrong];
  if (type !== 'multiple' || !TRIVIA_DIFFICULTIES.includes(difficulty)) return null;
  if (text.length < 1 || text.length > 300) return null;
  if (wrong.length !== 3 || all.some((c) => c.length < 1 || c.length > 120)) return null;
  if (new Set(all).size !== 4) return null;
  const choices = shuffled(all, random);
  return {
    id: `otdb_${createHash('sha256').update(text).digest('hex').slice(0, 16)}`,
    text,
    choices,
    correct: choices.indexOf(correct),
    category: shortCategoryLabel(decodeBase64Field(q.category)) || null,
    difficulty,
  };
}

/**
 * Trivia questions from Open Trivia Database (opentdb.com, CC BY-SA 4.0), fetched server-side only:
 * the phones never call it, and the request carries only a category and a difficulty (no user data,
 * no session token). Session tokens expire after 6 h idle, which for one couple means almost
 * always, so repeats are avoided with our own `exclude` set instead.
 *
 * It never waits and never fails a game: a timeout, network error, rate limit, bad answer, or a
 * call within `minIntervalMs` of the previous one all fall back to the built-in set, and only the
 * reason is logged (never question text).
 */
export class OpenTdbTrivia implements TriviaSource {
  private lastCallAt = Number.NEGATIVE_INFINITY;
  private readonly fetchImpl: typeof fetch;
  private readonly random: RandomInt;

  constructor(private readonly opts: OpenTdbOptions) {
    this.fetchImpl = opts.fetch ?? globalThis.fetch.bind(globalThis);
    this.random = opts.random ?? cryptoRandom;
  }

  async pick(req: TriviaRequest): Promise<TriviaResult> {
    const started = this.opts.now().getTime();
    if (started - this.lastCallAt < this.opts.minIntervalMs) return this.fallback(req, 'throttled');
    // Claimed before the call so concurrent duels in this process are throttled too.
    this.lastCallAt = started;

    // "Any" becomes a random CURATED category: a call without one would mix in all 24 OpenTDB
    // categories, including the ones left out on purpose.
    const category =
      (req.category !== null ? triviaCategory(req.category) : null) ?? randomTriviaCategory(this.random);
    // A category AND a difficulty can leave fewer than 10 multiple-choice questions (Animals has 21
    // hard ones in all, boolean included), and OpenTDB then answers code 1 with nothing. Asking for
    // exactly what we need avoids that; otherwise ask for 10 to have room to skip recent repeats.
    const amount = req.difficulty !== null ? req.count : Math.max(10, req.count);
    const url = new URL(OPENTDB_API);
    url.searchParams.set('amount', String(amount));
    url.searchParams.set('type', 'multiple');
    url.searchParams.set('encode', 'base64');
    url.searchParams.set('category', String(category.id));
    if (req.difficulty !== null) url.searchParams.set('difficulty', req.difficulty);

    let res: Response;
    try {
      res = await this.fetchImpl(url, {
        headers: { accept: 'application/json' },
        signal: AbortSignal.timeout(this.opts.timeoutMs),
      });
    } catch (err) {
      const name = err instanceof Error ? err.name : '';
      return this.fallback(req, name === 'TimeoutError' || name === 'AbortError' ? 'timeout' : 'network');
    }

    // Read the body even when the status is not OK: the rate limit is HTTP 429 with
    // {"response_code":5}, and the logged reason should say code_5 rather than http_429.
    let body: z.infer<typeof ApiBody> | null = null;
    try {
      const parsed = ApiBody.safeParse(JSON.parse(await res.text()));
      if (parsed.success) body = parsed.data;
    } catch {
      body = null;
    }
    if (body && body.response_code !== 0) return this.fallback(req, `code_${body.response_code}`);
    if (!res.ok) return this.fallback(req, `http_${res.status}`);
    if (!body) return this.fallback(req, 'bad_body');

    const valid = (body.results ?? [])
      .map((q) => toQuizItem(q, this.random))
      .filter((q): q is QuizItem => q !== null);
    const unique = [...new Map(valid.map((q) => [q.id, q])).values()];
    if (unique.length < req.count) return this.fallback(req, 'short');
    // Fresh questions first; recently played ones only to fill up.
    const fresh = unique.filter((q) => !req.exclude.has(q.id));
    const stale = unique.filter((q) => req.exclude.has(q.id));
    return { source: 'opentdb', category, items: [...fresh, ...stale].slice(0, req.count) };
  }

  private async fallback(req: TriviaRequest, reason: string): Promise<TriviaResult> {
    this.opts.logger.info({ provider: 'opentdb', reason }, 'trivia: using the built-in question set');
    return this.opts.fallback.pick(req);
  }
}
