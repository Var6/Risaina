import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { API, bearer, createTestApp, pair, register, type TestApp } from './helpers/app.js';

let t: TestApp;

beforeAll(async () => {
  t = await createTestApp();
});
afterAll(async () => {
  await t.close();
});

async function invite(session: Awaited<ReturnType<typeof register>>) {
  const res = await t.app.inject({
    method: 'POST',
    url: `${API}/invitations`,
    headers: bearer(session),
    payload: {},
  });
  expect(res.statusCode).toBe(201);
  return res.json<{ invitation: { id: string; code: string; expiresAt: string } }>().invitation;
}

function accept(session: Awaited<ReturnType<typeof register>>, code: string) {
  return t.app.inject({
    method: 'POST',
    url: `${API}/invitations/accept`,
    headers: bearer(session),
    payload: { code },
  });
}

describe('invitations & couple', () => {
  it('creates a pending couple, accepts once, exposes couple + partner, and emits couple.updated', async () => {
    const a = await register(t.app, 'Ann');
    const b = await register(t.app, 'Ben');
    const inv = await invite(a);
    expect(inv.code).toMatch(/^[0-9A-HJKMNP-TV-Z]{10}$/);

    // code is hashed at rest and never returned by /current
    const stored = await t.db.invitations.findOne({ _id: new ObjectId(inv.id) });
    expect(stored!.codeHash).not.toBe(inv.code);
    const current = await t.app.inject({
      method: 'GET',
      url: `${API}/invitations/current`,
      headers: bearer(a),
    });
    expect(current.statusCode).toBe(200);
    expect(current.json().invitation.id).toBe(inv.id);
    expect(current.json().invitation).not.toHaveProperty('code');

    const meBefore = await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(a) });
    expect(meBefore.json().couple.status).toBe('pending');

    // lowercase + separators are normalised
    const acc = await accept(b, `${inv.code.slice(0, 5).toLowerCase()}-${inv.code.slice(5)}`);
    expect(acc.statusCode).toBe(200);
    const { couple, partner } = acc.json();
    expect(couple.status).toBe('active');
    expect(couple.memberIds).toHaveLength(2);
    expect(couple.memberIds).toEqual(expect.arrayContaining([a.user.id, b.user.id]));
    expect(partner.id).toBe(a.user.id);
    expect(partner).not.toHaveProperty('email');

    const coupleA = await t.app.inject({ method: 'GET', url: `${API}/couple`, headers: bearer(a) });
    expect(coupleA.statusCode).toBe(200);
    expect(coupleA.json().partner.id).toBe(b.user.id);

    const events = await t.db.events.find({ coupleId: new ObjectId(couple.id) }).toArray();
    expect(events.map((e) => e.type)).toEqual(['couple.updated']);
    expect(events[0]!.seq).toBe(1);
    expect(events[0]!.payload).toEqual({
      couple: expect.objectContaining({ id: couple.id, status: 'active' }),
    });

    // third user cannot accept an already-accepted invitation
    const c = await register(t.app, 'Cal');
    const third = await accept(c, inv.code);
    expect(third.statusCode).toBe(409);
    expect(third.json().error.code).toBe('invitation_used');

    // the couple still has exactly two members
    const doc = await t.db.couples.findOne({ _id: new ObjectId(couple.id) });
    expect(doc!.memberIds).toHaveLength(2);
  });

  it('rejects expired invitations (410) and revoked invitations', async () => {
    const a = await register(t.app, 'Eve');
    const b = await register(t.app, 'Finn');
    const inv = await invite(a);
    await t.db.invitations.updateOne(
      { _id: new ObjectId(inv.id) },
      { $set: { expiresAt: new Date(Date.now() - 1000) } },
    );
    const expired = await accept(b, inv.code);
    expect(expired.statusCode).toBe(410);
    expect(expired.json().error.code).toBe('invitation_expired');
    const current = await t.app.inject({
      method: 'GET',
      url: `${API}/invitations/current`,
      headers: bearer(a),
    });
    expect(current.json().invitation).toBeNull();

    const inv2 = await invite(a);
    const del = await t.app.inject({
      method: 'DELETE',
      url: `${API}/invitations/${inv2.id}`,
      headers: bearer(a),
    });
    expect(del.statusCode).toBe(204);
    const revoked = await accept(b, inv2.code);
    expect(revoked.statusCode).toBe(410);
    expect(revoked.json().error.code).toBe('invitation_expired');

    // someone else cannot revoke my invitation
    const inv3 = await invite(a);
    const notMine = await t.app.inject({
      method: 'DELETE',
      url: `${API}/invitations/${inv3.id}`,
      headers: bearer(b),
    });
    expect(notMine.statusCode).toBe(404);
  });

  it('rejects own invitation, already-paired acceptors and already-paired inviters', async () => {
    const a = await register(t.app, 'Gia');
    const b = await register(t.app, 'Hal');
    const inv = await invite(a);
    const own = await accept(a, inv.code);
    expect(own.statusCode).toBe(409);
    expect(own.json().error.code).toBe('conflict');

    // pair a+b, then a paired user tries to accept someone else's code
    const ok = await accept(b, inv.code);
    expect(ok.statusCode).toBe(200);
    const c = await register(t.app, 'Ivy');
    const invC = await invite(c);
    const paired = await accept(a, invC.code);
    expect(paired.statusCode).toBe(409);
    expect(paired.json().error.code).toBe('already_paired');

    // a paired user cannot create new invitations
    const again = await t.app.inject({
      method: 'POST',
      url: `${API}/invitations`,
      headers: bearer(a),
      payload: {},
    });
    expect(again.statusCode).toBe(409);
    expect(again.json().error.code).toBe('already_paired');

    // inviter who got paired elsewhere: their stale open code cannot be accepted
    const d = await register(t.app, 'Jon');
    const invD = await invite(d);
    const e = await register(t.app, 'Kim');
    await pair(t.app, e, d); // d accepts e's code and is now paired
    const f = await register(t.app, 'Lou');
    const stale = await accept(f, invD.code);
    expect([409, 410]).toContain(stale.statusCode);
    expect(['already_paired', 'invitation_expired', 'invitation_used']).toContain(stale.json().error.code);
  });

  it('limits accept attempts to 5 per 15 minutes per user+IP', async () => {
    const a = await register(t.app, 'Max');
    const codes = ['AAAAAAAAAA', 'BBBBBBBBBB', 'CCCCCCCCCC', 'DDDDDDDDDD', 'EEEEEEEEEE', 'FFFFFFFFFF'];
    const statuses: number[] = [];
    for (const code of codes) statuses.push((await accept(a, code)).statusCode);
    expect(statuses.slice(0, 5).every((s) => s === 404)).toBe(true);
    expect(statuses[5]).toBe(429);
  });

  it('unknown code → 404; unlink ends the couple and emits couple.unlinked', async () => {
    const a = await register(t.app, 'Ned');
    const b = await register(t.app, 'Oli');
    const coupleId = await pair(t.app, a, b);
    const noConfirm = await t.app.inject({
      method: 'POST',
      url: `${API}/couple/unlink`,
      headers: bearer(a),
      payload: { confirm: false },
    });
    expect(noConfirm.statusCode).toBe(400);
    const unlink = await t.app.inject({
      method: 'POST',
      url: `${API}/couple/unlink`,
      headers: bearer(a),
      payload: { confirm: true },
    });
    expect(unlink.statusCode).toBe(204);

    const meB = await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(b) });
    expect(meB.json().couple).toBeNull();
    expect(meB.json().partner).toBeNull();
    const notPaired = await t.app.inject({ method: 'GET', url: `${API}/couple`, headers: bearer(b) });
    expect(notPaired.statusCode).toBe(409);
    expect(notPaired.json().error.code).toBe('not_paired');

    const events = await t.db.events
      .find({ coupleId: new ObjectId(coupleId) })
      .sort({ seq: 1 })
      .toArray();
    const unlinked = events.find((e) => e.type === 'couple.unlinked');
    expect(unlinked).toBeDefined();
    expect(unlinked!.payload).toEqual({ coupleId, endedAt: expect.any(String), by: a.user.id });
    expect(unlinked!.actorId.toHexString()).toBe(a.user.id);

    // both can pair again afterwards
    const c = await register(t.app, 'Pat');
    await expect(pair(t.app, b, c)).resolves.toMatch(/^[a-f0-9]{24}$/);
  });

  it('PATCH /couple updates shared fields and emits couple.updated', async () => {
    const a = await register(t.app, 'Quin');
    const b = await register(t.app, 'Rae');
    const coupleId = await pair(t.app, a, b);
    const res = await t.app.inject({
      method: 'PATCH',
      url: `${API}/couple`,
      headers: bearer(b),
      payload: {
        relationshipStartDate: '2024-02-14',
        nextMeetingDate: '2026-12-24T18:00:00.000Z',
        nextMeetingLabel: 'Christmas',
        sharedPreferences: { theme: 'dusk' },
      },
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().couple).toMatchObject({
      relationshipStartDate: '2024-02-14',
      nextMeetingLabel: 'Christmas',
      sharedPreferences: { theme: 'dusk' },
    });
    const bad = await t.app.inject({
      method: 'PATCH',
      url: `${API}/couple`,
      headers: bearer(b),
      payload: { relationshipStartDate: '14/02/2024' },
    });
    expect(bad.statusCode).toBe(400);
    const events = await t.db.events
      .find({ coupleId: new ObjectId(coupleId), type: 'couple.updated' })
      .toArray();
    expect(events.length).toBe(2); // activation + patch
    expect(events[1]!.payload).toEqual({
      couple: expect.objectContaining({ nextMeetingLabel: 'Christmas' }),
    });
  });
});
