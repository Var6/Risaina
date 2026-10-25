import { z } from 'zod';
import { DisplayName } from '../auth/schemas.js';
import {
  Birthday,
  Couple,
  IdString,
  List,
  Memory,
  Message,
  Milestone,
  Partner,
  Ping,
  Preferences,
  PreferencesUpdate,
  ProfileTimeZone,
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
    birthday: Birthday.nullable().optional(),
    timeZone: ProfileTimeZone.nullable().optional(),
    shareLocalTime: z.boolean().optional(),
  })
  .strict();

export type MePatch = z.infer<typeof MePatchBody>;

export const UserResponse = z.object({ user: User });
export const PreferencesResponse = z.object({ preferences: Preferences });
export const PreferencesBody = PreferencesUpdate;

export const DeleteMeBody = z
  .object({
    password: z
      .string()
      .min(1)
      .max(200)
      .optional()
      .meta({ description: 'Required when the account has a password (user.hasPassword)' }),
    confirm: z
      .literal('DELETE')
      .optional()
      .meta({ description: 'Required instead of password for Google-only accounts (no password)' }),
  })
  .refine((b) => b.password !== undefined || b.confirm !== undefined, {
    message: 'provide password, or confirm: "DELETE" for an account without a password',
  });

export const ExportResponse = z.object({
  exportedAt: z.string(),
  user: User,
  couple: Couple.nullable(),
  pings: z.array(Ping),
  messages: z.array(Message),
  memories: z.array(Memory),
  milestones: z.array(Milestone),
  lists: z.array(List),
  truncated: z.boolean(),
});
