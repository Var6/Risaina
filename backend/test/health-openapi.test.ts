import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { loadEnv } from '../src/config/env.js';
import { API, createTestApp, type TestApp } from './helpers/app.js';

let t: TestApp;

beforeAll(async () => {
  t = await createTestApp();
});
afterAll(async () => {
  await t.close();
});

describe('health & docs', () => {
  it('GET /health reports mongo/storage/push; /health/ready is 200', async () => {
    const res = await t.app.inject({ method: 'GET', url: `${API}/health` });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({
      status: 'ok',
      version: 'test',
      uptimeSeconds: expect.any(Number),
      checks: {
        mongo: { status: 'ok' },
        storage: { status: 'ok', driver: 'local' },
        push: { status: 'disabled', provider: 'disabled' },
      },
    });
    const ready = await t.app.inject({ method: 'GET', url: `${API}/health/ready` });
    expect(ready.statusCode).toBe(200);
  });

  it('serves the generated OpenAPI document with every API.md route', async () => {
    const res = await t.app.inject({ method: 'GET', url: '/docs/json' });
    expect(res.statusCode).toBe(200);
    const doc = res.json();
    expect(doc.openapi).toBe('3.1.0');
    const paths = Object.keys(doc.paths);
    for (const p of [
      '/api/v1/auth/register',
      '/api/v1/auth/login',
      '/api/v1/auth/refresh',
      '/api/v1/auth/logout',
      '/api/v1/auth/password/forgot',
      '/api/v1/auth/password/reset',
      '/api/v1/me',
      '/api/v1/me/preferences',
      '/api/v1/me/export',
      '/api/v1/invitations',
      '/api/v1/invitations/current',
      '/api/v1/invitations/{id}',
      '/api/v1/invitations/accept',
      '/api/v1/couple',
      '/api/v1/couple/unlink',
      '/api/v1/devices',
      '/api/v1/devices/{deviceId}',
      '/api/v1/pings',
      '/api/v1/pings/{id}/delivered',
      '/api/v1/pings/{id}/seen',
      '/api/v1/messages',
      '/api/v1/messages/{id}/read',
      '/api/v1/messages/{id}',
      '/api/v1/media/uploads',
      '/api/v1/media/{id}/complete',
      '/api/v1/media/{id}/url',
      '/api/v1/media/{id}',
      '/api/v1/memories',
      '/api/v1/memories/{id}',
      '/api/v1/milestones',
      '/api/v1/milestones/{id}',
      '/api/v1/events',
      '/api/v1/health',
      '/api/v1/health/ready',
    ]) {
      expect(paths, p).toContain(p);
    }
    expect(doc.components.securitySchemes.bearerAuth.type).toBe('http');
    expect(Object.keys(doc.components.schemas)).toEqual(
      expect.arrayContaining(['Ping', 'Message', 'Event', 'Couple', 'ErrorEnvelope']),
    );
    const ui = await t.app.inject({ method: 'GET', url: '/docs' });
    expect([200, 302]).toContain(ui.statusCode);
  });
});

describe('env validation', () => {
  const base = {
    HOST: '127.0.0.1',
    PORT: '8080',
    MONGODB_URI: 'mongodb://127.0.0.1:27017',
    MONGODB_DB: 'x',
    JWT_SECRET: 'a-very-long-secret-that-is-at-least-32-characters',
    CORS_ORIGINS: '*',
  };

  it('fails fast with a readable, value-free message', () => {
    expect(() => loadEnv({ ...base, JWT_SECRET: 'short' })).toThrowError(
      /JWT_SECRET must be at least 32 characters/,
    );
    expect(() => loadEnv({ ...base, MONGODB_URI: 'postgres://x' })).toThrowError(/MONGODB_URI/);
    try {
      loadEnv({ ...base, JWT_SECRET: 'short-secret-value' });
    } catch (err) {
      expect((err as Error).message).not.toContain('short-secret-value');
    }
  });

  it('defaults to local storage in dev, refuses it in production, and requires R2 vars for r2', () => {
    expect(loadEnv(base).storage.driver).toBe('local');
    expect(loadEnv(base).push.enabled).toBe(false);
    expect(() =>
      loadEnv({
        ...base,
        NODE_ENV: 'production',
        STORAGE_DRIVER: 'local',
        CORS_ORIGINS: 'https://app.example',
      }),
    ).toThrowError(/development-only/);
    expect(() => loadEnv({ ...base, STORAGE_DRIVER: 'r2' })).toThrowError(/R2_BUCKET is required/);
    const r2 = loadEnv({
      ...base,
      STORAGE_DRIVER: 'r2',
      R2_ACCOUNT_ID: 'a',
      R2_ACCESS_KEY_ID: 'k',
      R2_SECRET_ACCESS_KEY: 's',
      R2_BUCKET: 'b',
      R2_ENDPOINT: 'https://a.r2.cloudflarestorage.com',
    });
    expect(r2.storage.driver).toBe('r2');
    expect(() => loadEnv({ ...base, FIREBASE_SERVICE_ACCOUNT_JSON: 'not json at all' })).toThrowError(
      /FIREBASE_SERVICE_ACCOUNT_JSON/,
    );
    const sa = Buffer.from(
      JSON.stringify({
        project_id: 'p',
        client_email: 'e@p.iam',
        private_key: '-----BEGIN PRIVATE KEY-----\nx\n',
      }),
    ).toString('base64');
    expect(loadEnv({ ...base, FIREBASE_SERVICE_ACCOUNT_JSON: sa }).push.enabled).toBe(true);
  });
});
