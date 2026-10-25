import { z } from 'zod';
import { Sos } from '../shared/schemas.js';

export const CreateSosBody = z
  .object({
    message: z.string().trim().max(140).optional().meta({ description: '≤ 140 chars; "" is stored as null' }),
  })
  .strict();

export const SosListQuery = z.object({
  limit: z.coerce.number().int().min(1).max(20).default(20),
});

export const SosResponse = z.object({ sos: Sos });
export const SosListResponse = z.object({ items: z.array(Sos) });
