import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { ObjectId } from 'mongodb';
import type { Collections } from '../db/collections.js';
import type { CoupleDoc, SessionDoc } from '../db/types.js';
import { errors } from '../lib/errors.js';
import type { Clock } from '../lib/time.js';
import type { TokenService } from '../modules/auth/tokens.js';

export interface AuthUser {
  id: string;
  sessionId: string;
}

declare module 'fastify' {
  interface FastifyRequest {
    user: AuthUser | null;
    couple: CoupleDoc | null;
    partnerId: string | null;
  }
  interface FastifyInstance {
    /** preValidation hook: verifies the bearer token and the backing session. */
    authenticate: (request: FastifyRequest, reply: FastifyReply) => Promise<void>;
    /** preHandler hook: loads the ACTIVE couple from the user's `activeCoupleId` (409 not_paired otherwise). */
    requireCouple: (request: FastifyRequest, reply: FastifyReply) => Promise<void>;
  }
}

export interface AuthPluginDeps {
  db: Collections;
  tokens: TokenService;
  now: Clock;
}

export function sessionIsLive(session: SessionDoc | null, now: Date): session is SessionDoc {
  return !!session && session.revokedAt === null && session.expiresAt.getTime() > now.getTime();
}

/**
 * Resolve an access token to `{ userId, sessionId }`, requiring the backing session to be live
 * (not revoked, not expired). Shared by the HTTP hook and the WebSocket auth handshake.
 */
export async function resolveAccessToken(deps: AuthPluginDeps, token: string): Promise<AuthUser | null> {
  const claims = await deps.tokens.verifyAccess(token);
  if (!claims) return null;
  const session = await deps.db.sessions.findOne({ _id: new ObjectId(claims.sessionId) });
  if (!sessionIsLive(session, deps.now())) return null;
  if (!session.userId.equals(new ObjectId(claims.userId))) return null;
  return { id: claims.userId, sessionId: claims.sessionId };
}

export function registerAuthDecorators(app: FastifyInstance, deps: AuthPluginDeps): void {
  app.decorateRequest('user', null);
  app.decorateRequest('couple', null);
  app.decorateRequest('partnerId', null);

  app.decorate('authenticate', async (request: FastifyRequest) => {
    const header = request.headers.authorization;
    if (!header?.startsWith('Bearer ')) throw errors.unauthorized('Missing bearer token');
    const token = header.slice('Bearer '.length).trim();
    if (!token) throw errors.unauthorized('Missing bearer token');
    const user = await resolveAccessToken(deps, token);
    if (!user) throw errors.unauthorized('Invalid or expired token');
    request.user = user;
  });

  app.decorate('requireCouple', async (request: FastifyRequest) => {
    const user = request.user;
    if (!user) throw errors.unauthorized();
    const userDoc = await deps.db.users.findOne(
      { _id: new ObjectId(user.id), deletedAt: null },
      { projection: { activeCoupleId: 1 } },
    );
    if (!userDoc) throw errors.unauthorized('Account no longer exists');
    if (!userDoc.activeCoupleId) throw errors.notPaired();
    const couple = await deps.db.couples.findOne({ _id: userDoc.activeCoupleId, status: 'active' });
    if (!couple) throw errors.notPaired();
    const me = new ObjectId(user.id);
    const partner = couple.memberIds.find((m) => !m.equals(me));
    if (!couple.memberIds.some((m) => m.equals(me)) || !partner) throw errors.notPaired();
    request.couple = couple;
    request.partnerId = partner.toHexString();
  });
}
