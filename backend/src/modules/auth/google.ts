import { createRemoteJWKSet, type JWTVerifyGetKey, jwtVerify } from 'jose';
import { errors } from '../../lib/errors.js';
import type { Clock } from '../../lib/time.js';

export const GOOGLE_JWKS_URL = 'https://www.googleapis.com/oauth2/v3/certs';
export const GOOGLE_ISSUERS = ['accounts.google.com', 'https://accounts.google.com'];

/** The subset of a verified Google ID token the backend uses. */
export interface GoogleIdentity {
  sub: string;
  email: string; // trimmed + lowercased
  givenName: string | null;
  name: string | null;
}

export interface GoogleTokenVerifier {
  /** Resolves the identity or throws `AppError` 401 (`unauthorized`). Never returns unverified emails. */
  verify(idToken: string): Promise<GoogleIdentity>;
}

export interface GoogleVerifierOptions {
  /** Accepted `aud` values: the Google OAuth *Web* client ID(s) the Android app uses as serverClientId. */
  clientIds: string[];
  /** Key resolver. Production: Google's remote JWKS. Tests: `createLocalJWKSet` over a local key pair. */
  keys?: JWTVerifyGetKey;
  now: Clock;
}

const INVALID = 'Invalid Google ID token';

export function createGoogleVerifier(opts: GoogleVerifierOptions): GoogleTokenVerifier {
  if (opts.clientIds.length === 0)
    throw new Error('createGoogleVerifier: at least one client id is required');
  // createRemoteJWKSet fetches lazily and caches/rotates keys by `kid`; nothing happens at construction.
  const keys = opts.keys ?? createRemoteJWKSet(new URL(GOOGLE_JWKS_URL));
  return {
    async verify(idToken: string): Promise<GoogleIdentity> {
      let payload: Record<string, unknown>;
      try {
        ({ payload } = await jwtVerify(idToken, keys, {
          algorithms: ['RS256'],
          issuer: GOOGLE_ISSUERS,
          audience: opts.clientIds,
          currentDate: opts.now(),
          clockTolerance: 30,
          requiredClaims: ['sub', 'exp', 'iat'],
        }));
      } catch {
        // Bad signature, wrong audience/issuer, expired, malformed, JWKS unreachable: all one answer.
        throw errors.unauthorized(INVALID);
      }
      const sub = payload.sub;
      const email = payload.email;
      if (typeof sub !== 'string' || sub.length === 0 || sub.length > 255) throw errors.unauthorized(INVALID);
      if (typeof email !== 'string') throw errors.unauthorized(INVALID);
      if (payload.email_verified !== true) {
        throw errors.unauthorized('Your Google account email is not verified');
      }
      const normalised = email.trim().toLowerCase();
      if (normalised.length < 3 || normalised.length > 254 || !normalised.includes('@')) {
        throw errors.unauthorized(INVALID);
      }
      return {
        sub,
        email: normalised,
        givenName: typeof payload.given_name === 'string' ? payload.given_name : null,
        name: typeof payload.name === 'string' ? payload.name : null,
      };
    },
  };
}
