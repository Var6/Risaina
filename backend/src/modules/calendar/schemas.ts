import { z } from 'zod';
import {
  CalendarDate,
  CalendarEntry,
  CalendarKind,
  CalendarRepeat,
  Emoji,
  HHmm,
  IdString,
  ProfileTimeZone,
} from '../shared/schemas.js';

/** Entries per couple (POST beyond it: 409 conflict). */
export const MAX_CALENDAR_ENTRIES = 1000;
/** A multi-day all-day entry (a trip, a visit) spans at most this many days after `date`. */
export const MAX_SPAN_DAYS = 366;

const unique = (values: unknown[]) => new Set(values).size === values.length;

/** Day of the month exists (CalendarDate alone lets '2026-02-30' through: Date rolls it into March). */
function isRealDay(s: string): boolean {
  const [y, m, d] = s.split('-').map(Number) as [number, number, number];
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}
const Day = CalendarDate.refine(isRealDay, 'must be a real calendar date');

const Title = z.string().trim().min(1).max(120);
const Notes = z.string().max(2000).nullable().meta({ description: '≤ 2000; blank is stored as null' });
const EntryEmoji = Emoji.nullable().meta({ description: '1–2 emoji' });
const EndDate = Day.nullable().meta({
  description: `All-day only: the last day, on or after date and at most ${MAX_SPAN_DAYS} days later`,
});
const Time = HHmm.nullable().meta({ description: 'HH:mm; null = all day' });
const DurationMinutes = z.int().min(1).max(10_080).nullable().meta({ description: 'Timed entries only' });
const EntryTimeZone = ProfileTimeZone.nullable().meta({
  description: "IANA zone the time is anchored to (timed only); null = each phone's own clock",
});
const RepeatUntil = Day.nullable().meta({ description: 'Inclusive, on or after date' });
const Reminders = z.array(z.int().min(0).max(40_320)).max(4).refine(unique, 'reminders must be unique').meta({
  description:
    "Minutes before (0–40320, at most 4). For all-day entries they count back from each phone's own all-day reminder time on the date.",
});
const RemindUserIds = z
  .array(IdString)
  .max(2)
  .refine(unique, 'remindUserIds must be unique')
  .meta({ description: "Members whose phones remind (a to-do's assignees). Default: both" });
const ItemId = z.string().regex(/^[A-Za-z0-9_-]{1,32}$/, 'must be a list item id');
const Source = z
  .discriminatedUnion('kind', [
    z.object({ kind: z.literal('message'), messageId: IdString }).strict(),
    z.object({ kind: z.literal('list'), listId: IdString }).strict(),
    z.object({ kind: z.literal('list_item'), listId: IdString, itemId: ItemId }).strict(),
  ])
  .nullable()
  .meta({ description: 'A text message or a non-deleted list of this couple (404 otherwise)' });
const NotifyPartner = z.boolean().meta({
  description: 'Ask the partner\'s phone to say "<name> added … to your calendar" (payload notify: true)',
});
const ExpectedVersion = z
  .number()
  .int()
  .min(1)
  .meta({ description: 'When given and not the current version: 409 version_conflict' });

const fields = {
  kind: CalendarKind,
  title: Title,
  notes: Notes,
  emoji: EntryEmoji,
  date: Day,
  endDate: EndDate,
  time: Time,
  durationMinutes: DurationMinutes,
  timeZone: EntryTimeZone,
  repeat: CalendarRepeat,
  repeatUntil: RepeatUntil,
  reminders: Reminders,
  remindUserIds: RemindUserIds,
  pinned: z.boolean(),
  source: Source,
};

export const CreateCalendarBody = z
  .object({
    kind: fields.kind,
    title: fields.title,
    date: fields.date,
    notes: fields.notes.optional(),
    emoji: fields.emoji.optional(),
    endDate: fields.endDate.optional(),
    time: fields.time.optional(),
    durationMinutes: fields.durationMinutes.optional(),
    timeZone: fields.timeZone.optional(),
    repeat: fields.repeat.optional().meta({ description: "Default 'none'; a to-do cannot repeat" }),
    repeatUntil: fields.repeatUntil.optional(),
    reminders: fields.reminders.optional().meta({ description: 'Default []' }),
    remindUserIds: fields.remindUserIds.optional(),
    pinned: fields.pinned.optional(),
    source: fields.source.optional(),
    clientKey: z.string().min(1).max(64).optional().meta({
      description:
        'Retry key chosen by the phone: a repeat with the same key returns 200 with the first entry',
    }),
    notifyPartner: NotifyPartner.optional().meta({ description: 'Default false' }),
  })
  .strict();

export const PatchCalendarBody = z
  .object({
    kind: fields.kind.optional(),
    title: fields.title.optional(),
    date: fields.date.optional(),
    notes: fields.notes.optional(),
    emoji: fields.emoji.optional(),
    endDate: fields.endDate.optional(),
    time: fields.time.optional(),
    durationMinutes: fields.durationMinutes.optional(),
    timeZone: fields.timeZone.optional(),
    repeat: fields.repeat.optional(),
    repeatUntil: fields.repeatUntil.optional(),
    reminders: fields.reminders.optional(),
    remindUserIds: fields.remindUserIds.optional(),
    pinned: fields.pinned.optional(),
    source: fields.source.optional(),
    done: z.boolean().optional().meta({ description: 'To-dos only (400 for an event)' }),
    expectedVersion: ExpectedVersion.optional(),
    notifyPartner: NotifyPartner.optional().meta({ description: 'Default false' }),
  })
  .strict();

export type CreateCalendarInput = z.infer<typeof CreateCalendarBody>;
export type PatchCalendarInput = z.infer<typeof PatchCalendarBody>;

export const CalendarEntryResponse = z.object({ entry: CalendarEntry });
export const CalendarListResponse = z.object({ items: z.array(CalendarEntry) });
