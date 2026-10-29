import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { AlarmChange, AlarmDoc, AlarmMember, CoupleDoc } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { localMinutes, parseHHmm } from '../../lib/quiet-hours.js';
import { canonicalTimeZone } from '../../lib/time.js';
import { toAlarmDto } from '../shared/dto.js';
import type { AlarmDto } from '../shared/schemas.js';
import { AlarmRepo } from './repo.js';
import {
  type AlarmReportInput,
  type CreateAlarmInput,
  MAX_ALARMS,
  MAX_SKIPS,
  type PatchAlarmInput,
  type PutMyAlarmInput,
  type WakeInput,
} from './schemas.js';

const DAY_MS = 24 * 60 * 60 * 1000;
/** A one-off alarm may be set for a minute ago (clock skew), and at most this far ahead. */
const ONCE_AT_PAST_GRACE_MS = 60_000;
const HORIZON_MS = 366 * DAY_MS;
/** Skips of rings older than this are dropped whenever an entry is written: they cannot ring again. */
const SKIP_KEEP_MS = DAY_MS;
/** A wake report may come late (the phone retries), but it is about a real, recent ring. */
const WAKE_PAST_MS = 7 * DAY_MS;
const WAKE_FUTURE_MS = DAY_MS;
/** Without expectedVersion (member changes, reports), a lost compare-and-set is re-applied. */
const MAX_ATTEMPTS = 5;

export interface Issue {
  path: string;
  message: string;
}

export interface Schedule {
  time: string;
  timeZone: string;
  days: number[];
  onceAt: Date | null;
}

/**
 * Rules of the stored schedule: a one-off alarm (no days) has exactly one instant, `onceAt`, and it
 * must be the alarm's `time` in its zone, so every phone computes the same ring. The 366-day window
 * is checked only when onceAt is being set, so a past one-off alarm can still be renamed.
 */
export function scheduleIssues(s: Schedule, now: Date, checkOnceAtWindow: boolean): Issue[] {
  if (s.days.length === 0 && s.onceAt === null) {
    return [{ path: '/onceAt', message: 'onceAt is required for a one-off alarm (no days)' }];
  }
  if (s.days.length > 0 && s.onceAt !== null) {
    return [
      { path: '/onceAt', message: 'onceAt is only for a one-off alarm: set days to [] or onceAt to null' },
    ];
  }
  if (s.onceAt === null) return [];
  const issues: Issue[] = [];
  const at = s.onceAt.getTime();
  if (checkOnceAtWindow && (at < now.getTime() - ONCE_AT_PAST_GRACE_MS || at > now.getTime() + HORIZON_MS)) {
    issues.push({ path: '/onceAt', message: 'onceAt must be within the next 366 days' });
  }
  if (localMinutes(s.onceAt, s.timeZone) !== parseHHmm(s.time)) {
    issues.push({ path: '/onceAt', message: `onceAt must be at ${s.time} in ${s.timeZone}` });
  }
  return issues;
}

/**
 * Whether the partner's phone should show a notice for this create or edit, which also makes the
 * push high priority (services/push/policy.ts). All of these must hold for the recipient (the
 * member who is not the actor): the alarm rings on their phone, it is enabled, the change is a
 * create or an edit of the shared rule (never a member's own change), and their phone has reported
 * for this alarm (`ready !== null`), so it runs a build that posts the notice. An older app never
 * gets a high push it would answer with nothing, which would teach FCM to delay its pushes.
 */
export function notifyFor(alarm: AlarmDoc, actorId: ObjectId, change: AlarmChange | 'created'): boolean {
  if (change === 'member' || !alarm.enabled) return false;
  const recipient = alarm.members.find((m) => !m.userId.equals(actorId));
  return recipient?.ring === true && (recipient.ready ?? null) !== null;
}

/**
 * Wake together: the server stores one shared alarm rule; each phone rings its own copy and
 * reports back. Members change only their own entry (ring, skips, readiness, wake).
 */
