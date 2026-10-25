import type { Db } from 'mongodb';
import { ensureIndexes } from './indexes.js';
import { ensureValidators, staleEventTypes, validatorWarnings } from './validators.js';

export { connectMongo, pingMongo } from './client.js';
export { COLLECTION_NAMES, type Collections, getCollections } from './collections.js';
export { ensureIndexes, INDEXES } from './indexes.js';
export * from './types.js';
export { ensureValidators, staleEventTypes, VALIDATORS, validatorWarnings } from './validators.js';

/** Boot-time schema setup: validators first (creates collections), then indexes. */
export async function prepareDatabase(db: Db): Promise<void> {
  await ensureValidators(db);
  if (validatorWarnings.length > 0) {
    console.warn(
      `[risaina] Could not update schema validators for: ${validatorWarnings.join(', ')}. ` +
        'The database user needs the dbAdmin role on this database (see docs/GO_LIVE.md, "Database permissions"). ' +
        'Until then, Google-only accounts cannot be created.',
    );
  }
  if (staleEventTypes.length > 0) {
    console.error(
      `[risaina] The events collection validator predates these event types: ${staleEventTypes.join(', ')}. ` +
        'MongoDB rejects them, so they are delivered live over WebSocket only (no replay via GET /events or ' +
        'sync, no push). Grant the database user dbAdmin on this database and restart once to fix.',
    );
  }
  await ensureIndexes(db);
}
