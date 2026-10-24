import { ObjectId } from 'mongodb';
import { errors } from './errors.js';

export const OBJECT_ID_REGEX = /^[a-f0-9]{24}$/;

export function newId(): ObjectId {
  return new ObjectId();
}

export function idToString(id: ObjectId): string {
  return id.toHexString();
}

/** Parse a client-supplied id; throws 404 (not validation) so ids never leak existence hints. */
export function parseId(value: string, what = 'Resource'): ObjectId {
  if (!OBJECT_ID_REGEX.test(value)) throw errors.notFound(what);
  return new ObjectId(value);
}

export function sameId(a: ObjectId | string, b: ObjectId | string): boolean {
  return a.toString() === b.toString();
}
