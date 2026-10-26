import type { Logger } from 'pino';
import { describe, expect, it } from 'vitest';
import { TRIVIA_CATEGORY_IDS } from '../src/modules/games/catalog.js';
import {
  decodeBase64Field,
  OpenTdbTrivia,
  shortCategoryLabel,
  toQuizItem,
} from '../src/services/trivia/opentdb.js';
import type { TriviaRequest, TriviaResult, TriviaSource } from '../src/services/trivia/types.js';

const b64 = (s: string) => Buffer.from(s, 'utf8').toString('base64');

function question(i: number, over: Partial<Record<string, unknown>> = {}) {
  return {
    type: b64('multiple'),
    difficulty: b64('medium'),
    category: b64('Entertainment: Film'),
    question: b64(`Question number ${i}?`),
    correct_answer: b64(`Right ${i}`),
    incorrect_answers: [b64(`Wrong ${i}a`), b64(`Wrong ${i}b`), b64(`Wrong ${i}c`)],
    ...over,
  };
}

const okBody = (n: number) =>
  JSON.stringify({ response_code: 0, results: Array.from({ length: n }, (_, i) => question(i)) });

class StubFallback implements TriviaSource {
  calls = 0;
  async pick(req: TriviaRequest): Promise<TriviaResult> {
    this.calls++;
    return { source: 'builtin', category: null, items: Array(req.count).fill(null) };
  }
}

function setup(
  respond: (url: URL, init: RequestInit) => Promise<Response>,
  opts: { minIntervalMs?: number } = {},
) {
  let nowMs = 1_000_000;
  const reasons: string[] = [];
  const urls: URL[] = [];
  const fallback = new StubFallback();
  const logger = { info: (obj: { reason: string }) => reasons.push(obj.reason) } as unknown as Logger;
  const src = new OpenTdbTrivia({
    fallback,
    logger,
    now: () => new Date(nowMs),
    timeoutMs: 80,
    minIntervalMs: opts.minIntervalMs ?? 5500,
    fetch: (async (input: URL, init: RequestInit) => {
      urls.push(new URL(input));
      return respond(new URL(input), init);
    }) as unknown as typeof fetch,
  });
  return {
    src,
    reasons,
    urls,
    fallback,
    advance: (ms: number) => {
      nowMs += ms;
    },
  };
}

const req = (over: Partial<TriviaRequest> = {}): TriviaRequest => ({
  count: 5,
  category: 11,
  difficulty: null,
  exclude: new Set(),
  ...over,
});

