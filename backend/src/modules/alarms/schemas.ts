import { z } from 'zod';
import { ALARM_MODES, WAKE_OUTCOMES } from '../../db/types.js';
import { Alarm, HHmm, IdString, ProfileTimeZone } from '../shared/schemas.js';

/** Alarms per couple (POST beyond it: 409 conflict). */
export const MAX_ALARMS = 10;
/** Skipped occurrences one person may hold per alarm. */
export const MAX_SKIPS = 7;
/** Readiness reports per request. */
export const MAX_REPORT_ITEMS = 10;

const unique = (values: unknown[]) => new Set(values).size === values.length;

/**
 * An instant with its zone: 'Z' or an offset is required, because a time without one would be read
 * in the server's own zone. Parsed to a Date.
 */
const Instant = z
  .string()
  .regex(
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/,
    'must be an ISO-8601 datetime with Z or an offset',
  )
  .refine((s) => !Number.isNaN(Date.parse(s)), 'must be an ISO-8601 datetime')
  .transform((s) => new Date(s))
  .meta({ example: '2026-11-02T01:00:00Z' });

const Label = z.string().trim().max(40).nullable().meta({ description: '≤ 40; blank is stored as null' });
const Days = z
  .array(z.int().min(1).max(7))
  .max(7)
  .refine(unique, 'days must be unique')
  .meta({ description: 'ISO weekdays in timeZone, 1 = Monday … 7 = Sunday; [] = once, at onceAt' });
const OnceAt = Instant.nullable().meta({
  description: 'Required exactly when days is empty: within the next 366 days, at `time` in timeZone',
});
const SnoozeMinutes = z.int().min(1).max(30);
/** A short snake_case code such as 'exact_alarms_off' or 'notifications_off'. */
const Reason = z.string().regex(/^[a-z][a-z0-9_]{0,31}$/, 'must be a short snake_case code');

export const CreateAlarmBody = z
  .object({
    label: Label.optional(),
    time: HHmm,
    timeZone: ProfileTimeZone.meta({ description: "The anchor zone: the creator's" }),
    days: Days,
    onceAt: OnceAt.optional(),
    ringOnMyPhone: z.boolean().optional().meta({ description: 'Default true. The partner always starts on' }),
    snoozeMinutes: SnoozeMinutes.optional().meta({ description: 'Default 10' }),
    mode: z.enum(ALARM_MODES).optional().meta({ description: "Default 'together'" }),
  })
  .strict();

export const PatchAlarmBody = z
  .object({
    expectedVersion: z.int().min(1).meta({ description: 'Required: 409 version_conflict when stale' }),
    label: Label.optional(),
    time: HHmm.optional(),
    timeZone: ProfileTimeZone.optional(),
    days: Days.optional(),
    onceAt: OnceAt.optional(),
    enabled: z.boolean().optional(),
    snoozeMinutes: SnoozeMinutes.optional(),
  })
  .strict();

export const PutMyAlarmBody = z
  .object({
    ring: z.boolean().optional().meta({ description: 'false: "not on my phone"' }),
    skip: z
      .object({
        occurrence: Instant.meta({ description: 'The ring to skip (or un-skip), within the next 366 days' }),
        skip: z.boolean(),
      })
      .strict()
      .optional(),
  })
  .strict()
  .refine((b) => b.ring !== undefined || b.skip !== undefined, 'send ring or skip');

export const AlarmReportBody = z
  .object({
    items: z
      .array(
        z
          .object({
            alarmId: IdString,
            ready: z.boolean(),
            reason: Reason.nullable()
              .optional()
              .meta({ description: 'Why not ready; null (default) when ready' }),
            armedFor: Instant.nullable().optional().meta({ description: 'The next ring this phone has set' }),
          })
          .strict(),
      )
      .max(MAX_REPORT_ITEMS)
      .refine((items) => unique(items.map((i) => i.alarmId)), 'alarm ids must be unique'),
  })
  .strict();

export const WakeBody = z
  .object({
    occurrence: Instant.meta({ description: 'The ring this is about' }),
    outcome: z.enum(WAKE_OUTCOMES),
    at: Instant.meta({ description: 'When the person got up (or the alarm gave up)' }),
    snoozes: z.int().min(0).max(20),
  })
  .strict();

export type CreateAlarmInput = z.infer<typeof CreateAlarmBody>;
export type PatchAlarmInput = z.infer<typeof PatchAlarmBody>;
export type PutMyAlarmInput = z.infer<typeof PutMyAlarmBody>;
export type AlarmReportInput = z.infer<typeof AlarmReportBody>;
export type WakeInput = z.infer<typeof WakeBody>;

export const AlarmResponse = z.object({ alarm: Alarm });
export const AlarmListResponse = z.object({ items: z.array(Alarm) });
