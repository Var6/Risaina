import { z } from 'zod';
import { Tokens, User } from '../shared/schemas.js';

function hasControlCharacters(value: string): boolean {
  for (const ch of value) {
    const code = ch.codePointAt(0) ?? 0;
    if (code < 0x20 || code === 0x7f) return true;
  }
  return false;
}

export const Email = z
  .email()
  .max(254)
  .transform((s) => s.trim().toLowerCase());
export const Password = z.string().min(10, 'password must be at least 10 characters').max(200);
export const DisplayName = z
  .string()
  .trim()
  .min(1)
  .max(60)
  .refine((s) => !hasControlCharacters(s), 'display name contains control characters');

export const RegisterBody = z.object({ email: Email, password: Password, displayName: DisplayName });
export const LoginBody = z.object({ email: Email, password: z.string().min(1).max(200) });
export const RefreshBody = z.object({ refreshToken: z.string().min(20).max(200) });
export const LogoutBody = z.object({
  refreshToken: z.string().min(20).max(200),
  deviceId: z.string().min(1).max(128).optional(),
});
export const ForgotBody = z.object({ email: Email });
export const ResetBody = z.object({ token: z.string().min(20).max(200), newPassword: Password });

export const GoogleBody = z.object({
  idToken: z.string().min(1).max(4096).meta({ description: 'Google ID token (Credential Manager)' }),
});
export const VerifyEmailBody = z.object({
  code: z
    .string()
    .trim()
    .regex(/^\d{6}$/, 'code must be 6 digits')
    .meta({ example: '042137' }),
});

export const AuthResponse = z.object({ user: User, tokens: Tokens });
export const GoogleAuthResponse = z.object({
  user: User,
  tokens: Tokens,
  isNewUser: z.boolean().meta({ description: 'True when this call created the account' }),
});
export const UserOnlyResponse = z.object({ user: User });
export const AcceptedResponse = z.object({ accepted: z.literal(true) });
export const TokensResponse = z.object({ tokens: Tokens });
