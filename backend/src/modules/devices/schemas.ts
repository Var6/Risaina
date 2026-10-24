import { z } from 'zod';

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
