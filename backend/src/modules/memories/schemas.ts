import { z } from 'zod';
import { IdString, IsoDateTime, Memory, PaginationQuery } from '../shared/schemas.js';

export const CreateMemoryBody = z
  .object({
    mediaId: IdString,
    caption: z.string().trim().max(500).optional(),
    takenAt: IsoDateTime.refine(
      (s) => !Number.isNaN(new Date(s).getTime()),
      'must be an ISO-8601 datetime',
    ).optional(),
  })
  .strict();

export const PatchMemoryBody = z
  .object({
    caption: z.string().trim().max(500).nullable().optional(),
    pinned: z.boolean().optional(),
  })
  .strict();

export const MemoryListQuery = PaginationQuery.extend({
  pinned: z
    .enum(['true', 'false'])
    .transform((s) => s === 'true')
    .optional(),
});

export const MemoryResponse = z.object({ memory: Memory });
export const MemoryListResponse = z.object({ items: z.array(Memory), nextCursor: z.string().nullable() });
