import { randomUUID } from 'node:crypto';
import { ObjectId } from 'mongodb';
import { pino } from 'pino';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { DeviceTokenDoc } from '../src/db/types.js';
import { CleanupWorker } from '../src/workers/cleanup.js';
import { API, bearer, createTestApp, register, type TestApp } from './helpers/app.js';

let t: TestApp;
let cleanup: CleanupWorker;

beforeAll(async () => {
  t = await createTestApp();
  cleanup = new CleanupWorker(t.db, t.storage, t.clock.now, pino({ level: 'silent' }), {
    intervalMs: 60_000,
    abandonedUploadHours: 24,
  });
});
afterAll(async () => {
  await t.close();
});

const HOUR = 3600_000;
const DAY = 24 * HOUR;
const ago = (ms: number) => new Date(t.clock.now().getTime() - ms);

/** A device row as the outbox and the push ack leave it: `sent`/`acked` are how long ago. */
async function row(
  userId: ObjectId,
  opts: { created: number; sent?: number; acked?: number },
): Promise<ObjectId> {
  const _id = new ObjectId();
  const doc: DeviceTokenDoc = {
    _id,
    userId,
    deviceId: `dev-${randomUUID().slice(0, 8)}`,
    platform: 'android',
    token: `fcm-${randomUUID()}`,
    appVersion: '0.2.0+abc',
    model: 'RMX3741',
    createdAt: ago(opts.created),
    updatedAt: ago(opts.created),
    ...(opts.sent !== undefined
      ? { lastPushSentAt: ago(opts.sent), lastPushSentPriority: 'high' as const }
      : {}),
    ...(opts.acked !== undefined
      ? { lastPushAckAt: ago(opts.acked), lastPushPriority: 'high' as const }
      : {}),
  };
  await t.db.deviceTokens.insertOne(doc);
  return _id;
}
const exists = async (id: ObjectId) => (await t.db.deviceTokens.countDocuments({ _id: id })) === 1;

describe('pruning dead duplicate device rows (cleanup worker)', () => {
  it('deletes a row pushed for days without an ack while the same user’s other phone acks', async () => {
    const user = new ObjectId();
    const live = await row(user, { created: 2 * DAY, sent: HOUR, acked: HOUR });
    const dead = await row(user, { created: 10 * DAY, sent: HOUR });
    const report = await cleanup.runOnce();
    expect(report.deadDevicesPruned).toBe(1);
    expect(await exists(dead)).toBe(false);
    expect(await exists(live)).toBe(true);
  });

  it("never deletes a user's only row, even one that never acked", async () => {
    const only = await row(new ObjectId(), { created: 10 * DAY, sent: HOUR });
    await cleanup.runOnce();
    expect(await exists(only)).toBe(true);
  });

  it('keeps both rows when neither acks (a build that does not ack)', async () => {
    const user = new ObjectId();
    const one = await row(user, { created: 10 * DAY, sent: HOUR });
    const two = await row(user, { created: 9 * DAY, sent: HOUR });
    await cleanup.runOnce();
    expect(await exists(one)).toBe(true);
    expect(await exists(two)).toBe(true);
  });

  it('keeps a silent row younger than 3 days', async () => {
    const user = new ObjectId();
    await row(user, { created: 5 * DAY, sent: HOUR, acked: HOUR });
    const young = await row(user, { created: 2 * DAY, sent: HOUR });
    await cleanup.runOnce();
    expect(await exists(young)).toBe(true);
  });

  it('counts silence from the last ack: acked 5 days ago and pushed since goes, acked 2 days ago stays', async () => {
    const user = new ObjectId();
    await row(user, { created: 30 * DAY, sent: HOUR, acked: HOUR });
    const old = await row(user, { created: 30 * DAY, sent: HOUR, acked: 5 * DAY });
    const recent = await row(user, { created: 30 * DAY, sent: HOUR, acked: 2 * DAY });
    await cleanup.runOnce();
    expect(await exists(old)).toBe(false);
    expect(await exists(recent)).toBe(true);
  });

  it('keeps a row that was not pushed since its last ack, or never pushed at all', async () => {
    const user = new ObjectId();
    await row(user, { created: 30 * DAY, sent: HOUR, acked: HOUR });
    const quiet = await row(user, { created: 30 * DAY, sent: 6 * DAY, acked: 5 * DAY });
    const neverPushed = await row(user, { created: 30 * DAY });
    await cleanup.runOnce();
    expect(await exists(quiet)).toBe(true);
    expect(await exists(neverPushed)).toBe(true);
  });

  it('needs the sibling to have acked within 24 hours', async () => {
    const user = new ObjectId();
    await row(user, { created: 30 * DAY, sent: HOUR, acked: 30 * HOUR });
    const silent = await row(user, { created: 30 * DAY, sent: HOUR });
    await cleanup.runOnce();
    expect(await exists(silent)).toBe(true);
  });

  it("leaves other users' rows alone", async () => {
    const me = new ObjectId();
    const other = new ObjectId();
    await row(me, { created: 30 * DAY, sent: HOUR, acked: HOUR });
    const theirs = await row(other, { created: 30 * DAY, sent: HOUR });
    await cleanup.runOnce();
    expect(await exists(theirs)).toBe(true);
  });

  it('a pruned phone registers again the next time the app opens', async () => {
    const s = await register(t.app, 'Pr');
    const put = (deviceId: string, token: string) =>
      t.app.inject({
        method: 'PUT',
        url: `${API}/devices`,
        headers: bearer(s),
        payload: { deviceId, platform: 'android', token, appVersion: '1.0.0' },
      });
    expect((await put('new-install', `fcm-${randomUUID()}`)).statusCode).toBe(204);
    expect((await put('old-install', `fcm-${randomUUID()}`)).statusCode).toBe(204);
    const userId = new ObjectId(s.user.id);
    await t.db.deviceTokens.updateOne(
      { userId, deviceId: 'new-install' },
      { $set: { lastPushSentAt: ago(HOUR), lastPushAckAt: ago(HOUR) } },
    );
    await t.db.deviceTokens.updateOne(
      { userId, deviceId: 'old-install' },
      { $set: { createdAt: ago(10 * DAY), updatedAt: ago(10 * DAY), lastPushSentAt: ago(HOUR) } },
    );
    expect(await cleanup.pruneDeadDuplicateDevices(t.clock.now())).toBe(1);
    expect(await t.db.deviceTokens.countDocuments({ userId, deviceId: 'old-install' })).toBe(0);
    expect((await put('old-install', `fcm-${randomUUID()}`)).statusCode).toBe(204);
    expect(await t.db.deviceTokens.countDocuments({ userId, deviceId: 'old-install' })).toBe(1);
  });
});
