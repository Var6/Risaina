import { existsSync } from 'node:fs';
import { MongoMemoryServer } from 'mongodb-memory-server';
import type { TestProject } from 'vitest/node';

declare module 'vitest' {
  export interface ProvidedContext {
    mongoUri: string;
  }
}

const SYSTEM_MONGOD = '/opt/homebrew/bin/mongod';

/**
 * Starts ONE in-memory mongod for the whole run and hands its URI to every test file via
 * `inject('mongoUri')`; each file uses its own database name so files run in parallel.
 * The system binary is used when present so nothing is downloaded.
 */
export default async function globalSetup(project: TestProject): Promise<() => Promise<void>> {
  if (existsSync(SYSTEM_MONGOD)) {
    process.env.MONGOMS_SYSTEM_BINARY = SYSTEM_MONGOD;
    process.env.MONGOMS_SYSTEM_BINARY_VERSION_CHECK = 'false';
  }
  const server = await MongoMemoryServer.create({ instance: { storageEngine: 'wiredTiger' } });
  project.provide('mongoUri', server.getUri());
  return async () => {
    await server.stop({ doCleanup: true, force: true });
  };
}
