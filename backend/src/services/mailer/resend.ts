import type { Logger } from 'pino';
import type { Clock } from '../../lib/time.js';
import { passwordResetEmail, type RenderedEmail, verificationEmail } from './templates.js';
import { type EmailVerificationMail, type Mailer, MailerError, type PasswordResetMail } from './types.js';

export const RESEND_ENDPOINT = 'https://api.resend.com/emails';

export interface ResendMailerOptions {
  apiKey: string;
  /** e.g. `Risaina <hello@yourdomain>` (the domain must be verified in Resend). */
  from: string;
  logger: Logger;
  now?: Clock;
  /** Injectable for tests; defaults to the global fetch. */
  fetch?: typeof fetch;
  timeoutMs?: number;
}

/**
 * Resend (https://resend.com) HTTP API driver. Logs only the kind of email, the HTTP status and
 * Resend's message id; never the recipient, code, link or response body.
 */
export class ResendMailer implements Mailer {
  readonly provider = 'resend' as const;
  private readonly fetchImpl: typeof fetch;
  private readonly now: Clock;

  constructor(private readonly opts: ResendMailerOptions) {
    this.fetchImpl = opts.fetch ?? globalThis.fetch.bind(globalThis);
    this.now = opts.now ?? (() => new Date());
  }

  async sendPasswordReset(mail: PasswordResetMail): Promise<void> {
    const rendered = passwordResetEmail({ resetUrl: mail.resetUrl, expiresAt: mail.expiresAt }, this.now());
    await this.send('password_reset', mail.to, rendered);
  }

  async sendEmailVerification(mail: EmailVerificationMail): Promise<void> {
    const rendered = verificationEmail(
      { code: mail.code, displayName: mail.displayName, expiresAt: mail.expiresAt },
      this.now(),
    );
    await this.send('email_verification', mail.to, rendered);
  }

  private async send(kind: string, to: string, email: RenderedEmail): Promise<void> {
    let res: Response;
    try {
      res = await this.fetchImpl(RESEND_ENDPOINT, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${this.opts.apiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: this.opts.from,
          to: [to],
          subject: email.subject,
          html: email.html,
          text: email.text,
        }),
        signal: AbortSignal.timeout(this.opts.timeoutMs ?? 10_000),
      });
    } catch (err) {
      const name = err instanceof Error ? err.name : 'unknown';
      throw new MailerError(`resend request failed (${name})`);
    }
    if (!res.ok) {
      // The body may echo the recipient; it is deliberately not read into the error or logs.
      await res.body?.cancel().catch(() => undefined);
      throw new MailerError(`resend responded with HTTP ${res.status}`);
    }
    let id: string | undefined;
    try {
      const body = (await res.json()) as { id?: unknown };
      if (typeof body.id === 'string') id = body.id;
    } catch {
      // a 2xx without JSON is still a success
    }
    this.opts.logger.info({ kind, provider: 'resend', status: res.status, id }, 'email sent');
  }
}
