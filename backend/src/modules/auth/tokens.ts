import { randomUUID } from 'node:crypto';
import { jwtVerify, SignJWT } from 'jose';
import type { Clock } from '../../lib/time.js';

export interface AccessClaims {
  userId: string;
  sessionId: string;
}

const ISSUER = 'risaina';
const AUDIENCE = 'risaina-app';

/** HS256 access tokens: `sub` = userId, `sid` = sessionId, short-lived (default 15 min). */
export class TokenService {
  private readonly key: Uint8Array;

  constructor(
    secret: string,
    readonly accessTtlSeconds: number,
    private readonly now: Clock,
  ) {
    this.key = new TextEncoder().encode(secret);
  }

  async signAccess(claims: AccessClaims): Promise<{ token: string; expiresAt: Date }> {
    const nowSec = Math.floor(this.now().getTime() / 1000);
    const expSec = nowSec + this.accessTtlSeconds;
    const token = await new SignJWT({ sid: claims.sessionId })
      .setProtectedHeader({ alg: 'HS256', typ: 'JWT' })
      .setSubject(claims.userId)
      .setIssuer(ISSUER)
      .setAudience(AUDIENCE)
      .setIssuedAt(nowSec)
      .setExpirationTime(expSec)
      .setJti(randomUUID())
      .sign(this.key);
    return { token, expiresAt: new Date(expSec * 1000) };
  }

  async verifyAccess(token: string): Promise<AccessClaims | null> {
    try {
      const { payload } = await jwtVerify(token, this.key, {
        algorithms: ['HS256'],
        issuer: ISSUER,
        audience: AUDIENCE,
        currentDate: this.now(),
      });
      const sid = payload.sid;
      if (typeof payload.sub !== 'string' || typeof sid !== 'string') return null;
      if (!/^[a-f0-9]{24}$/.test(payload.sub) || !/^[a-f0-9]{24}$/.test(sid)) return null;
      return { userId: payload.sub, sessionId: sid };
    } catch {
      return null;
    }
  }
}
