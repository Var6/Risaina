import { z } from 'zod';
import { Emoji, PaginationQuery, Ping, PingType, UuidV4 } from '../shared/schemas.js';

export const CreatePingBody = z
  .object({
    type: PingType,
    emoji: Emoji,
    message: z.string().trim().max(140).optional(),
    idempotencyKey: UuidV4,
  })
  .strict();

export const PingResponse = z.object({ ping: Ping });
export const PingListQuery = PaginationQuery;
export const PingListResponse = z.object({ items: z.array(Ping), nextCursor: z.string().nullable() });
