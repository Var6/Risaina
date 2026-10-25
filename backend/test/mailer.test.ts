import { Writable } from 'node:stream';
import { pino } from 'pino';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { loadEnv } from '../src/config/env.js';
import {
  ConsoleMailer,
  createMailer,
  MailerError,
  RESEND_ENDPOINT,
  ResendMailer,
  verificationEmail,
} from '../src/services/mailer/index.js';

function capturingLogger() {
  const lines: string[] = [];
  const stream = new Writable({
    write(chunk, _enc, cb) {
      lines.push(chunk.toString());
      cb();
    },
  });
  return { logger: pino({ level: 'info' }, stream), lines };
}

const NOW = new Date('2026-10-25T12:00:00.000Z');
const EXPIRES = new Date(NOW.getTime() + 30 * 60_000);

afterEach(() => {
  vi.restoreAllMocks();
});

describe('ResendMailer', () => {
  it('POSTs the verification email to the Resend API with a bearer key', async () => {
    const { logger, lines } = capturingLogger();
    const fetchMock = vi.fn(
      async (_url: string | URL | Request, _init?: RequestInit) =>
        new Response(JSON.stringify({ id: 'msg_123' }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
    );
    const mailer = new ResendMailer({
      apiKey: 're_test_key',
      from: 'Risaina <hello@risaina.test>',
      logger,
      now: () => NOW,
      fetch: fetchMock as unknown as typeof fetch,
    });
    await mailer.sendEmailVerification({
      to: 'person@example.test',
      displayName: 'Asha',
      code: '042137',
      expiresAt: EXPIRES,
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe(RESEND_ENDPOINT);
    expect(url).toBe('https://api.resend.com/emails');
    expect(init!.method).toBe('POST');
    const headers = init!.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer re_test_key');
    expect(headers['Content-Type']).toBe('application/json');
    const body = JSON.parse(init!.body as string);
    expect(Object.keys(body).sort()).toEqual(['from', 'html', 'subject', 'text', 'to']);
    expect(body.from).toBe('Risaina <hello@risaina.test>');
    expect(body.to).toEqual(['person@example.test']);
    expect(body.subject).toContain('042137');
    expect(body.text).toContain('042137');
    expect(body.text).toContain('30 minutes');
    expect(body.html).toContain('042137');
    expect(body.html).toContain('Hi Asha');

    const log = lines.join('');
    expect(log).toContain('msg_123');
    expect(log).not.toContain('person@example.test');
    expect(log).not.toContain('042137');
    expect(log).not.toContain('re_test_key');
  });

  it('sends the password reset email with the link in HTML and text', async () => {
    const { logger } = capturingLogger();
    const fetchMock = vi.fn(async () => new Response('{"id":"x"}', { status: 200 }));
    const mailer = new ResendMailer({
      apiKey: 'k',
      from: 'hello@risaina.test',
      logger,
      now: () => NOW,
      fetch: fetchMock as unknown as typeof fetch,
    });
    const resetUrl = 'https://api.risaina.test/reset-password?token=abc&x=1';
    await mailer.sendPasswordReset({ to: 'p@example.test', resetToken: 'abc', resetUrl, expiresAt: EXPIRES });
    const body = JSON.parse((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].body as string);
    expect(body.subject).toBe('Reset your Risaina password');
    expect(body.text).toContain(resetUrl);
    expect(body.html).toContain('https://api.risaina.test/reset-password?token=abc&amp;x=1');
  });

  it('turns HTTP and network failures into MailerError without leaking recipient or response body', async () => {
    const { logger, lines } = capturingLogger();
    const failing = new ResendMailer({
      apiKey: 'k',
      from: 'hello@risaina.test',
      logger,
      fetch: (async () =>
        new Response(JSON.stringify({ message: 'Invalid `to` field: victim@example.test' }), {
          status: 422,
        })) as unknown as typeof fetch,
    });
    const err = await failing
      .sendEmailVerification({
        to: 'victim@example.test',
        displayName: 'V',
        code: '999111',
        expiresAt: EXPIRES,
      })
      .catch((e: unknown) => e);
    expect(err).toBeInstanceOf(MailerError);
    expect((err as Error).message).toBe('resend responded with HTTP 422');
    expect(lines.join('')).not.toContain('victim@example.test');

    const offline = new ResendMailer({
      apiKey: 'k',
      from: 'hello@risaina.test',
      logger,
      fetch: (async () => {
        throw new TypeError('fetch failed');
      }) as unknown as typeof fetch,
    });
    await expect(
      offline.sendPasswordReset({
        to: 'a@b.test',
        resetToken: 't',
        resetUrl: 'https://x',
        expiresAt: EXPIRES,
      }),
    ).rejects.toThrowError(/resend request failed \(TypeError\)/);
  });
});

describe('templates', () => {
  it('escapes the display name', () => {
    const mail = verificationEmail({ code: '123456', displayName: '<b>Eve</b>', expiresAt: EXPIRES }, NOW);
    expect(mail.html).toContain('&lt;b&gt;Eve&lt;/b&gt;');
    expect(mail.html).not.toContain('<b>Eve</b>');
  });
});

describe('ConsoleMailer', () => {
  it('never prints codes or links in production mode and logs no recipient', async () => {
    const { logger, lines } = capturingLogger();
    const write = vi.spyOn(process.stdout, 'write').mockImplementation(() => true);
    const prod = new ConsoleMailer(logger, false);
    await prod.sendEmailVerification({
      to: 'p@example.test',
      displayName: 'P',
      code: '555123',
      expiresAt: EXPIRES,
    });
    await prod.sendPasswordReset({
      to: 'p@example.test',
      resetToken: 'tok',
      resetUrl: 'https://x/reset?token=tok',
      expiresAt: EXPIRES,
    });
    expect(write).not.toHaveBeenCalled();
    const log = lines.join('');
    expect(log).not.toContain('555123');
    expect(log).not.toContain('p@example.test');
    expect(log).not.toContain('token=tok');

    const dev = new ConsoleMailer(logger, true);
    await dev.sendEmailVerification({
      to: 'p@example.test',
      displayName: 'P',
      code: '555123',
      expiresAt: EXPIRES,
    });
    expect(write).toHaveBeenCalledWith(expect.stringContaining('555123'));
  });
});

describe('createMailer', () => {
  const base = {
    NODE_ENV: 'development',
    HOST: '127.0.0.1',
    PORT: '0',
    MONGODB_URI: 'mongodb://127.0.0.1:1',
    MONGODB_DB: 'x',
    JWT_SECRET: 'a-very-long-secret-that-is-at-least-32-characters',
    CORS_ORIGINS: '*',
  };
  it('picks the driver from MAILER', () => {
    const logger = pino({ level: 'silent' });
    expect(createMailer(loadEnv(base), logger).provider).toBe('console');
    const env = loadEnv({
      ...base,
      MAILER: 'resend',
      RESEND_API_KEY: 're_x',
      MAIL_FROM: 'Risaina <a@b.test>',
    });
    expect(createMailer(env, logger).provider).toBe('resend');
  });
});
