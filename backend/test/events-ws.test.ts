import { randomUUID } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import {
  API,
  bearer,
  createTestApp,
  pair,
  register,
  type Session,
  sendPing,
  type TestApp,
} from './helpers/app.js';

let t: TestApp;
let a: Session;
let b: Session;
let coupleId: string;
let wsUrl: string;

beforeAll(async () => {
  t = await createTestApp({ envOverrides: { PING_COOLDOWN_SECONDS: '0' } });
  a = await register(t.app, 'Ada');
  b = await register(t.app, 'Bo');
  coupleId = await pair(t.app, a, b);
  await t.app.listen({ host: '127.0.0.1', port: 0 });
  const addr = t.app.server.address() as AddressInfo;
  wsUrl = `ws://127.0.0.1:${addr.port}${API}/ws`;
});
afterAll(async () => {
  await t.close();
});

type Msg = Record<string, unknown> & { type: string };

function openSocket(): Promise<{
  ws: WebSocket;
  next: () => Promise<Msg>;
  closed: Promise<{ code: number; reason: string }>;
}> {
  const ws = new WebSocket(wsUrl);
  const queue: Msg[] = [];
  const waiters: ((m: Msg) => void)[] = [];
  ws.on('message', (raw) => {
    const m = JSON.parse(raw.toString()) as Msg;
    const w = waiters.shift();
    if (w) w(m);
    else queue.push(m);
  });
  const closed = new Promise<{ code: number; reason: string }>((resolve) => {
    ws.on('close', (code, reason) => resolve({ code, reason: reason.toString() }));
  });
  const next = () =>
    new Promise<Msg>((resolve, reject) => {
      const q = queue.shift();
      if (q) return resolve(q);
      const timer = setTimeout(() => reject(new Error('timed out waiting for ws message')), 5000);
      waiters.push((m) => {
        clearTimeout(timer);
        resolve(m);
      });
    });
  return new Promise((resolve, reject) => {
    ws.once('open', () => resolve({ ws, next, closed }));
    ws.once('error', reject);
  });
}

describe('events (HTTP)', () => {
  it('seq is monotonic and unique per couple; GET /events?since returns only newer events', async () => {
    for (let i = 0; i < 3; i++) expect((await sendPing(t.app, a)).statusCode).toBe(201);
    const all = await t.app.inject({ method: 'GET', url: `${API}/events?since=0`, headers: bearer(b) });
    expect(all.statusCode).toBe(200);
    const seqs = all.json().items.map((e: { seq: number }) => e.seq);
    expect(seqs).toEqual(seqs.map((_: number, i: number) => i + 1)); // 1..n, dense and strictly increasing
    expect(all.json().latestSeq).toBe(seqs[seqs.length - 1]);
    expect(new Set(seqs).size).toBe(seqs.length);

    const since = seqs[1];
    const newer = await t.app.inject({
      method: 'GET',
      url: `${API}/events?since=${since}`,
      headers: bearer(b),
    });
    expect(newer.json().items.map((e: { seq: number }) => e.seq)).toEqual(
      seqs.filter((s: number) => s > since),
    );
    expect(newer.json().items.every((e: { type: string }) => e.type === 'ping.created')).toBe(true);

    // unique index on (coupleId, seq) rejects duplicates
    await expect(
      t.db.events.insertOne({
        _id: new ObjectId(),
        coupleId: new ObjectId(coupleId),
        seq: 1,
        type: 'ping.created',
        actorId: new ObjectId(a.user.id),
        payload: {},
        createdAt: new Date(),
        push: {
          state: 'pending',
          attempts: 0,
          leaseUntil: null,
          lastError: null,
          skipReason: null,
          sentAt: null,
        },
      }),
    ).rejects.toMatchObject({ code: 11000 });

    // concurrent emits still allocate distinct seqs
    const results = await Promise.all(Array.from({ length: 10 }, () => sendPing(t.app, b)));
    expect(results.every((r) => r.statusCode === 201)).toBe(true);
    const after = await t.app.inject({ method: 'GET', url: `${API}/events?since=0`, headers: bearer(a) });
    const allSeqs = after.json().items.map((e: { seq: number }) => e.seq);
    expect(new Set(allSeqs).size).toBe(allSeqs.length);
    expect(allSeqs[allSeqs.length - 1]).toBe(allSeqs.length);
  });
});

