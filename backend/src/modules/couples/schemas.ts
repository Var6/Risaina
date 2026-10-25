import { z } from 'zod';
import {
  CalendarDate,
  ChatWallpaperStyle,
  Couple,
  IdString,
  IsoDateTime,
  Partner,
  SharedPreferences,
} from '../shared/schemas.js';

export const CoupleResponse = z.object({ couple: Couple, partner: Partner });
export const CoupleOnlyResponse = z.object({ couple: Couple });

export const ChatWallpaperInput = z
  .object({
    style: ChatWallpaperStyle,
    photoMediaId: IdString.nullable().optional().meta({
      description:
        'Required for PHOTO (ready photo|wallpaper media of this couple, not view-once); must be omitted/null otherwise',
    }),
  })
  .strict()
  .superRefine((w, c) => {
    if (w.style === 'PHOTO' && !w.photoMediaId) {
      c.addIssue({ code: 'custom', path: ['photoMediaId'], message: 'photoMediaId is required for PHOTO' });
    }
    if (w.style !== 'PHOTO' && w.photoMediaId) {
      c.addIssue({
        code: 'custom',
        path: ['photoMediaId'],
        message: 'photoMediaId is only allowed for PHOTO',
      });
    }
  });

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
    sharedPreferences: z
      .preprocess((v) => {
        // chatWallpaper is server-managed (PATCH { chatWallpaper }); clients echoing the couple's
        // sharedPreferences back must not fail on it, so the key is dropped here.
        if (v && typeof v === 'object' && !Array.isArray(v) && 'chatWallpaper' in v) {
          const { chatWallpaper: _ignored, ...rest } = v as Record<string, unknown>;
          return rest;
        }
        return v;
      }, SharedPreferences)
      .optional()
      .meta({ description: 'Replaces the flat map; a chatWallpaper key is ignored (use chatWallpaper)' }),
    chatWallpaper: ChatWallpaperInput.nullable()
      .optional()
      .meta({ description: 'Shared chat wallpaper for both partners; null resets to the app default' }),
  })
  .strict();

export const UnlinkBody = z.object({ confirm: z.literal(true) });