describe('Open Trivia DB client', () => {
  it('decodes base64 (UTF-8, NFC), strips control characters and category prefixes', () => {
    expect(decodeBase64Field('UG9rw6ltb24=')).toBe('Pokémon');
    expect(decodeBase64Field(b64('  Tab\there\u0007  '))).toBe('Tabhere');
    expect(shortCategoryLabel('Entertainment: Video Games')).toBe('Video Games');
    expect(shortCategoryLabel('Science: Computers')).toBe('Computers');
    expect(shortCategoryLabel('Science & Nature')).toBe('Science & Nature');
  });

  it('a valid answer: 5 questions, shuffled choices with the right one marked, opentdb source', async () => {
    const s = setup(async () => new Response(okBody(10), { status: 200 }));
    const r = await s.src.pick(req());
    expect(r.source).toBe('opentdb');
    expect(r.category).toEqual({ id: 11, name: 'Film' });
    expect(r.items).toHaveLength(5);
    for (const it of r.items) {
      const n = it.text!.match(/(\d+)/)![1];
      expect(it.choices).toHaveLength(4);
      expect(it.choices[it.correct!]).toBe(`Right ${n}`);
      expect(it.category).toBe('Film');
      expect(it.difficulty).toBe('medium');
      expect(it.id).toMatch(/^otdb_[0-9a-f]{16}$/);
    }
    expect(s.fallback.calls).toBe(0);
  });

  it('the URL: amount 10 (or exactly 5 with a difficulty), multiple choice, base64, a category, no token', async () => {
    const s = setup(async () => new Response(okBody(10), { status: 200 }), { minIntervalMs: 0 });
    await s.src.pick(req());
    await s.src.pick(req({ difficulty: 'hard', category: 27 }));
    await s.src.pick(req({ category: null }));
    const [plain, narrow, any] = s.urls.map((u) => Object.fromEntries(u.searchParams));
    expect(s.urls[0]!.origin + s.urls[0]!.pathname).toBe('https://opentdb.com/api.php');
    expect(plain).toEqual({ amount: '10', type: 'multiple', encode: 'base64', category: '11' });
    expect(narrow).toEqual({
      amount: '5',
      type: 'multiple',
      encode: 'base64',
      category: '27',
      difficulty: 'hard',
    });
    // "Any" never asks OpenTDB for all categories: it picks a curated one.
    expect((TRIVIA_CATEGORY_IDS as readonly number[]).includes(Number(any!.category))).toBe(true);
    for (const u of s.urls) expect(u.searchParams.has('token')).toBe(false);
  });

  it('prefers questions not played recently', async () => {
    const s = setup(async () => new Response(okBody(10), { status: 200 }));
    const first = await s.src.pick(req());
    s.advance(10_000);
    const second = await s.src.pick(req({ exclude: new Set(first.items.map((i) => i.id)) }));
    expect(second.items.some((i) => first.items.some((j) => j.id === i.id))).toBe(false);
  });

  it('drops invalid questions; too few valid ones fall back', async () => {
    const results = [
      question(1),
      question(2, { type: b64('boolean') }),
      question(3, { incorrect_answers: [b64('a'), b64('b')] }),
      question(4, { correct_answer: b64('Wrong 4a') }), // duplicate choice
      question(5, { difficulty: b64('insane') }),
      question(6, { question: b64('') }),
    ];
    for (const q of results.slice(1)) expect(toQuizItem(q)).toBeNull();
    expect(toQuizItem(results[0]!)).not.toBeNull();
    const s = setup(async () => new Response(JSON.stringify({ response_code: 0, results }), { status: 200 }));
    const r = await s.src.pick(req());
    expect(r.source).toBe('builtin');
    expect(s.reasons).toEqual(['short']);
  });

  it('rate limit: HTTP 429 with {"response_code":5,"result":[]} falls back, logged as code_5', async () => {
    const s = setup(
      async () =>
        new Response(JSON.stringify({ response_code: 5, result: [] }), {
          status: 429,
          headers: { 'content-type': 'application/json' },
        }),
    );
    expect((await s.src.pick(req())).source).toBe('builtin');
    expect(s.reasons).toEqual(['code_5']);
  });

  it('falls back on every other failure, with the reason logged', async () => {
    const cases: [string, (url: URL, init: RequestInit) => Promise<Response>][] = [
      ['code_5', async () => new Response(JSON.stringify({ response_code: 5, result: [] }), { status: 200 })],
      [
        'code_1',
        async () => new Response(JSON.stringify({ response_code: 1, results: [] }), { status: 200 }),
      ],
      [
        'code_2',
        async () => new Response(JSON.stringify({ response_code: 2, results: [] }), { status: 200 }),
      ],
      [
        'code_3',
        async () => new Response(JSON.stringify({ response_code: 3, results: [] }), { status: 200 }),
      ],
      [
        'code_4',
        async () => new Response(JSON.stringify({ response_code: 4, results: [] }), { status: 200 }),
      ],
      ['http_500', async () => new Response('oops', { status: 500 })],
      ['bad_body', async () => new Response('<html>not json</html>', { status: 200 })],
      [
        'network',
        async () => {
          throw new TypeError('fetch failed');
        },
      ],
      [
        'timeout',
        (_url, init) =>
          new Promise<Response>((_resolve, reject) => {
            init.signal?.addEventListener('abort', () => reject(init.signal?.reason));
          }),
      ],
    ];
    for (const [reason, respond] of cases) {
      const s = setup(respond);
      const r = await s.src.pick(req());
      expect(r.source, reason).toBe('builtin');
      expect(r.items, reason).toHaveLength(5);
      expect(s.reasons, reason).toEqual([reason]);
    }
  });

  it('throttles: a second call within the interval uses the built-in set without calling OpenTDB', async () => {
    const s = setup(async () => new Response(okBody(10), { status: 200 }));
    expect((await s.src.pick(req())).source).toBe('opentdb');
    s.advance(2000);
    expect((await s.src.pick(req())).source).toBe('builtin');
    expect(s.urls).toHaveLength(1);
    expect(s.reasons).toEqual(['throttled']);
    s.advance(4000);
    expect((await s.src.pick(req())).source).toBe('opentdb');
    expect(s.urls).toHaveLength(2);
  });
});
