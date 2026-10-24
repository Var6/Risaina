import type { Db } from 'mongodb';
import { ensureIndexes } from './indexes.js';
import { ensureValidators } from './validators.js';

export { connectMongo, pingMongo } from './client.js';
export { COLLECTION_NAMES, type Collections, getCollections } from './collections.js';
export { ensureIndexes, INDEXES } from './indexes.js';
export * from './types.js';
export { ensureValidators, VALIDATORS } from './validators.js';

/** Boot-time schema setup: validators first (creates collections), then indexes. */
export async function prepareDatabase(db: Db): Promise<void> {
  await ensureValidators(db);
  await ensureIndexes(db);
}
