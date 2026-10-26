import type { Logger } from 'pino';
import type { WebSocket } from 'ws';
import type { EventDto } from '../../modules/shared/dto.js';
import type { LiveLocationDto } from '../../modules/shared/schemas.js';

export interface HubConnection {
  id: string;
  socket: WebSocket;
  userId: string;
  sessionId: string;
  coupleId: string | null;
  lastSeen: number;
  pending: number; // sends awaiting the ws write callback
}

export type ServerMessage =
  | { type: 'ready'; coupleId: string | null; latestSeq: number }
  | { type: 'event'; event: EventDto }
  | { type: 'synced' }
  | { type: 'revoked' }
  | { type: 'error'; code: string; message: string }
  // safety: a live location fix for the viewer only. Fire-and-forget: never stored or replayed
  | { type: 'location'; location: LiveLocationDto };

export const WS_CLOSE = {
  UNAUTHORIZED: 4401,
  REVOKED: 4403,
  BAD_MESSAGE: 4400,
  OVERLOADED: 4429,
} as const;

/**
 * In-process WebSocket hub (single backend instance by design — see D-04). Tracks connections by
 * couple and by user, fans out events, and enforces a bounded per-socket send queue.
 */
export class WsHub {
  private readonly byCouple = new Map<string, Set<HubConnection>>();
  private readonly byUser = new Map<string, Set<HubConnection>>();
  private readonly all = new Set<HubConnection>();

  constructor(
    private readonly maxPendingSends: number,
    private readonly logger: Logger,
  ) {}

  add(conn: HubConnection): void {
    this.all.add(conn);
    addTo(this.byUser, conn.userId, conn);
    if (conn.coupleId) addTo(this.byCouple, conn.coupleId, conn);
  }

  remove(conn: HubConnection): void {
    this.all.delete(conn);
    removeFrom(this.byUser, conn.userId, conn);
    if (conn.coupleId) removeFrom(this.byCouple, conn.coupleId, conn);
  }

  /** Re-bind a connection to a couple (e.g. an unpaired socket whose user just got paired). */
  bindCouple(conn: HubConnection, coupleId: string): void {
    if (conn.coupleId === coupleId) return;
    if (conn.coupleId) removeFrom(this.byCouple, conn.coupleId, conn);
    conn.coupleId = coupleId;
    addTo(this.byCouple, coupleId, conn);
  }

  /** Deliver an event to every socket of the couple and to member sockets not yet bound. */
  publish(event: EventDto, memberIds: string[]): void {
    const targets = new Set<HubConnection>(this.byCouple.get(event.coupleId) ?? []);
    for (const uid of memberIds) {
      for (const conn of this.byUser.get(uid) ?? []) {
        if (conn.coupleId === null) this.bindCouple(conn, event.coupleId);
        if (conn.coupleId === event.coupleId) targets.add(conn);
      }
    }
    for (const conn of targets) this.send(conn, { type: 'event', event });
  }

  /**
   * Deliver a frame to one user's sockets bound to `coupleId` (live location goes to the viewer
   * only, never to the sharer's own devices). Returns how many sockets it was written to.
   */
  sendToUser(userId: string, coupleId: string, msg: ServerMessage): number {
    let n = 0;
    for (const conn of this.byUser.get(userId) ?? []) {
      if (conn.coupleId === coupleId && this.send(conn, msg)) n++;
    }
    return n;
  }

  send(conn: HubConnection, msg: ServerMessage): boolean {
    const socket = conn.socket;
    if (socket.readyState !== socket.OPEN) return false;
    if (conn.pending >= this.maxPendingSends) {
      this.logger.warn({ connId: conn.id }, 'ws send queue overflow; dropping connection');
      this.close(conn, WS_CLOSE.OVERLOADED, 'send queue overflow');
      return false;
    }
    conn.pending++;
    socket.send(JSON.stringify(msg), (err) => {
      conn.pending--;
      if (err) this.logger.debug({ connId: conn.id, err: err.message }, 'ws send failed');
    });
    return true;
  }

  close(conn: HubConnection, code: number, reason: string): void {
    try {
      if (conn.socket.readyState === conn.socket.OPEN || conn.socket.readyState === conn.socket.CONNECTING) {
        conn.socket.close(code, reason);
      }
    } catch (err) {
      this.logger.debug({ connId: conn.id, err: (err as Error).message }, 'ws close failed');
    }
    this.remove(conn);
  }

  private revoke(conns: Iterable<HubConnection>, reason: string): void {
    for (const conn of [...conns]) {
      this.send(conn, { type: 'revoked' });
      this.close(conn, WS_CLOSE.REVOKED, reason);
    }
  }

  revokeSession(sessionId: string): void {
    this.revoke(
      [...this.all].filter((c) => c.sessionId === sessionId),
      'session revoked',
    );
  }

  revokeUser(userId: string): void {
    this.revoke(this.byUser.get(userId) ?? [], 'user revoked');
  }

  revokeCouple(coupleId: string): void {
    this.revoke(this.byCouple.get(coupleId) ?? [], 'couple ended');
  }

  closeAll(code = 1001, reason = 'server shutting down'): void {
    for (const conn of [...this.all]) this.close(conn, code, reason);
  }

  stats(): { connections: number; couples: number; users: number } {
    return { connections: this.all.size, couples: this.byCouple.size, users: this.byUser.size };
  }
}

function addTo<T>(map: Map<string, Set<T>>, key: string, value: T): void {
  const set = map.get(key);
  if (set) set.add(value);
  else map.set(key, new Set([value]));
}

function removeFrom<T>(map: Map<string, Set<T>>, key: string, value: T): void {
  const set = map.get(key);
  if (!set) return;
  set.delete(value);
  if (set.size === 0) map.delete(key);
}
