import { z } from 'zod';
import { IdString, Message, MessageType, PaginationQuery, UuidV4 } from '../shared/schemas.js';

export const CreateMessageBody = z
  .object({
    type: MessageType,
    text: z.string().trim().max(2000).optional(),
    mediaId: IdString.optional(),
    idempotencyKey: UuidV4,
  })
  .strict()
  .superRefine((v, ctx) => {
    if (v.type === 'text' || v.type === 'emoji') {
      if (!v.text || v.text.length === 0) {
        ctx.addIssue({ code: 'custom', path: ['text'], message: `text is required for type ${v.type}` });
      }
      if (v.mediaId)
        ctx.addIssue({
          code: 'custom',
          path: ['mediaId'],
          message: `mediaId is not allowed for type ${v.type}`,
        });
    } else {
      if (!v.mediaId)
        ctx.addIssue({
          code: 'custom',
          path: ['mediaId'],
          message: `mediaId is required for type ${v.type}`,
        });
    }
  });

export const MessageResponse = z.object({ message: Message });
export const MessageListQuery = PaginationQuery;
export const MessageListResponse = z.object({ items: z.array(Message), nextCursor: z.string().nullable() });
