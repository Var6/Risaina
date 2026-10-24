export interface PasswordResetMail {
  to: string;
  resetToken: string;
  resetUrl: string;
  expiresAt: Date;
}

export interface Mailer {
  readonly provider: 'console' | 'capturing';
  sendPasswordReset(mail: PasswordResetMail): Promise<void>;
}
