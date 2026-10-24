import { z } from 'zod';
import { IsoDateTime, Media, MediaCategory } from '../shared/schemas.js';

export const CreateUploadBody = z
  .object({
    category: MediaCategory,
    mimeType: z.string().trim().toLowerCase().min(3).max(100),
    sizeBytes: z.number().int().positive(),
    durationMs: z.number().int().positive().optional(),
    width: z.number().int().positive().max(20000).optional(),
    height: z.number().int().positive().max(20000).optional(),
  })
  .strict();

export const UploadCreated = z.object({
  media: Media,
  upload: z.object({
    url: z.string(),
    method: z.literal('PUT'),
    headers: z.record(z.string(), z.string()),
    expiresAt: IsoDateTime,
  }),
});

export const MediaResponse = z.object({ media: Media });
export const MediaUrlResponse = z.object({ url: z.string(), expiresAt: IsoDateTime });