export class AlarmService {
  private readonly repo: AlarmRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new AlarmRepo(ctx.db);
  }

  async list(couple: CoupleDoc): Promise<{ items: AlarmDto[] }> {
    return { items: (await this.repo.list(couple._id)).map(toAlarmDto) };
  }

  async create(couple: CoupleDoc, userId: string, input: CreateAlarmInput): Promise<{ alarm: AlarmDto }> {
    const now = this.ctx.now();
    const schedule: Schedule = {
      time: input.time,
      timeZone: canonicalTimeZone(input.timeZone),
      days: ascending(input.days),
      onceAt: input.onceAt ?? null,
    };
    rejectIssues(scheduleIssues(schedule, now, true));
    if ((await this.repo.count(couple._id)) >= MAX_ALARMS) {
      throw errors.conflict(`At most ${MAX_ALARMS} alarms per couple`);
    }
    const uid = new ObjectId(userId);
    const doc: AlarmDoc = {
      _id: newId(),
      coupleId: couple._id,
      createdBy: uid,
      updatedBy: uid,
      mode: input.mode ?? 'together',
      label: blankToNull(input.label),
      ...schedule,
      enabled: true,
      snoozeMinutes: input.snoozeMinutes ?? 10,
      version: 1,
      createdAt: now,
      updatedAt: now,
      // The partner starts on, like the creator by default; each turns it off on their own phone.
      members: couple.memberIds.map((m) => ({
        userId: m,
        ring: m.equals(uid) ? (input.ringOnMyPhone ?? true) : true,
        skips: [],
        ready: null,
        reason: null,
        armedFor: null,
        reportedAt: null,
        lastWake: null,
      })),
    };
    await this.repo.insert(doc);
    const alarm = toAlarmDto(doc);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'alarm.created',
      actorId: uid,
      payload: { alarm, notify: notifyFor(doc, uid, 'created') },
    });
    return { alarm };
  }

  async update(
    couple: CoupleDoc,
    userId: string,
    id: ObjectId,
    patch: PatchAlarmInput,
  ): Promise<{ alarm: AlarmDto }> {
    const uid = new ObjectId(userId);
    const current = await this.repo.findInCouple(id, couple._id);
    if (!current) throw errors.notFound('Alarm');
    if (patch.expectedVersion !== current.version) throw versionConflict(current);
    const now = this.ctx.now();
    const set: Partial<AlarmDoc> = { updatedAt: now, updatedBy: uid };
    if (patch.label !== undefined) set.label = blankToNull(patch.label);
    if (patch.time !== undefined) set.time = patch.time;
    if (patch.timeZone !== undefined) set.timeZone = canonicalTimeZone(patch.timeZone);
    if (patch.days !== undefined) set.days = ascending(patch.days);
    if (patch.onceAt !== undefined) set.onceAt = patch.onceAt;
    if (patch.enabled !== undefined) set.enabled = patch.enabled;
    if (patch.snoozeMinutes !== undefined) set.snoozeMinutes = patch.snoozeMinutes;
    rejectIssues(scheduleIssues({ ...current, ...set }, now, patch.onceAt != null));

    const updated = await this.repo.patchAtVersion(id, couple._id, current.version, set);
    if (!updated) {
      const fresh = await this.repo.findInCouple(id, couple._id);
      if (!fresh) throw errors.notFound('Alarm');
      throw versionConflict(fresh);
    }
    const onlyEnabled =
      patch.enabled !== undefined &&
      [patch.label, patch.time, patch.timeZone, patch.days, patch.onceAt, patch.snoozeMinutes].every(
        (v) => v === undefined,
      );
    const change: AlarmChange = onlyEnabled ? 'enabled' : 'edited';
    const alarm = toAlarmDto(updated);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'alarm.updated',
      actorId: uid,
      payload: { alarm, change, notify: notifyFor(updated, uid, change) },
    });
    return { alarm };
  }

  async delete(couple: CoupleDoc, userId: string, id: ObjectId): Promise<void> {
    if (!(await this.repo.delete(id, couple._id))) throw errors.notFound('Alarm');
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'alarm.deleted',
      actorId: new ObjectId(userId),
      payload: { alarmId: id.toHexString() },
    });
  }

  /** The caller's own entry only: ring on this phone or not, and skipping one ring. */
  async updateMine(
    couple: CoupleDoc,
    userId: string,
    id: ObjectId,
    input: PutMyAlarmInput,
  ): Promise<{ alarm: AlarmDto }> {
    const uid = new ObjectId(userId);
    const now = this.ctx.now();
    if (input.skip) {
      const at = input.skip.occurrence.getTime();
      if (at < now.getTime() - SKIP_KEEP_MS || at > now.getTime() + HORIZON_MS) {
        throw errors.validation('occurrence must be a ring within the next 366 days', [
          { path: '/skip/occurrence', message: 'out of range' },
        ]);
      }
    }
    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      const current = await this.repo.findInCouple(id, couple._id);
      const me = current?.members.find((m) => m.userId.equals(uid));
      if (!current || !me) throw errors.notFound('Alarm');
      const fields: Partial<AlarmMember> = {};
      if (input.ring !== undefined && input.ring !== me.ring) fields.ring = input.ring;
      const skipped = (me.skips ?? []).some((d) => d.getTime() === input.skip?.occurrence.getTime());
      if (input.skip && input.skip.skip !== skipped) {
        const skips = nextSkips(me.skips ?? [], input.skip.occurrence, input.skip.skip, now);
        if (skips.length > MAX_SKIPS) {
          throw errors.validation(`At most ${MAX_SKIPS} skipped rings per alarm`, [
            { path: '/skip', message: 'too many skips' },
          ]);
        }
        fields.skips = skips;
      }
      if (Object.keys(fields).length === 0) return { alarm: toAlarmDto(current) };
      const updated = await this.repo.patchMemberAtVersion(id, couple._id, current.version, uid, fields, now);
      if (!updated) continue;
      return { alarm: await this.emitMemberChange(couple, uid, updated) };
    }
    throw errors.conflict('The alarm is being changed concurrently; try again');
  }

  /**
   * Readiness each phone reports after it arms its alarms. Only a change of `ready` or `reason` is
   * news for the partner (alarm.updated, change 'member'); armedFor and reportedAt alone are stored
   * silently, without a version or an event. Alarms that no longer exist are skipped.
   */
  async report(couple: CoupleDoc, userId: string, input: AlarmReportInput): Promise<void> {
    const uid = new ObjectId(userId);
    for (const item of input.items) {
      const id = new ObjectId(item.alarmId);
      const fields = {
        ready: item.ready,
        reason: item.reason ?? null,
        armedFor: item.armedFor ?? null,
        reportedAt: this.ctx.now(),
      };
      for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        const current = await this.repo.findInCouple(id, couple._id);
        const me = current?.members.find((m) => m.userId.equals(uid));
        if (!current || !me) break;
        if ((me.ready ?? null) === fields.ready && (me.reason ?? null) === fields.reason) {
          await this.repo.setMemberSilently(id, couple._id, uid, {
            armedFor: fields.armedFor,
            reportedAt: fields.reportedAt,
          });
          break;
        }
        const updated = await this.repo.patchMemberAtVersion(
          id,
          couple._id,
          current.version,
          uid,
          fields,
          fields.reportedAt,
        );
        if (!updated) continue;
        await this.emitMemberChange(couple, uid, updated);
        break;
      }
    }
  }

  /**
   * "I'm up" (or the alarm gave up: 'missed'). Idempotent per member and occurrence: a repeat, or a
   * report about an earlier ring than the one stored, changes nothing and emits nothing. It never
   * silences the partner's phone; their app only shows that this person is up. The version stays.
   */
  async wake(
    couple: CoupleDoc,
    userId: string,
    id: ObjectId,
    input: WakeInput,
  ): Promise<{ alarm: AlarmDto }> {
    const uid = new ObjectId(userId);
    const now = this.ctx.now().getTime();
    const at = input.occurrence.getTime();
    if (at < now - WAKE_PAST_MS || at > now + WAKE_FUTURE_MS) {
      throw errors.validation('occurrence must be a ring of the last 7 days', [
        { path: '/occurrence', message: 'out of range' },
      ]);
    }
    const wake = {
      occurrence: input.occurrence,
      outcome: input.outcome,
      at: input.at,
      snoozes: input.snoozes,
    };
    const updated = await this.repo.recordWake(id, couple._id, uid, wake);
    if (!updated) {
      const current = await this.repo.findInCouple(id, couple._id);
      if (!current) throw errors.notFound('Alarm');
      return { alarm: toAlarmDto(current) };
    }
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'alarm.woke',
      actorId: uid,
      payload: {
        alarmId: id.toHexString(),
        userId: uid.toHexString(),
        occurrence: input.occurrence.toISOString(),
        outcome: input.outcome,
        at: input.at.toISOString(),
        snoozes: input.snoozes,
      },
    });
    return { alarm: toAlarmDto(updated) };
  }

  private async emitMemberChange(couple: CoupleDoc, uid: ObjectId, updated: AlarmDoc): Promise<AlarmDto> {
    const alarm = toAlarmDto(updated);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'alarm.updated',
      actorId: uid,
      payload: { alarm, change: 'member', notify: false },
    });
    return alarm;
  }
}

/** The skips after adding or removing one: rings older than a day dropped, oldest first. */
export function nextSkips(skips: Date[], occurrence: Date, skip: boolean, now: Date): Date[] {
  const keepFrom = now.getTime() - SKIP_KEEP_MS;
  const kept = skips.filter((d) => d.getTime() >= keepFrom && d.getTime() !== occurrence.getTime());
  if (skip) kept.push(occurrence);
  return kept.sort((x, y) => x.getTime() - y.getTime());
}

function rejectIssues(issues: Issue[]): void {
  if (issues.length > 0) throw errors.validation(issues[0]!.message, issues);
}

function ascending(days: number[]): number[] {
  return [...days].sort((x, y) => x - y);
}

function blankToNull(label: string | null | undefined): string | null {
  return label === undefined || label === null || label === '' ? null : label;
}

function versionConflict(current: AlarmDoc) {
  return errors.versionConflict(`The alarm changed (now at version ${current.version})`, {
    alarm: toAlarmDto(current),
  });
}
