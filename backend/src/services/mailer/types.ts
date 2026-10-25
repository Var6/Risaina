export interface PasswordResetMail {
  to: string;
  resetToken: string;
  resetUrl: string;
  expiresAt: Date;
}

export interface EmailVerificationMail {
  to: string;
  displayName: string;
  /** 6-digit numeric code. Never logged outside the development console mailer. */
  code: string;
  expiresAt: Date;
}

export interface Mailer {
  readonly provider: 'console' | 'resend' | 'capturing';
  sendPasswordReset(mail: PasswordResetMail): Promise<void>;
  sendEmailVerification(mail: EmailVerificationMail): Promise<void>;
}

/** Thrown by mailer drivers. The message never contains recipients, codes or links. */
export class MailerError extends Error {
  override name = 'MailerError';
}
