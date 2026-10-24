import { z } from 'zod';
import { CalendarDate, Couple, IsoDateTime, Partner, SharedPreferences } from '../shared/schemas.js';

export const CoupleResponse = z.object({ couple: Couple, partner: Partner });
export const CoupleOnlyResponse = z.object({ couple: Couple });

export const CouplePatchBody = z
  .object({
    relationshipStartDate: CalendarDate.nullable().optional(),
    nextMeetingDate: IsoDateTime.refine(
      (s) => !Number.isNaN(new Date(s).getTime()),
      'must be an ISO-8601 datetime',
    )
      .nullable()
      .optional(),
    nextMeetingLabel: z.string().trim().max(60).nullable().optional(),
    sharedPreferences: SharedPreferences.optional(),
  })
  .strict();

export const UnlinkBody = z.object({ confirm: z.literal(true) });
