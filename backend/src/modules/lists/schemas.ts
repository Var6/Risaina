import { z } from 'zod';
import { List, ListKind, ListMarker } from '../shared/schemas.js';

export const MAX_LIST_ITEMS = 200;
export const MAX_ITEM_TEXT = 300;
export const MAX_BODY = 10_000;

const Title = z.string().trim().min(1).max(120);
const Body = z.string().max(MAX_BODY);
const ItemText = z.string().trim().min(1).max(MAX_ITEM_TEXT);
const ItemId = z
  .string()
  .regex(/^[A-Za-z0-9_-]{1,32}$/, 'must be 1-32 characters of [A-Za-z0-9_-]')
  .meta({ description: 'Keep the id the server assigned; unknown ids are treated as new items' });

// Item objects are not strict: clients may echo server-owned keys (doneBy, doneAt, addedBy, addedAt;
// id on create). They are stripped and ignored, never trusted.
const IGNORED_ITEM_KEYS = {
  description:
    'doneBy / doneAt / addedBy / addedAt (and id on create) may be sent but are ignored: the server owns them',
};

const Marker = ListMarker.meta({ description: "Row marker: 'none', 'number' or 'bullet'" });
const Checkboxes = z.boolean().meta({ description: 'Rows show checkboxes' });

const ExpectedVersion = z
  .number()
  .int()
  .min(1)
  .optional()
  .meta({ description: 'When given and not the current version: 409 version_conflict' });

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
    marker: Marker.optional().meta({ description: "Default 'none'" }),
    checkboxes: Checkboxes.optional().meta({ description: 'Default true' }),
    clientKey: z.string().min(1).max(64).optional().meta({
      description:
        'Retry key chosen by the phone: a repeat with the same key returns 200 with the list it created and emits nothing',
    }),
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
    marker: Marker.optional(),
    checkboxes: Checkboxes.optional(),
    expectedVersion: ExpectedVersion,
  })
  .strict();

export const ConvertListBody = z
  .object({
    to: ListKind.meta({ description: 'The kind the list becomes' }),
    marker: Marker.optional().meta({ description: 'Format of the converted list (unchanged when omitted)' }),
    checkboxes: Checkboxes.optional().meta({ description: 'Unchanged when omitted' }),
    expectedVersion: ExpectedVersion,
  })
  .strict();

export type CreateListInput = z.infer<typeof CreateListBody>;
export type PatchListInput = z.infer<typeof PatchListBody>;
export type ConvertListInput = z.infer<typeof ConvertListBody>;

export const ListResponse = z.object({ list: List });
export const ListListResponse = z.object({ items: z.array(List) });
