import { z } from 'zod';
import { DisplayName } from '../auth/schemas.js';
import {
  Couple,
  IdString,
  Memory,
  Message,
  Milestone,
  Partner,
  Ping,
  Preferences,
  PreferencesUpdate,
  User,
} from '../shared/schemas.js';

export const MeResponse = z.object({
  user: User,
  couple: Couple.nullable(),
  partner: Partner.nullable(),
  preferences: Preferences,
});

export const MePatchBody = z
  .object({
    displayName: DisplayName.optional(),
    avatarMediaId: IdString.nullable().optional(),
  })
  .strict();

export const UserResponse = z.object({ user: User });
export const PreferencesResponse = z.object({ preferences: Preferences });
export const PreferencesBody = PreferencesUpdate;

export const DeleteMeBody = z.object({ password: z.string().min(1).max(200) });

export const ExportResponse = z.object({
  exportedAt: z.string(),
  user: User,
  couple: Couple.nullable(),
  pings: z.array(Ping),
  messages: z.array(Message),
  memories: z.array(Memory),
  milestones: z.array(Milestone),
  truncated: z.boolean(),
});
