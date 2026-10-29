import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CalendarEntryDoc, CalendarSource, CoupleDoc } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { canonicalTimeZone } from '../../lib/time.js';
import { isDuplicateKey } from '../auth/service.js';
import { toCalendarEntryDto } from '../shared/dto.js';
import type { CalendarEntryDto } from '../shared/schemas.js';
import { CalendarRepo } from './repo.js';
import {
  type CreateCalendarInput,
  MAX_CALENDAR_ENTRIES,
  MAX_SPAN_DAYS,
  type PatchCalendarInput,
} from './schemas.js';

/** Without expectedVersion, a PATCH that loses a compare-and-set race is re-applied on fresh state. */
const MAX_PATCH_ATTEMPTS = 5;

type SourceInput = NonNullable<CreateCalendarInput['source']>;

/** The fields the cross-field rules look at, as the entry will be stored. */
export type EntryShape = Pick<
  CalendarEntryDoc,
  'kind' | 'date' | 'endDate' | 'time' | 'durationMinutes' | 'timeZone' | 'repeat' | 'repeatUntil'
>;

export interface Issue {
  path: string;
  message: string;
}

function plusDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/**
 * Rules between fields, checked on the entry as it will be stored (after a PATCH is merged), so a
 * PATCH that clears `time` must also clear `timeZone` and `durationMinutes`. YYYY-MM-DD strings
 * compare in date order.
 */
export function entryIssues(e: EntryShape): Issue[] {
  const issues: Issue[] = [];
  if (e.time === null && e.timeZone !== null) {
    issues.push({ path: '/timeZone', message: 'timeZone needs a time (all-day entries have none)' });
  }
  if (e.time === null && e.durationMinutes !== null) {
    issues.push({ path: '/durationMinutes', message: 'durationMinutes needs a time' });
  }
  if (e.endDate !== null) {
    if (e.time !== null) issues.push({ path: '/endDate', message: 'endDate is only for all-day entries' });
    else if (e.endDate < e.date || e.endDate > plusDays(e.date, MAX_SPAN_DAYS)) {
      issues.push({
        path: '/endDate',
        message: `endDate must be on or after date and at most ${MAX_SPAN_DAYS} days later`,
      });
    }
  }
  if (e.kind === 'todo' && e.repeat !== 'none') {
    issues.push({ path: '/repeat', message: 'a to-do cannot repeat' });
  }
  if (e.repeatUntil !== null && e.repeatUntil < e.date) {
    issues.push({ path: '/repeatUntil', message: 'repeatUntil must be on or after date' });
  }
  return issues;
}

/**
 * The shared calendar: events and dated to-dos, stored as wall-clock fields. The server never
 * works out occurrences or reminder times; each phone schedules its own reminders from these.
 * `notify` in the events only carries the creator's "let my partner know" choice: the push stays a
 * normal-priority sync and the partner's app posts the notice when it applies the event.
 */
