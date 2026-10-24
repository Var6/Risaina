import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { API, createTestApp, type TestApp } from './helpers/app.js';

let t: TestApp;

beforeAll(async () => {
  t = await createTestApp({ envOverrides: { RATE_LIMIT_AUTH_MAX: '3', RATE_LIMIT_AUTH_WINDOW_MS: '60000' } });
});
afterAll(async () => {
  await t.close();
});

describe('rate limiting', () => {
  it('login returns 429 rate_limited with Retry-After after the auth limit', async () => {
    const statuses: number[] = [];
    let last: Awaited<ReturnType<typeof t.app.inject>> | null = null;
    for (let i = 0; i < 4; i++) {
      last = await t.app.inject({
        method: 'POST',
        url: `${API}/auth/login`,
        payload: { email: 'nobody@example.test', password: 'whatever-password' },
      });
      statuses.push(last.statusCode);
    }
    expect(statuses.slice(0, 3)).toEqual([401, 401, 401]);
    expect(statuses[3]).toBe(429);
    expect(last!.json()).toEqual({ error: { code: 'rate_limited', message: expect.any(String) } });
    expect(last!.headers['retry-after']).toBeDefined();
  });

  it('health is not rate limited', async () => {
    for (let i = 0; i < 5; i++) {
      const res = await t.app.inject({ method: 'GET', url: `${API}/health` });
      expect(res.statusCode).toBe(200);
    }
  });
});
