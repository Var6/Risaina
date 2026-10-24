import { z } from 'zod';
import { Event } from '../shared/schemas.js';

export const EventsQuery = z.object({
  since: z.coerce.number().int().min(0).default(0).meta({ description: 'Last seq the client has applied' }),
  limit: z.coerce.number().int().min(1).max(500).default(200),
});

export const EventsResponse = z.object({ items: z.array(Event), latestSeq: z.number().int() });
