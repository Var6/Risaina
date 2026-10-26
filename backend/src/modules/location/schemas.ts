import { z } from 'zod';
import { IdString, LiveLocation, LiveLocationReason } from '../shared/schemas.js';

/** Fixes older than this are refused (400): the partner should never be shown a stale place as live. */
export const LIVE_LOCATION_MAX_FIX_AGE_MS = 10 * 60_000;

/**
 * One position from the sharer's phone. `ageMs` (how long ago the phone took the fix, from its
 * monotonic clock) replaces an absolute timestamp, so a phone whose wall clock is wrong is still
 * placed correctly in time. `accuracyM` accepts very coarse cell fixes and is clamped to 50 km.
 */
export const LocationFixInput = z
  .object({
    lat: z.number().min(-90).max(90),
    lng: z.number().min(-180).max(180),
    accuracyM: z.number().min(0).max(1_000_000),
    ageMs: z.number().int().min(0).max(LIVE_LOCATION_MAX_FIX_AGE_MS),
    batteryPct: z.number().int().min(0).max(100).nullable().optional(),
  })
  .strict();

/**
 * Start (or extend) sharing. `mode` is explicit rather than `durationMinutes: null` because the
 * Android client drops null keys (explicitNulls = false): a missing duration must never mean
 * "until I turn it off". The phone generates `sessionId` so a stop sent right after a start can
 * name it, even while the start is still in flight. A retry, Resume or re-register sends the time
 * LEFT as `durationMinutes` (the server sets end = now + duration), which can be a few minutes.
 */
export const StartSharingBody = z
  .object({
    sessionId: IdString,
    mode: z.enum(['timed', 'until_off']),
    durationMinutes: z.number().int().min(1).max(1440).optional(),
    reason: LiveLocationReason.default('manual'),
    sosId: IdString.optional().meta({ description: 'Panic only: the SOS this sharing belongs to' }),
    fix: LocationFixInput.optional(),
  })
  .strict()
  .refine((b) => (b.mode === 'timed') === (b.durationMinutes !== undefined), {
    path: ['durationMinutes'],
    message: 'durationMinutes is required for timed sharing and not allowed for until_off',
  })
  .refine((b) => b.reason === 'panic' || b.sosId === undefined, {
    path: ['sosId'],
    message: 'sosId is only allowed for panic sharing',
  });

export const PutFixBody = z.object({ sessionId: IdString, fix: LocationFixInput }).strict();

export const StopSharingQuery = z
  .object({
    sessionId: IdString.optional(),
    reason: z.enum(['stopped', 'expired']).default('stopped'),
  })
  .strict();

export const SharingResponse = z.object({ sharing: LiveLocation });
export const SharingStateResponse = z.object({
  mine: LiveLocation.nullable(),
  partner: LiveLocation.nullable(),
});
export const PutFixResponse = z.object({
  accepted: z
    .boolean()
    .meta({ description: 'false: the fix was older than the stored one (the check-in still counts)' }),
});

export type StartSharingInput = z.infer<typeof StartSharingBody>;
export type LocationFixInputType = z.infer<typeof LocationFixInput>;