describe('websocket', () => {
  it('closes 4401 when no auth message arrives in time', async () => {
    const { closed } = await openSocket();
    const res = await closed;
    expect(res.code).toBe(4401);
  });

  it('closes 4401 on a bad token', async () => {
    const { ws, closed } = await openSocket();
    ws.send(JSON.stringify({ type: 'auth', token: 'garbage' }));
    expect((await closed).code).toBe(4401);
  });

  it('closes 4401 if the first message is not auth', async () => {
    const { ws, closed } = await openSocket();
    ws.send(JSON.stringify({ type: 'sync', since: 0 }));
    expect((await closed).code).toBe(4401);
  });

  it('authenticated: ready → sync replay → synced → live event when the partner pings', async () => {
    const latest = (
      await t.app.inject({ method: 'GET', url: `${API}/events?since=0`, headers: bearer(b) })
    ).json().latestSeq as number;
    const { ws, next } = await openSocket();
    ws.send(JSON.stringify({ type: 'auth', token: b.tokens.accessToken }));
    const ready = await next();
    expect(ready).toEqual({ type: 'ready', coupleId, latestSeq: latest });

    ws.send(JSON.stringify({ type: 'sync', since: latest - 2 }));
    const e1 = await next();
    const e2 = await next();
    const synced = await next();
    expect(e1.type).toBe('event');
    expect((e1.event as { seq: number }).seq).toBe(latest - 1);
    expect((e2.event as { seq: number }).seq).toBe(latest);
    expect(synced).toEqual({ type: 'synced' });

    const ping = await sendPing(t.app, a, { message: 'live!' });
    expect(ping.statusCode).toBe(201);
    const live = await next();
    expect(live.type).toBe('event');
    const event = live.event as {
      type: string;
      seq: number;
      coupleId: string;
      actorId: string;
      payload: { ping: { id: string } };
    };
    expect(event.type).toBe('ping.created');
    expect(event.seq).toBe(latest + 1);
    expect(event.coupleId).toBe(coupleId);
    expect(event.actorId).toBe(a.user.id);
    expect(event.payload.ping.id).toBe(ping.json().ping.id);
    ws.close();
  });

  it('a socket of another couple does not receive AB events', async () => {
    const c = await register(t.app, 'Cy');
    const d = await register(t.app, 'Di');
    await pair(t.app, c, d);
    const { ws, next } = await openSocket();
    ws.send(JSON.stringify({ type: 'auth', token: c.tokens.accessToken }));
    const ready = await next();
    expect(ready.type).toBe('ready');
    expect(ready.coupleId).not.toBe(coupleId);
    await sendPing(t.app, a);
    await expect(
      Promise.race([next(), new Promise((r) => setTimeout(() => r('nothing'), 500))]),
    ).resolves.toBe('nothing');
    ws.close();
  });

  it('sends revoked + 4403 when the session is revoked (logout) and when the couple is unlinked', async () => {
    const extra = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/login`,
      payload: {
        email: (await t.db.users.findOne({ _id: new ObjectId(b.user.id) }))!.email,
        password: 'correct-horse-battery',
      },
    });
    const s2 = extra.json();
    const { ws, next, closed } = await openSocket();
    ws.send(JSON.stringify({ type: 'auth', token: s2.tokens.accessToken }));
    expect((await next()).type).toBe('ready');
    await t.app.inject({
      method: 'POST',
      url: `${API}/auth/logout`,
      payload: { refreshToken: s2.tokens.refreshToken },
    });
    expect(await next()).toEqual({ type: 'revoked' });
    expect((await closed).code).toBe(4403);

    const c = await register(t.app, 'Eli');
    const d = await register(t.app, 'Fay');
    const cd = await pair(t.app, c, d);
    const sock = await openSocket();
    sock.ws.send(JSON.stringify({ type: 'auth', token: d.tokens.accessToken }));
    expect((await sock.next()).type).toBe('ready');
    const unlink = await t.app.inject({
      method: 'POST',
      url: `${API}/couple/unlink`,
      headers: bearer(c),
      payload: { confirm: true },
    });
    expect(unlink.statusCode).toBe(204);
    const ev = await sock.next();
    expect(ev.type).toBe('event');
    expect((ev.event as { type: string; payload: unknown }).type).toBe('couple.unlinked');
    expect((ev.event as { payload: { coupleId: string; by: string } }).payload).toEqual({
      coupleId: cd,
      endedAt: expect.any(String),
      by: c.user.id,
    });
    expect(await sock.next()).toEqual({ type: 'revoked' });
    expect((await sock.closed).code).toBe(4403);
  });

  it('an unpaired user gets ready with coupleId null and receives couple.updated once paired', async () => {
    const g = await register(t.app, 'Gus');
    const h = await register(t.app, 'Hana');
    const { ws, next } = await openSocket();
    ws.send(JSON.stringify({ type: 'auth', token: g.tokens.accessToken }));
    expect(await next()).toEqual({ type: 'ready', coupleId: null, latestSeq: 0 });
    ws.send(JSON.stringify({ type: 'sync', since: 0 }));
    expect(await next()).toEqual({ type: 'synced' });
    const newCouple = await pair(t.app, g, h);
    const ev = await next();
    expect(ev.type).toBe('event');
    expect((ev.event as { type: string; coupleId: string }).type).toBe('couple.updated');
    expect((ev.event as { coupleId: string }).coupleId).toBe(newCouple);
    ws.close();
  });

  it('message.read live event carries messageId + readAt', async () => {
    const { ws, next } = await openSocket();
    ws.send(JSON.stringify({ type: 'auth', token: a.tokens.accessToken }));
    expect((await next()).type).toBe('ready');
    const msg = await t.app.inject({
      method: 'POST',
      url: `${API}/messages`,
      headers: bearer(a),
      payload: { type: 'text', text: 'hey', idempotencyKey: randomUUID() },
    });
    const created = await next();
    expect((created.event as { type: string }).type).toBe('message.created');
    await t.app.inject({
      method: 'POST',
      url: `${API}/messages/${msg.json().message.id}/read`,
      headers: bearer(b),
    });
    const read = await next();
    expect((read.event as { type: string; payload: unknown }).type).toBe('message.read');
    expect((read.event as { payload: unknown }).payload).toEqual({
      messageId: msg.json().message.id,
      readAt: expect.any(String),
    });
    ws.close();
  });
});
