import { z } from 'zod';
import { Emoji, IdString, WidgetBoard } from '../shared/schemas.js';

/** Trimmed text; '' (after trim) clears the field like null does. */
const ClearableText = (max: number) =>
  z
    .string()
    .trim()
    .max(max)
    .transform((s) => (s.length === 0 ? null : s))
    .nullable();

export const PutWidgetBoardBody = z
  .object({
    note: ClearableText(140).optional().meta({ description: '≤ 140 chars; null or "" clears' }),
    emoji: Emoji.nullable().optional().meta({ description: '1–2 grapheme clusters; null clears' }),
    mood: ClearableText(40).optional().meta({ description: '≤ 40 chars; null or "" clears' }),
    photoMediaId: IdString.nullable().optional().meta({
      description:
        'Ready photo|wallpaper media of the same couple, not view-once; null clears. Fetched with GET /media/:id/url',
    }),
  })
  .strict()
  .refine((b) => Object.values(b).some((v) => v !== undefined), {
    message: 'send at least one of note, emoji, mood, photoMediaId',
  });

export type PutWidgetBoardInput = z.infer<typeof PutWidgetBoardBody>;

export const WidgetBoardsResponse = z.object({
  mine: WidgetBoard.nullable().meta({ description: 'What I set for my partner' }),
  forMe: WidgetBoard.nullable().meta({ description: 'What my partner set for me' }),
});

export const WidgetBoardResponse = z.object({ board: WidgetBoard });