export class CalendarService {
  private readonly repo: CalendarRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new CalendarRepo(ctx.db);
  }

  async list(couple: CoupleDoc): Promise<{ items: CalendarEntryDto[] }> {
    return { items: (await this.repo.list(couple._id)).map(toCalendarEntryDto) };
  }

  /**
   * With a clientKey, a repeat (a phone retrying a create whose answer it never got) returns the
   * first entry, without a second entry or event. The unique index settles a race between two.
   */
  async create(
    couple: CoupleDoc,
    userId: string,
    input: CreateCalendarInput,
  ): Promise<{ entry: CalendarEntryDto; created: boolean }> {
    if (input.clientKey !== undefined) {
      const first = await this.repo.findByClientKey(couple._id, input.clientKey);
      if (first) return { entry: toCalendarEntryDto(first), created: false };
    }
    const shape: EntryShape = {
      kind: input.kind,
      date: input.date,
      endDate: input.endDate ?? null,
      time: input.time ?? null,
      durationMinutes: input.durationMinutes ?? null,
      timeZone: input.timeZone ? canonicalTimeZone(input.timeZone) : null,
      repeat: input.repeat ?? 'none',
      repeatUntil: input.repeatUntil ?? null,
    };
    rejectIssues(entryIssues(shape));
    const remindUserIds =
      input.remindUserIds === undefined ? [...couple.memberIds] : members(couple, input.remindUserIds);
    const source = input.source ? await this.resolveSource(couple, input.source) : null;
    if ((await this.repo.count(couple._id)) >= MAX_CALENDAR_ENTRIES) {
      throw errors.conflict(`At most ${MAX_CALENDAR_ENTRIES} calendar entries per couple`);
    }

    const now = this.ctx.now();
    const uid = new ObjectId(userId);
    const doc: CalendarEntryDoc = {
      _id: newId(),
      coupleId: couple._id,
      createdBy: uid,
      updatedBy: uid,
      ...shape,
      title: input.title,
      notes: blankToNull(input.notes),
      emoji: input.emoji ?? null,
      reminders: ascending(input.reminders ?? []),
      remindUserIds,
      pinned: input.pinned ?? false,
      doneAt: null,
      doneBy: null,
      source,
      clientKey: input.clientKey ?? null,
      version: 1,
      createdAt: now,
      updatedAt: now,
    };
    try {
      await this.repo.insert(doc);
    } catch (err) {
      if (isDuplicateKey(err) && input.clientKey !== undefined) {
        const first = await this.repo.findByClientKey(couple._id, input.clientKey);
        if (first) return { entry: toCalendarEntryDto(first), created: false };
      }
      throw err;
    }
    const entry = toCalendarEntryDto(doc);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'calendar.created',
      actorId: uid,
      payload: { entry, notify: input.notifyPartner === true },
    });
    return { entry, created: true };
  }

  async update(
    couple: CoupleDoc,
    userId: string,
    id: ObjectId,
    patch: PatchCalendarInput,
  ): Promise<{ entry: CalendarEntryDto }> {
    const uid = new ObjectId(userId);
    // Independent of the stored entry, so checked once, before the compare-and-set loop.
    const remindUserIds =
      patch.remindUserIds === undefined ? undefined : members(couple, patch.remindUserIds);
    const source =
      patch.source === undefined || patch.source === null
        ? patch.source
        : await this.resolveSource(couple, patch.source);

    for (let attempt = 0; attempt < MAX_PATCH_ATTEMPTS; attempt++) {
      const current = await this.repo.findInCouple(id, couple._id);
      if (!current) throw errors.notFound('Calendar entry');
      if (patch.expectedVersion !== undefined && patch.expectedVersion !== current.version) {
        throw versionConflict(current);
      }
      const now = this.ctx.now();
      const set: Partial<CalendarEntryDoc> = { updatedAt: now, updatedBy: uid };
      if (patch.kind !== undefined) set.kind = patch.kind;
      if (patch.title !== undefined) set.title = patch.title;
      if (patch.date !== undefined) set.date = patch.date;
      if (patch.notes !== undefined) set.notes = blankToNull(patch.notes);
      if (patch.emoji !== undefined) set.emoji = patch.emoji;
      if (patch.endDate !== undefined) set.endDate = patch.endDate;
      if (patch.time !== undefined) set.time = patch.time;
      if (patch.durationMinutes !== undefined) set.durationMinutes = patch.durationMinutes;
      if (patch.timeZone !== undefined) {
        set.timeZone = patch.timeZone === null ? null : canonicalTimeZone(patch.timeZone);
      }
      if (patch.repeat !== undefined) set.repeat = patch.repeat;
      if (patch.repeatUntil !== undefined) set.repeatUntil = patch.repeatUntil;
      if (patch.reminders !== undefined) set.reminders = ascending(patch.reminders);
      if (remindUserIds !== undefined) set.remindUserIds = remindUserIds;
      if (patch.pinned !== undefined) set.pinned = patch.pinned;
      if (source !== undefined) set.source = source;

      const merged: CalendarEntryDoc = { ...current, ...set };
      rejectIssues(entryIssues(merged));
      // done/doneBy/doneAt are server-owned, like a list item's: kept while ticked, stamped when it
      // flips to done, cleared when undone or when the entry stops being a to-do.
      if (merged.kind !== 'todo') {
        if (patch.done !== undefined) {
          throw errors.validation('done is only for to-dos', [{ path: '/done', message: 'not a to-do' }]);
        }
        if (current.doneAt) Object.assign(set, { doneAt: null, doneBy: null });
      } else if (patch.done === true && !current.doneAt) {
        Object.assign(set, { doneAt: now, doneBy: uid });
      } else if (patch.done === false) {
        Object.assign(set, { doneAt: null, doneBy: null });
      }

      const updated = await this.repo.patchAtVersion(id, couple._id, current.version, set);
      if (!updated) {
        // Changed or deleted between read and write. With expectedVersion the client asked for
        // strict optimistic concurrency, so report the conflict; otherwise retry on fresh state.
        if (patch.expectedVersion !== undefined) {
          const fresh = await this.repo.findInCouple(id, couple._id);
          if (!fresh) throw errors.notFound('Calendar entry');
          throw versionConflict(fresh);
        }
        continue;
      }
      const entry = toCalendarEntryDto(updated);
      await this.ctx.events.emit({
        coupleId: couple._id,
        type: 'calendar.updated',
        actorId: uid,
        payload: { entry, notify: patch.notifyPartner === true },
      });
      return { entry };
    }
    throw errors.conflict('The entry is being edited concurrently; try again');
  }

  async delete(couple: CoupleDoc, userId: string, id: ObjectId): Promise<void> {
    if (!(await this.repo.delete(id, couple._id))) throw errors.notFound('Calendar entry');
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'calendar.deleted',
      actorId: new ObjectId(userId),
      payload: { entryId: id.toHexString() },
    });
  }

  /**
   * A source must belong to this couple: a message that still exists and is not a view-once photo
   * (its content is meant to disappear), or a list that is not deleted. A list item is not looked
   * up: the phone may link one it has not saved yet, and items come and go; the list is enough.
   */
  private async resolveSource(couple: CoupleDoc, s: SourceInput): Promise<CalendarSource> {
    if (s.kind === 'message') {
      const messageId = new ObjectId(s.messageId);
      const message = await this.repo.findMessage(messageId, couple._id);
      if (!message || message.deletedAt) throw errors.notFound('Message');
      if (message.viewOnce) {
        throw errors.validation('A view-once photo cannot be added to the calendar', [
          { path: '/source/messageId', message: 'view-once message' },
        ]);
      }
      return { kind: 'message', messageId };
    }
    const listId = new ObjectId(s.listId);
    if (!(await this.repo.findList(listId, couple._id))) throw errors.notFound('List');
    return s.kind === 'list' ? { kind: 'list', listId } : { kind: 'list_item', listId, itemId: s.itemId };
  }
}

function rejectIssues(issues: Issue[]): void {
  if (issues.length > 0) throw errors.validation(issues[0]!.message, issues);
}

/** remindUserIds must name members of this couple. */
function members(couple: CoupleDoc, ids: string[]): ObjectId[] {
  return ids.map((id, i) => {
    const member = couple.memberIds.find((m) => m.toHexString() === id);
    if (!member) {
      throw errors.validation('remindUserIds must be members of the couple', [
        { path: `/remindUserIds/${i}`, message: 'not a member of this couple' },
      ]);
    }
    return member;
  });
}

function blankToNull(notes: string | null | undefined): string | null {
  return notes === undefined || notes === null || notes.trim() === '' ? null : notes;
}

function ascending(minutes: number[]): number[] {
  return [...minutes].sort((x, y) => x - y);
}

function versionConflict(current: CalendarEntryDoc) {
  return errors.versionConflict(`The entry changed (now at version ${current.version})`, {
    entry: toCalendarEntryDto(current),
  });
}
