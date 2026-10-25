/**
 * Application errors map 1:1 onto the API.md error envelope:
 * `{ error: { code, message, details? } }` with an HTTP status.
 */
export type ErrorCode =
  | 'validation_failed'
  | 'unauthorized'
  | 'forbidden'
  | 'not_found'
  | 'conflict'
  | 'version_conflict'
  | 'rate_limited'
  | 'invitation_expired'
  | 'invitation_used'
  | 'already_paired'
  | 'not_paired'
  | 'media_not_ready'
  | 'payload_too_large'
  | 'unsupported_media'
  | 'google_not_configured'
  | 'verification_code_invalid'
  | 'verification_code_expired'
  | 'verification_attempts_exceeded'
  | 'internal';

export class AppError extends Error {
  override name = 'AppError';
  readonly statusCode: number;
  readonly code: ErrorCode;
  readonly details: unknown;
  readonly headers: Record<string, string>;

  constructor(
    statusCode: number,
    code: ErrorCode,
    message: string,
    options: { details?: unknown; headers?: Record<string, string> } = {},
  ) {
    super(message);
    this.statusCode = statusCode;
    this.code = code;
    this.details = options.details;
    this.headers = options.headers ?? {};
  }

  toBody(): { error: { code: ErrorCode; message: string; details?: unknown } } {
    const body: { error: { code: ErrorCode; message: string; details?: unknown } } = {
      error: { code: this.code, message: this.message },
    };
    if (this.details !== undefined) body.error.details = this.details;
    return body;
  }
}

export const errors = {
  validation: (message = 'Validation failed', details?: unknown) =>
    new AppError(400, 'validation_failed', message, { details }),
  unauthorized: (message = 'Unauthorized') => new AppError(401, 'unauthorized', message),
  forbidden: (message = 'Forbidden') => new AppError(403, 'forbidden', message),
  notFound: (what = 'Resource') => new AppError(404, 'not_found', `${what} not found`),
  conflict: (message: string) => new AppError(409, 'conflict', message),
  versionConflict: (message: string, details: unknown) =>
    new AppError(409, 'version_conflict', message, { details }),
  rateLimited: (retryAfterSeconds: number, message = 'Too many requests') =>
    new AppError(429, 'rate_limited', message, {
      headers: { 'retry-after': String(Math.max(1, Math.ceil(retryAfterSeconds))) },
    }),
  invitationExpired: (message = 'Invitation has expired') => new AppError(410, 'invitation_expired', message),
  invitationUsed: (message = 'Invitation has already been used') =>
    new AppError(409, 'invitation_used', message),
  alreadyPaired: (message = 'Already paired with a partner') => new AppError(409, 'already_paired', message),
  notPaired: (message = 'You are not paired with a partner') => new AppError(409, 'not_paired', message),
  mediaNotReady: (message = 'Media is not ready') => new AppError(409, 'media_not_ready', message),
  payloadTooLarge: (message = 'Payload too large') => new AppError(413, 'payload_too_large', message),
  unsupportedMedia: (message = 'Unsupported media type') => new AppError(415, 'unsupported_media', message),
  googleNotConfigured: (message = 'Google sign-in is not configured on this server') =>
    new AppError(503, 'google_not_configured', message),
  verificationCodeInvalid: (attemptsRemaining: number) =>
    new AppError(400, 'verification_code_invalid', 'That code is not correct', {
      details: { attemptsRemaining },
    }),
  verificationCodeExpired: (message = 'This code has expired. Request a new one.') =>
    new AppError(410, 'verification_code_expired', message),
  verificationAttemptsExceeded: (retryAfterSeconds: number) =>
    new AppError(429, 'verification_attempts_exceeded', 'Too many wrong codes. Request a new one.', {
      headers: { 'retry-after': String(Math.max(1, Math.ceil(retryAfterSeconds))) },
    }),
  internal: (message = 'Internal server error') => new AppError(500, 'internal', message),
};
