import { randomUUID } from 'node:crypto';
import { createLocalJWKSet, exportJWK, generateKeyPair, type JWTVerifyGetKey, SignJWT } from 'jose';

export const GOOGLE_TEST_CLIENT_ID = '1234567890-web.apps.googleusercontent.com';

type Key = Awaited<ReturnType<typeof generateKeyPair>>['privateKey'];

export interface GoogleTokenClaims {
  sub?: string;
  email?: string;
  email_verified?: boolean;
  given_name?: string;
  name?: string;
  aud?: string;
  iss?: string;
  /** seconds relative to `now` */
  expiresIn?: number;
  issuedAt?: Date;
}

/** A stand-in for Google's signing keys: an RS256 key pair exposed as a local JWKS. */
export class FakeGoogle {
  private constructor(
    private readonly privateKey: Key,
    private readonly otherKey: Key,
    readonly keys: JWTVerifyGetKey,
    private readonly now: () => Date,
  ) {}

  static async create(now: () => Date = () => new Date()): Promise<FakeGoogle> {
    const { publicKey, privateKey } = await generateKeyPair('RS256', { extractable: true });
    const other = await generateKeyPair('RS256', { extractable: true });
    const jwk = await exportJWK(publicKey);
    const keys = createLocalJWKSet({ keys: [{ ...jwk, kid: 'test-kid', alg: 'RS256', use: 'sig' }] });
    return new FakeGoogle(privateKey, other.privateKey, keys, now);
  }

  async token(claims: GoogleTokenClaims = {}, opts: { signWithUnknownKey?: boolean } = {}): Promise<string> {
    const iat = Math.floor((claims.issuedAt ?? this.now()).getTime() / 1000);
    const payload: Record<string, unknown> = {
      email: claims.email ?? `g-${randomUUID().slice(0, 8)}@gmail.test`,
      email_verified: claims.email_verified ?? true,
    };
    if (claims.given_name !== undefined) payload.given_name = claims.given_name;
    if (claims.name !== undefined) payload.name = claims.name;
    return new SignJWT(payload)
      .setProtectedHeader({ alg: 'RS256', kid: 'test-kid', typ: 'JWT' })
      .setIssuer(claims.iss ?? 'https://accounts.google.com')
      .setAudience(claims.aud ?? GOOGLE_TEST_CLIENT_ID)
      .setSubject(claims.sub ?? `${Math.floor(Math.random() * 1e15)}`)
      .setIssuedAt(iat)
      .setExpirationTime(iat + (claims.expiresIn ?? 3600))
      .sign(opts.signWithUnknownKey ? this.otherKey : this.privateKey);
  }
}
