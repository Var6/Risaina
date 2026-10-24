import { z } from 'zod';
import { Couple, IdString, IsoDateTime, Partner } from '../shared/schemas.js';

/** The client sends `{}`; any extra keys are rejected. */
export const CreateInvitationBody = z.object({}).strict().optional();

export const InvitationCreated = z.object({
  invitation: z.object({
    id: IdString,
    code: z.string().meta({ description: 'Shown once; 10-character Crockford base32' }),
    expiresAt: IsoDateTime,
  }),
});

export const CurrentInvitation = z.object({
  invitation: z.object({ id: IdString, expiresAt: IsoDateTime, createdAt: IsoDateTime }).nullable(),
});

export const AcceptBody = z.object({
  code: z
    .string()
    .trim()
    .min(10)
    .max(14)
    .meta({ description: 'Case-insensitive; separators and confusable letters are normalised' }),
});

export const AcceptResponse = z.object({ couple: Couple, partner: Partner });
