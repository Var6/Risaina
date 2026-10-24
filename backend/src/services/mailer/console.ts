import type { Logger } from 'pino';
import type { Mailer, PasswordResetMail } from './types.js';

/**
 * The only mailer that exists today (no email provider is configured — see COUNCIL_DECISIONS
 * O-01). In production mode it logs a single non-sensitive line; in development it additionally
 * prints the reset link to stdout so the flow can be exercised by hand.
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
}
