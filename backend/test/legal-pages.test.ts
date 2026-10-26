import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createTestApp, type TestApp } from './helpers/app.js';

let t: TestApp;

beforeAll(async () => {
  t = await createTestApp();
});
afterAll(async () => {
  await t.close();
});

describe('public legal pages', () => {
  for (const [url, heading] of [
    ['/privacy', 'Risaina Privacy Policy'],
    ['/delete-account', 'Delete your Risaina account'],
    ['/terms', 'Risaina Terms of Use'],
  ] as const) {
    it(`GET ${url} is a public HTML page with no template markers left`, async () => {
      const res = await t.app.inject({ method: 'GET', url });
      expect(res.statusCode).toBe(200);
      expect(res.headers['content-type']).toContain('text/html');
      expect(res.body).toContain(heading);
      expect(res.body).not.toContain('{{');
      expect(res.body).not.toMatch(/\[(DEVELOPER NAME|CONTACT EMAIL)\]/);
      expect(res.body).toContain('mailto:');
    });
  }

  it('the two pages link to each other', async () => {
    const privacy = await t.app.inject({ method: 'GET', url: '/privacy' });
    const deletion = await t.app.inject({ method: 'GET', url: '/delete-account' });
    expect(privacy.body).toContain('href="/delete-account"');
    expect(deletion.body).toContain('href="/privacy"');
  });

  it('the terms link the privacy policy, and the privacy policy links the terms', async () => {
    const terms = await t.app.inject({ method: 'GET', url: '/terms' });
    const privacy = await t.app.inject({ method: 'GET', url: '/privacy' });
    expect(terms.body).toContain('href="/privacy"');
    expect(privacy.body).toContain('href="/terms"');
  });
});
