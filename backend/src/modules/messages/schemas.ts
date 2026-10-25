import { z } from 'zod';
import { IdString, IsoDateTime, Message, MessageType, PaginationQuery, UuidV4 } from '../shared/schemas.js';

export const CreateMessageBody = z
  .object({
    type: MessageType,
    text: z.string().trim().max(2000).optional(),
    mediaId: IdString.optional(),
    idempotencyKey: UuidV4,
    viewOnce: z
      .boolean()
      .optional()
      .meta({ description: 'View-once photo. true is only allowed for type photo (else 400)' }),
  })
  .strict()
  .superRefine((v, ctx) => {
    if (v.viewOnce === true && v.type !== 'photo') {
      ctx.addIssue({
        code: 'custom',
        path: ['viewOnce'],
        message: 'viewOnce is only allowed for type photo',
      });
    }
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
export const OpenMessageResponse = z.object({
  url: z.string().meta({ description: 'Signed GET URL for the photo; valid once-ish for a short TTL' }),
  expiresAt: IsoDateTime,
  message: Message,
});
