import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type {
  CoupleDoc,
  LiveLocationDoc,
  LiveLocationReason,
  LocationFix,
  LocationStopReason,
} from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { toLiveLocationDto, toLiveLocationSessionDto } from '../shared/dto.js';
import type { LiveLocationDto } from '../shared/schemas.js';
import { LocationRepo } from './repo.js';
import type { LocationFixInputType, StartSharingInput } from './schemas.js';

/** A session with no check-in for this long is purged even when it was "until I turn it off". */
export const LIVE_LOCATION_IDLE_PURGE_MS = 12 * 60 * 60_000;
/** How long a stopped session's tombstone (no coordinates) refuses a late start with its id. */
export const LIVE_LOCATION_TOMBSTONE_MS = 10 * 60_000;
export const LIVE_LOCATION_MAX_ACCURACY_M = 50_000;
/** Per-user budgets (route rate limits). Panic starts skip the start budget (SOS limits panic). */
export const LIVE_LOCATION_FIX_WRITES_PER_MIN = 12;
export const LIVE_LOCATION_STARTS_PER_HOUR = 20;

// Local copy of auth's helper: importing it would make auth -> location -> auth an import cycle.
const isDuplicateKey = (err: unknown) =>
  typeof err === 'object' && err !== null && (err as { code?: number }).code === 11000;

/** Rounds to 5 decimals (about 1 m): more precision is noise and only adds identifying detail. */
const round5 = (n: number) => Math.round(n * 1e5) / 1e5;

/** A session is usable only while live and not past its expiry or idle purge (the TTL monitor lags). */
export function isAlive(doc: LiveLocationDoc, now: Date): boolean {
  return doc.live && (doc.expiresAt === null || doc.expiresAt > now) && doc.purgeAt.getTime() > now.getTime();
}

/** The caller's own session in this couple, still usable. */
function isMineAndAlive(
  doc: LiveLocationDoc | null,
  me: ObjectId,
  coupleId: ObjectId,
  now: Date,
): doc is LiveLocationDoc {
  if (!doc) return false;
  return doc.userId.equals(me) && doc.coupleId.equals(coupleId) && isAlive(doc, now);
}

/** TTL deadline: the timed expiry, or 12 h after the last check-in, whichever comes first. */
export function purgeAtFor(expiresAt: Date | null, now: Date): Date {
  const idle = new Date(now.getTime() + LIVE_LOCATION_IDLE_PURGE_MS);
  return expiresAt && expiresAt < idle ? expiresAt : idle;
}

export function normaliseFix(input: LocationFixInputType, now: Date): LocationFix {
  return {
    lat: round5(input.lat),
    lng: round5(input.lng),
    accuracyM: Math.min(LIVE_LOCATION_MAX_ACCURACY_M, Math.ceil(input.accuracyM)),
    at: new Date(now.getTime() - input.ageMs),
    batteryPct: input.batteryPct ?? null,
  };
}

/** Later of two expiries where null means "no end". */
function laterExpiry(a: Date | null, b: Date | null): Date | null {
  if (a === null || b === null) return null;
  return a > b ? a : b;
}

/**
 * Whether an extend really moved the end. A retried "share for 8 hours" lands a few milliseconds
 * later; that must not re-notify the partner, so shifts under a minute do not count.
 */
const expiryMoved = (a: Date | null, b: Date | null) =>
  a === null || b === null ? a !== b : Math.abs(a.getTime() - b.getTime()) > 60_000;
const sameObjectId = (a: ObjectId | null, b: ObjectId | null) =>
  (a?.toHexString() ?? null) === (b?.toHexString() ?? null);

/**
 * Live location sharing (safety). One document per live session holds only the latest fix; fixes
 * reach the partner as WebSocket frames that are never stored or replayed, and pushes and events
 * carry no coordinates. Only start and stop are durable events. Stopping removes the coordinates
 * at once. Coordinates are never logged (see REDACT_PATHS).
 */
