import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { errors } from '../../lib/errors.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams, NoContent } from '../shared/schemas.js';
import {
  CalendarEntryResponse,
  CalendarListResponse,
  CreateCalendarBody,
  MAX_CALENDAR_ENTRIES,
  PatchCalendarBody,
} from './schemas.js';
import { CalendarService } from './service.js';

const TAG = 'Calendar';

export const calendarRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new CalendarService(ctx);
  const security = [{ bearerAuth: [] }];
  const errs = { 401: ErrorEnvelope, 404: ErrorEnvelope, 409: ErrorEnvelope };

  // One per-user budget shared by POST/PATCH/DELETE, keyed after authentication (as lists do).
  const writeLimiter = app.createRateLimit({
    max: ctx.env.rateLimit.calendarWritesPerMinute,
    timeWindow: 60_000,
    keyGenerator: (req) => `calendar:${req.user?.id ?? req.ip}`,
  });
  const limitWrites = async (req: FastifyRequest) => {
    const res = await writeLimiter(req);
    if (!res.isAllowed && res.isExceeded) {
      throw errors.rateLimited(res.ttlInSeconds, 'Too many calendar changes; slow down');
    }
  };
  const guarded = { preValidation: [app.authenticate], preHandler: [app.requireCouple] };
  const guardedWrite = { preValidation: [app.authenticate], preHandler: [app.requireCouple, limitWrites] };

  r.get(
    '/calendar',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: `The couple's calendar entries, by date (all of them; at most ${MAX_CALENDAR_ENTRIES})`,
        security,
        response: { 200: CalendarListResponse, ...errs },
      },
    },
    async (req) => service.list(req.couple!),
  );

  r.post(
    '/calendar',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: 'Add an event or a dated to-do',
        description:
          'timeZone and durationMinutes need a time; endDate is for all-day entries; a to-do cannot repeat; ' +
          'repeatUntil ≥ date; remindUserIds must be members. A source must be a message (not view-once) or ' +
          'a non-deleted list of the couple (404 otherwise). With clientKey, a repeat answers 200 with the ' +
          'first entry and emits nothing. Emits calendar.created { entry, notify }.',
        security,
        body: CreateCalendarBody,
        response: {
          201: CalendarEntryResponse,
          200: CalendarEntryResponse,
          400: ErrorEnvelope,
          429: ErrorEnvelope,
          ...errs,
        },
      },
    },
    async (req, reply) => {
      const { entry, created } = await service.create(req.couple!, req.user!.id, req.body);
      return reply.code(created ? 201 : 200).send({ entry });
    },
  );

  r.patch(
    '/calendar/:id',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: 'Change an entry (partial), or tick a to-do with done',
        description:
          'The rules of POST apply to the entry as it will be stored. Increments version and emits ' +
          'calendar.updated { entry, notify }. When expectedVersion is given and differs from the current ' +
          'version: 409 { error: { code: "version_conflict", details: { entry } } } with the current entry.',
        security,
        params: IdParams,
        body: PatchCalendarBody,
        response: { 200: CalendarEntryResponse, 400: ErrorEnvelope, 429: ErrorEnvelope, ...errs },
      },
    },
    async (req) =>
      service.update(req.couple!, req.user!.id, parseId(req.params.id, 'Calendar entry'), req.body),
  );

  r.delete(
    '/calendar/:id',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: 'Delete an entry (emits calendar.deleted { entryId })',
        security,
        params: IdParams,
        response: { 204: NoContent, 429: ErrorEnvelope, ...errs },
      },
    },
    async (req, reply) => {
      await service.delete(req.couple!, req.user!.id, parseId(req.params.id, 'Calendar entry'));
      return reply.code(204).send(null);
    },
  );
};
