import { z } from 'zod';
import { List, ListKind } from '../shared/schemas.js';

export const MAX_LIST_ITEMS = 200;

const Title = z.string().trim().min(1).max(120);
const Body = z.string().max(10_000);
const ItemText = z.string().trim().min(1).max(300);
const ItemId = z
  .string()
  .regex(/^[A-Za-z0-9_-]{1,32}$/, 'must be 1-32 characters of [A-Za-z0-9_-]')
  .meta({ description: 'Keep the id the server assigned; unknown ids are treated as new items' });

// Item objects are not strict: clients may echo server-owned keys (doneBy, doneAt; id on create).
// They are stripped and ignored, never trusted.
const IGNORED_ITEM_KEYS = {
  description: 'doneBy / doneAt (and id on create) may be sent but are ignored: the server owns them',
};

export const CreateListBody = z
  .object({
    title: Title,
    kind: ListKind,
    body: Body.optional(),
    items: z
      .array(z.object({ text: ItemText, done: z.boolean().optional() }).meta(IGNORED_ITEM_KEYS))
      .max(MAX_LIST_ITEMS)
      .optional(),
    pinned: z.boolean().optional(),
  })
  .strict();

const PatchItem = z
  .object({ id: ItemId.optional(), text: ItemText, done: z.boolean() })
  .meta(IGNORED_ITEM_KEYS);

export const PatchListBody = z
  .object({
    title: Title.optional(),
    body: Body.optional(),
    items: z
      .array(PatchItem)
      .max(MAX_LIST_ITEMS)
      .refine((items) => {
        const ids = items.flatMap((i) => (i.id === undefined ? [] : [i.id]));
        return new Set(ids).size === ids.length;
      }, 'item ids must be unique')
      .optional()
      .meta({ description: 'Replaces the whole items array' }),
    pinned: z.boolean().optional(),
    expectedVersion: z
      .number()
      .int()
      .min(1)
      .optional()
      .meta({ description: 'When given and not the current version: 409 version_conflict' }),
  })
  .strict();

export type CreateListInput = z.infer<typeof CreateListBody>;
export type PatchListInput = z.infer<typeof PatchListBody>;

export const ListResponse = z.object({ list: List });
export const ListListResponse = z.object({ items: z.array(List) });