export class LocationService {
  private readonly repo: LocationRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new LocationRepo(ctx.db);
  }

  async get(
    couple: CoupleDoc,
    me: string,
  ): Promise<{ mine: LiveLocationDto | null; partner: LiveLocationDto | null }> {
    const now = this.ctx.now();
    const meId = new ObjectId(me);
    const docs = (await this.repo.liveForCouple(couple._id)).filter((d) => isAlive(d, now));
    const mine = docs.find((d) => d.userId.equals(meId)) ?? null;
    const partner = docs.find((d) => !d.userId.equals(meId) && d.viewerId.equals(meId)) ?? null;
    return { mine: mine && toLiveLocationDto(mine), partner: partner && toLiveLocationDto(partner) };
  }

  async start(
    couple: CoupleDoc,
    me: string,
    partnerId: string,
    body: StartSharingInput,
  ): Promise<{ created: boolean; sharing: LiveLocationDto }> {
    const now = this.ctx.now();
    const meId = new ObjectId(me);
    const sessionId = new ObjectId(body.sessionId);
    let sosId: ObjectId | null = null;
    if (body.sosId) {
      sosId = new ObjectId(body.sosId);
      const sos = await this.ctx.db.sos.findOne(
        { _id: sosId, coupleId: couple._id, senderId: meId },
        { projection: { _id: 1 } },
      );
      if (!sos) throw errors.notFound('SOS');
    }
    const requested =
      body.mode === 'timed' ? new Date(now.getTime() + (body.durationMinutes ?? 60) * 60_000) : null;
    const fix = body.fix ? normaliseFix(body.fix, now) : null;
    const request = { reason: body.reason, requested, fix, sosId };

    // The same session again (a retry, or an extend): only while it is still live. A stopped
    // session's tombstone refuses it, so a start that arrives after its own stop stays stopped.
    const same = await this.repo.byId(sessionId);
    if (same) {
      if (!isMineAndAlive(same, meId, couple._id, now)) {
        throw errors.locationNotSharing('This sharing session has ended');
      }
      return this.extend(couple, same, request, now);
    }

    const existing = await this.repo.liveOf(couple._id, meId);
    if (existing) {
      if (isAlive(existing, now)) return this.extend(couple, existing, request, now);
      // Expired but not yet removed by the TTL monitor: clear it so the new session can start.
      // The partner's app already hides an expired session itself, so no event is emitted.
      await this.repo.deleteById(existing._id);
    }

    const doc: LiveLocationDoc = {
      _id: sessionId,
      coupleId: couple._id,
      userId: meId,
      viewerId: new ObjectId(partnerId),
      reason: body.reason,
      sosId,
      live: true,
      startedAt: now,
      expiresAt: requested,
      purgeAt: purgeAtFor(requested, now),
      fix,
      lastSeenAt: now,
      stoppedAt: null,
      updatedAt: now,
    };
    try {
      await this.repo.insert(doc);
    } catch (err) {
      if (!isDuplicateKey(err)) throw err;
      // A concurrent start won (same id, or another live session of mine): extend the winner.
      const winner = (await this.repo.byId(sessionId)) ?? (await this.repo.liveOf(couple._id, meId));
      if (!isMineAndAlive(winner, meId, couple._id, now)) {
        throw errors.locationNotSharing('This sharing session has ended');
      }
      return this.extend(couple, winner, request, now);
    }
    await this.emitStarted(couple, doc);
    const dto = toLiveLocationDto(doc);
    this.frame(couple, doc, dto);
    return { created: true, sharing: dto };
  }

  /**
   * Updates a live session. A manual start never downgrades a panic, and panic never shortens a
   * session (a manual change can). Merging means "I'm safe now" also ends a manual session that a
   * panic was merged into: on purpose, stopping is always the privacy-safe side.
   */
  private async extend(
    couple: CoupleDoc,
    existing: LiveLocationDoc,
    req: {
      reason: LiveLocationReason;
      requested: Date | null;
      fix: LocationFix | null;
      sosId: ObjectId | null;
    },
    now: Date,
  ): Promise<{ created: boolean; sharing: LiveLocationDto }> {
    const reason: LiveLocationReason = existing.reason === 'panic' ? 'panic' : req.reason;
    const sosId = req.sosId ?? existing.sosId;
    const expiresAt = reason === 'panic' ? laterExpiry(existing.expiresAt, req.requested) : req.requested;
    const newerFix = req.fix && (!existing.fix || req.fix.at > existing.fix.at) ? req.fix : null;
    const updated = await this.repo.updateLive(existing._id, {
      $set: {
        reason,
        sosId,
        expiresAt,
        purgeAt: purgeAtFor(expiresAt, now),
        lastSeenAt: now,
        updatedAt: now,
        ...(newerFix ? { fix: newerFix } : {}),
      },
    });
    if (!updated) throw errors.locationNotSharing('This sharing session has ended');
    const changed =
      reason !== existing.reason ||
      expiryMoved(expiresAt, existing.expiresAt) ||
      !sameObjectId(sosId, existing.sosId);
    if (changed) await this.emitStarted(couple, updated);
    const dto = toLiveLocationDto(updated);
    this.frame(couple, updated, dto);
    return { created: false, sharing: dto };
  }

  /**
   * A check-in from the sharer's phone. It always refreshes liveness (`lastSeenAt`) and the idle
   * purge, even when the fix itself is not newer than the stored one, so a phone that is still but
   * alive never looks stale to the partner. Writes no event and sends no push.
   */
  async putFix(
    couple: CoupleDoc,
    me: string,
    body: { sessionId: string; fix: LocationFixInputType },
  ): Promise<{ accepted: boolean }> {
    const now = this.ctx.now();
    const doc = await this.repo.byId(new ObjectId(body.sessionId));
    if (!isMineAndAlive(doc, new ObjectId(me), couple._id, now)) throw errors.locationNotSharing();
    const fix = normaliseFix(body.fix, now);
    const accepted = !doc.fix || fix.at > doc.fix.at;
    const updated = await this.repo.updateLive(doc._id, {
      $set: {
        lastSeenAt: now,
        updatedAt: now,
        purgeAt: purgeAtFor(doc.expiresAt, now),
        ...(accepted ? { fix } : {}),
      },
    });
    // Stopped between the read and the write: never send the partner a location after a stop.
    if (!updated) throw errors.locationNotSharing();
    this.frame(couple, updated, toLiveLocationDto(updated));
    return { accepted };
  }

  /**
   * Stop sharing. Needs no couple (stopping must always work). With a sessionId only that session
   * stops, so a stale retry can never end a newer session; if the id is not known yet (its start is
   * still in flight) a tombstone is left so that start is refused. Without one, every live session
   * of mine stops.
   */
  async stop(me: string, query: { sessionId?: string; reason: 'stopped' | 'expired' }): Promise<void> {
    const now = this.ctx.now();
    const meId = new ObjectId(me);
    if (!query.sessionId) {
      for (const doc of await this.repo.liveOfUser(meId)) await this.end(doc._id, query.reason, meId, now);
      return;
    }
    const id = new ObjectId(query.sessionId);
    // end() and byId() are two reads: the matching start can insert the live session in between.
    // A live session of mine found by byId() is therefore ended on the next pass (it cannot turn
    // live again once ended, so the second pass always settles it).
    let found: LiveLocationDoc | null = null;
    for (let pass = 0; pass < 2; pass++) {
      if (await this.end(id, query.reason, meId, now)) return;
      found = await this.repo.byId(id);
      if (!found?.live || !found.userId.equals(meId)) break;
    }
    if (found) return; // already stopped (idempotent), or not mine
    const user = await this.ctx.db.users.findOne({ _id: meId }, { projection: { activeCoupleId: 1 } });
    const couple = user?.activeCoupleId
      ? await this.ctx.db.couples.findOne({ _id: user.activeCoupleId, status: 'active' })
      : null;
    if (!couple) return; // a start without a couple is refused anyway
    const viewerId = couple.memberIds.find((m) => !m.equals(meId)) ?? meId;
    try {
      await this.repo.insert({
        _id: id,
        coupleId: couple._id,
        userId: meId,
        viewerId,
        reason: 'manual',
        sosId: null,
        live: false,
        startedAt: now,
        expiresAt: now,
        purgeAt: new Date(now.getTime() + LIVE_LOCATION_TOMBSTONE_MS),
        fix: null,
        lastSeenAt: now,
        stoppedAt: now,
        updatedAt: now,
      });
    } catch (err) {
      if (!isDuplicateKey(err)) throw err;
      await this.end(id, query.reason, meId, now); // the start won the race: stop it now
    }
  }

  /** "I'm safe now": ends the live sharing linked to that panic SOS. */
  async stopForSos(couple: CoupleDoc, me: string, sosId: ObjectId): Promise<void> {
    const now = this.ctx.now();
    const meId = new ObjectId(me);
    for (const doc of await this.repo.liveOfUser(meId, { sosId, coupleId: couple._id })) {
      await this.end(doc._id, 'resolved', meId, now);
    }
  }

  /**
   * Sign-out, session revocation and account deletion: delete every session of the user outright
   * (its access tokens are dead, so no late start can follow) and tell the partner.
   */
  async endAllForUser(userId: ObjectId): Promise<void> {
    const now = this.ctx.now();
    const live = (await this.repo.liveOfUser(userId)).filter((d) => isAlive(d, now));
    await this.repo.deleteAllOfUser(userId);
    for (const doc of live) await this.emitStopped(doc, 'signed_out', userId, now);
  }

  private async end(id: ObjectId, reason: LocationStopReason, actor: ObjectId, now: Date): Promise<boolean> {
    const purgeAt = new Date(now.getTime() + LIVE_LOCATION_TOMBSTONE_MS);
    const before = await this.repo.tombstone(id, actor, now, purgeAt);
    if (!before) return false;
    await this.emitStopped(before, reason, actor, now);
    return true;
  }

  private async emitStarted(couple: CoupleDoc, doc: LiveLocationDoc): Promise<void> {
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'location.started',
      actorId: doc.userId,
      payload: { sharing: toLiveLocationSessionDto(doc) },
    });
  }

  private async emitStopped(doc: LiveLocationDoc, reason: LocationStopReason, actor: ObjectId, now: Date) {
    // An unlinked couple's data is purged and its members are told by couple.unlinked instead.
    const active = await this.ctx.db.couples.countDocuments(
      { _id: doc.coupleId, status: 'active' },
      { limit: 1 },
    );
    if (active === 0) return;
    await this.ctx.events.emit({
      coupleId: doc.coupleId,
      type: 'location.stopped',
      actorId: actor,
      payload: {
        sessionId: doc._id.toHexString(),
        userId: doc.userId.toHexString(),
        stoppedAt: now.toISOString(),
        reason,
      },
    });
  }

  /** Fire-and-forget frame to the viewer only (D-12): a lost frame is replaced by the next fix. */
  private frame(couple: CoupleDoc, doc: LiveLocationDoc, dto: LiveLocationDto): void {
    this.ctx.hub.sendToUser(doc.viewerId.toHexString(), couple._id.toHexString(), {
      type: 'location',
      location: dto,
    });
  }
}
