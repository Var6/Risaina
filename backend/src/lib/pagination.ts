import { ObjectId } from 'mongodb';
import { errors } from './errors.js';

/** Opaque cursor = base64url("<createdAt ISO>|<objectId hex>"). Lists are newest-first. */
export interface Cursor {
  createdAt: Date;
  id: ObjectId;
}

export function encodeCursor(createdAt: Date, id: ObjectId): string {
  return Buffer.from(`${createdAt.toISOString()}|${id.toHexString()}`, 'utf8').toString('base64url');
}

export function decodeCursor(raw: string): Cursor {
  let text: string;
  try {
    text = Buffer.from(raw, 'base64url').toString('utf8');
  } catch {
    throw errors.validation('Invalid cursor');
  }
  const [iso, hex] = text.split('|');
  if (!iso || !hex || !/^[a-f0-9]{24}$/.test(hex)) throw errors.validation('Invalid cursor');
  const createdAt = new Date(iso);
  if (Number.isNaN(createdAt.getTime())) throw errors.validation('Invalid cursor');
  return { createdAt, id: new ObjectId(hex) };
}

/** Mongo filter fragment selecting documents strictly before the cursor in (createdAt desc, _id desc). */
export function beforeCursorFilter(cursor: Cursor): Record<string, unknown> {
  return {
    $or: [{ createdAt: { $lt: cursor.createdAt } }, { createdAt: cursor.createdAt, _id: { $lt: cursor.id } }],
  };
}

export function pageResult<TDoc extends { _id: ObjectId; createdAt: Date }, TDto>(
  docs: TDoc[],
  limit: number,
  map: (d: TDoc) => TDto,
): { items: TDto[]; nextCursor: string | null } {
  const hasMore = docs.length > limit;
  const page = hasMore ? docs.slice(0, limit) : docs;
  const last = page[page.length - 1];
  return {
    items: page.map(map),
    nextCursor: hasMore && last ? encodeCursor(last.createdAt, last._id) : null,
  };
}
