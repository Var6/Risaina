import { z } from 'zod';
import { CalendarDate, Milestone, MilestoneKind } from '../shared/schemas.js';

export const CreateMilestoneBody = z
  .object({
    title: z.string().trim().min(1).max(120),
    date: CalendarDate,
    kind: MilestoneKind,
  })
  .strict();

export const PatchMilestoneBody = z
  .object({
    title: z.string().trim().min(1).max(120).optional(),
    date: CalendarDate.optional(),
    kind: MilestoneKind.optional(),
  })
  .strict();

export const MilestoneResponse = z.object({ milestone: Milestone });
export const MilestoneListResponse = z.object({ items: z.array(Milestone) });
