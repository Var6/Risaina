import type { Logger } from 'pino';
import type { EmailVerificationMail, Mailer, PasswordResetMail } from './types.js';

/**
 * Development / fallback mailer. It always logs a single non-sensitive line (no recipient, code or
 * link). In development (`devMode`) it additionally prints the code or reset link to stdout so the
 * flows can be exercised by hand; never in production.
 */
export class ConsoleMailer implements Mailer {
  readonly provider = 'console' as const;

  constructor(
    private readonly logger: Logger,
    private readonly devMode: boolean,
  ) {}

  async sendPasswordReset(mail: PasswordResetMail): Promise<void> {
    this.logger.info(
      { kind: 'password_reset', expiresAt: mail.expiresAt.toISOString() },
      'password reset email queued (console mailer)',
    );
    if (this.devMode) {
      // Development only: the token is intentionally shown so developers can complete the flow.
      process.stdout.write(
        `\n[console-mailer] Password reset link (development only):\n  ${mail.resetUrl}\n\n`,
      );
    }
  }

  async sendEmailVerification(mail: EmailVerificationMail): Promise<void> {
    this.logger.info(
      { kind: 'email_verification', expiresAt: mail.expiresAt.toISOString() },
      'email verification code queued (console mailer)',
    );
    if (this.devMode) {
      process.stdout.write(`\n[console-mailer] Email verification code (development only): ${mail.code}\n\n`);
    }
  }
}
