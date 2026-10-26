import { z } from 'zod';
import { IsoDateTime, IsoDateTimeNullable } from '../shared/schemas.js';

export const DeviceUpsertBody = z
  .object({
    deviceId: z.string().trim().min(1).max(128),
    platform: z.literal('android'),
    token: z.string().min(1).max(4096),
    appVersion: z.string().trim().min(1).max(40),
    model: z.string().trim().max(80).optional(),
  })
  .strict();

export const DeviceParams = z.object({ deviceId: z.string().trim().min(1).max(128) });

// --- delivery health (stream: delivery) ---------------------------------------------------------

const Priority = z.enum(['high', 'normal']);

/** Short, non-sensitive codes describing why pushes may arrive late on a phone. */
export const HealthFlag = z
  .string()
  .regex(/^[a-z][a-z0-9_]{0,39}$/, 'must be a short snake_case code')
  .meta({ example: 'battery_optimized' });

export const TestPushBody = z
  .object({
    deviceId: z
      .string()
      .trim()
      .min(1)
      .max(128)
      .optional()
      .meta({ description: 'Only this phone (default: every phone of the caller)' }),
    delaySeconds: z
      .int()
      .min(0)
      .max(60)
      .default(0)
      .meta({ description: 'Wait before sending, so there is time to lock the phone or close the app' }),
  })
  .strict();

export const TestPushResponse = z.object({
  scheduled: z.boolean(),
  devices: z.int().min(0),
  reason: z.enum(['push_disabled', 'no_tokens']).nullable(),
});

export const PushAckBody = z
  .object({
    priority: Priority.meta({ description: 'Priority the push arrived with (RemoteMessage.priority)' }),
    originalPriority: Priority.meta({
      description: 'Priority it was sent with (RemoteMessage.originalPriority)',
    }),
    healthFlags: z.array(HealthFlag).max(16).optional(),
  })
  .strict();

export const DeviceHealth = z
  .object({
    deviceId: z.string().nullable().meta({ description: "Only for the caller's own phones" }),
    model: z.string().nullable(),
    appVersion: z.string(),
    updatedAt: IsoDateTime,
    lastPushSentAt: IsoDateTimeNullable,
    lastPushAckAt: IsoDateTimeNullable,
    lastPushPriority: Priority.nullable(),
    lastPushDowngraded: z.boolean(),
    healthFlags: z.array(z.string()),
  })
  .meta({ id: 'DeviceHealth' });

export const DeviceHealthResponse = z.object({
  mine: z.array(DeviceHealth),
  partner: z.array(DeviceHealth),
});
