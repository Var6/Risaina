import { z } from 'zod';
import { Sos } from '../shared/schemas.js';

export const CreateSosBody = z
  .object({
    message: z.string().trim().max(140).optional().meta({ description: '≤ 140 chars; "" is stored as null' }),
    panic: z.boolean().optional().meta({
      description: 'Panic alert: the sender also starts sharing live location (stored only when true)',
    }),
    idempotencyKey: z
      .string()
      .min(1)
      .max(64)
      .optional()
      .meta({ description: 'Retry key: a repeat with the same key returns the first SOS and emits nothing' }),
  })
  .strict();

export const SosListQuery = z.object({
  limit: z.coerce.number().int().min(1).max(20).default(20),
});

/**
 * Device state the recipient's phone reports when the alarm starts. Diagnostics only, never content:
 * the user's phone runs a sideloaded build without adb, so this is how a silent alarm gets explained.
 * Unknown keys are dropped (not refused) so a newer app never fails its receipt on an older server.
 */
export const SosDeliveredBody = z.object({
  priority: z.string().max(16).nullable().optional(),
  originalPriority: z.string().max(16).nullable().optional(),
  latencyMs: z.number().int().min(0).max(86_400_000).nullable().optional(),
  alarmMuted: z.boolean().nullable().optional(),
  alarmVolume: z.number().int().min(0).max(100).nullable().optional(),
  ringerMode: z.string().max(16).nullable().optional(),
  interruptionFilter: z.string().max(16).nullable().optional(),
  notificationsEnabled: z.boolean().nullable().optional(),
  canUseFullScreenIntent: z.boolean().nullable().optional(),
  fgsStarted: z.boolean().nullable().optional(),
});

export const SosTestBody = z
  .object({
    delaySeconds: z
      .number()
      .int()
      .min(0)
      .max(60)
      .default(15)
      .meta({ description: 'Wait before pushing, so the phone can be locked or the app closed first' }),
  })
  .strict();

export const SosResponse = z.object({ sos: Sos });
export const SosListResponse = z.object({ items: z.array(Sos) });
export const SosTestResponse = z.object({
  queued: z.boolean(),
  devices: z.number().int().meta({ description: 'How many of my devices will receive the test' }),
});

export type SosDeliveredInput = z.infer<typeof SosDeliveredBody>;
