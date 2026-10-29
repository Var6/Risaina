import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { errors } from '../../lib/errors.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams, NoContent } from '../shared/schemas.js';
import {
  AlarmListResponse,
  AlarmReportBody,
  AlarmResponse,
  CreateAlarmBody,
  MAX_ALARMS,
  PatchAlarmBody,
  PutMyAlarmBody,
  WakeBody,
} from './schemas.js';
import { AlarmService } from './service.js';

const TAG = 'Alarms';

export const alarmRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new AlarmService(ctx);
  const security = [{ bearerAuth: [] }];
  const errs = { 401: ErrorEnvelope, 404: ErrorEnvelope, 409: ErrorEnvelope };

  // One per-user budget for every write, reports included, keyed after authentication.
  const writeLimiter = app.createRateLimit({
    max: ctx.env.rateLimit.alarmWritesPerMinute,
    timeWindow: 60_000,
    keyGenerator: (req) => `alarms:${req.user?.id ?? req.ip}`,
  });
  const limitWrites = async (req: FastifyRequest) => {
    const res = await writeLimiter(req);
    if (!res.isAllowed && res.isExceeded) {
      throw errors.rateLimited(res.ttlInSeconds, 'Too many alarm changes; slow down');
    }
  };
  const guarded = { preValidation: [app.authenticate], preHandler: [app.requireCouple] };
  const guardedWrite = { preValidation: [app.authenticate], preHandler: [app.requireCouple, limitWrites] };
  const writeErrs = { 400: ErrorEnvelope, 429: ErrorEnvelope, ...errs };

  r.get(
    '/alarms',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: `The couple's wake-together alarms, oldest first (at most ${MAX_ALARMS})`,
        security,
        response: { 200: AlarmListResponse, ...errs },
      },
    },
    async (req) => service.list(req.couple!),
  );

  r.post(
    '/alarms',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: 'Set a wake-together alarm (rings on both phones at the same moment)',
        description:
          'Each ring is `time` in `timeZone` on one of `days`; with days [] it rings once, at onceAt (required, ' +
          'within 366 days, at `time` in the zone). The partner starts with ring: true. Emits alarm.created ' +
          `{ alarm, notify }. ${MAX_ALARMS} per couple (409 conflict).`,
        security,
        body: CreateAlarmBody,
        response: { 201: AlarmResponse, ...writeErrs },
      },
    },
    async (req, reply) => reply.code(201).send(await service.create(req.couple!, req.user!.id, req.body)),
  );

  r.post(
    '/alarms/report',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: "This phone's readiness for its alarms (after arming them)",
        description:
          "Stores ready / reason / armedFor on the caller's entry of each alarm. Only a change of ready or " +
          "reason emits alarm.updated (change 'member'); armedFor alone is stored silently. Unknown or " +
          'deleted alarm ids are skipped. At most 10 items.',
        security,
        body: AlarmReportBody,
        response: { 204: NoContent, ...writeErrs },
      },
    },
    async (req, reply) => {
      await service.report(req.couple!, req.user!.id, req.body);
      return reply.code(204).send(null);
    },
  );

  r.patch(
    '/alarms/:id',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: 'Change the shared alarm (expectedVersion required)',
        description:
          'A stale expectedVersion: 409 { error: { code: "version_conflict", details: { alarm } } }. Emits ' +
          "alarm.updated { alarm, change: 'enabled' (only enabled changed) | 'edited', notify }.",
        security,
        params: IdParams,
        body: PatchAlarmBody,
        response: { 200: AlarmResponse, ...writeErrs },
      },
    },
    async (req) => service.update(req.couple!, req.user!.id, parseId(req.params.id, 'Alarm'), req.body),
  );

  r.delete(
    '/alarms/:id',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: 'Delete an alarm for both (emits alarm.deleted { alarmId })',
        security,
        params: IdParams,
        response: { 204: NoContent, 429: ErrorEnvelope, ...errs },
      },
    },
    async (req, reply) => {
      await service.delete(req.couple!, req.user!.id, parseId(req.params.id, 'Alarm'));
      return reply.code(204).send(null);
    },
  );

  r.put(
    '/alarms/:id/me',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: 'My own side of an alarm: ring on my phone or not, skip one ring',
        description:
          "Changes only the caller's member entry. Emits alarm.updated { change: 'member', notify: false } when " +
          'something changed; at most 7 skipped rings.',
        security,
        params: IdParams,
        body: PutMyAlarmBody,
        response: { 200: AlarmResponse, ...writeErrs },
      },
    },
    async (req) => service.updateMine(req.couple!, req.user!.id, parseId(req.params.id, 'Alarm'), req.body),
  );

  r.post(
    '/alarms/:id/wake',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: '"I\'m up" (or missed) for one ring',
        description:
          'Idempotent per member and occurrence: a repeat (or an earlier occurrence than the stored one) ' +
          'changes nothing and emits nothing. Otherwise stores lastWake and emits alarm.woke ' +
          '{ alarmId, userId, occurrence, outcome, at, snoozes }. The version does not change.',
        security,
        params: IdParams,
        body: WakeBody,
        response: { 200: AlarmResponse, ...writeErrs },
      },
    },
    async (req) => service.wake(req.couple!, req.user!.id, parseId(req.params.id, 'Alarm'), req.body),
  );
};
