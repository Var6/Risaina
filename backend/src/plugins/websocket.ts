import { randomUUID } from 'node:crypto';
import type { FastifyPluginAsync } from 'fastify';
import { ObjectId } from 'mongodb';
import type { WebSocket } from 'ws';
import { z } from 'zod';
import type { AppContext } from '../app-context.js';
import { EventQueryService } from '../modules/events/service.js';
import { type HubConnection, WS_CLOSE } from '../services/events/hub.js';
import { resolveAccessToken } from './authenticate.js';

const AuthMessage = z.object({ type: z.literal('auth'), token: z.string().min(1).max(4096) });
const SyncMessage = z.object({ type: z.literal('sync'), since: z.number().int().min(0) });
const ClientMessage = z.discriminatedUnion('type', [AuthMessage, SyncMessage]);

const MAX_MESSAGE_BYTES = 8 * 1024;

/**
 * `/api/v1/ws` — protocol per API.md:
 *  1. client sends {type:'auth', token} within WS_AUTH_TIMEOUT_MS (default 5 s) or is closed 4401
 *  2. server replies {type:'ready', coupleId, latestSeq}   (coupleId is null for an unpaired user)
 *  3. client sends {type:'sync', since}; server streams {type:'event'} then {type:'synced'}
 *  4. live events arrive as {type:'event'}
 *  5. server pings every 30 s; 90 s without any frame ⇒ close
 *  6. {type:'revoked'} + 4403 when the couple ends or the session is revoked
 */
export const websocketRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const events = new EventQueryService(ctx);

  app.get(
    '/ws',
    { websocket: true, config: { rateLimit: false }, schema: { hide: true } },
    (socket: WebSocket, req) => {
      const connId = randomUUID();
      let conn: HubConnection | null = null;
      let closed = false;
      let heartbeat: NodeJS.Timeout | null = null;

      const authTimer = setTimeout(() => {
        if (!conn) close(WS_CLOSE.UNAUTHORIZED, 'auth timeout');
      }, ctx.env.ws.authTimeoutMs);

      function close(code: number, reason: string): void {
        if (closed) return;
        closed = true;
        clearTimeout(authTimer);
        if (heartbeat) clearInterval(heartbeat);
        if (conn) ctx.hub.close(conn, code, reason);
        else {
          try {
            socket.close(code, reason);
          } catch {
            /* already closed */
          }
        }
      }

      function touch(): void {
        if (conn) conn.lastSeen = ctx.now().getTime();
      }

      socket.on('pong', touch);
      socket.on('error', (err) => {
        req.log.debug({ connId, err: err.message }, 'ws error');
        close(1011, 'error');
      });
      socket.on('close', () => {
        closed = true;
        clearTimeout(authTimer);
        if (heartbeat) clearInterval(heartbeat);
        if (conn) ctx.hub.remove(conn);
      });

      socket.on('message', (raw, isBinary) => {
        touch();
        if (isBinary || (Buffer.isBuffer(raw) && raw.byteLength > MAX_MESSAGE_BYTES)) {
          close(WS_CLOSE.BAD_MESSAGE, 'bad message');
          return;
        }
        let parsed: z.infer<typeof ClientMessage>;
        try {
          parsed = ClientMessage.parse(JSON.parse(raw.toString()));
        } catch {
          close(conn ? WS_CLOSE.BAD_MESSAGE : WS_CLOSE.UNAUTHORIZED, 'bad message');
          return;
        }

        if (!conn) {
          if (parsed.type !== 'auth') {
            close(WS_CLOSE.UNAUTHORIZED, 'auth required');
            return;
          }
          void handleAuth(parsed.token);
          return;
        }
        if (parsed.type === 'sync') void handleSync(conn, parsed.since);
      });

      async function handleAuth(token: string): Promise<void> {
        const user = await resolveAccessToken({ db: ctx.db, tokens: ctx.tokens, now: ctx.now }, token);
        if (closed) return;
        if (!user) {
          close(WS_CLOSE.UNAUTHORIZED, 'unauthorized');
          return;
        }
        const userDoc = await ctx.db.users.findOne(
          { _id: new ObjectId(user.id), deletedAt: null },
          { projection: { activeCoupleId: 1 } },
        );
        if (closed) return;
        if (!userDoc) {
          close(WS_CLOSE.UNAUTHORIZED, 'unauthorized');
          return;
        }
        let coupleId: string | null = null;
        let latestSeq = 0;
        if (userDoc.activeCoupleId) {
          const couple = await ctx.db.couples.findOne(
            { _id: userDoc.activeCoupleId, status: 'active' },
            { projection: { eventSeq: 1 } },
          );
          if (couple) {
            coupleId = couple._id.toHexString();
            latestSeq = couple.eventSeq;
          }
        }
        if (closed) return;
        clearTimeout(authTimer);
        conn = {
          id: connId,
          socket,
          userId: user.id,
          sessionId: user.sessionId,
          coupleId,
          lastSeen: ctx.now().getTime(),
          pending: 0,
        };
        ctx.hub.add(conn);
        ctx.hub.send(conn, { type: 'ready', coupleId, latestSeq });

        heartbeat = setInterval(() => {
          if (!conn || closed) return;
          if (ctx.now().getTime() - conn.lastSeen > ctx.env.ws.idleTimeoutMs) {
            close(1001, 'idle timeout');
            return;
          }
          try {
            socket.ping();
          } catch {
            close(1011, 'ping failed');
          }
        }, ctx.env.ws.pingIntervalMs);
      }

      async function handleSync(c: HubConnection, since: number): Promise<void> {
        try {
          if (c.coupleId) {
            for await (const event of events.replay(new ObjectId(c.coupleId), since)) {
              if (closed) return;
              if (!ctx.hub.send(c, { type: 'event', event })) return;
            }
          }
          ctx.hub.send(c, { type: 'synced' });
        } catch (err) {
          req.log.error({ connId, err }, 'ws sync failed');
          close(1011, 'sync failed');
        }
      }
    },
  );
};
