import type { Env } from '../../config/env.js';
import { LocalStorageDriver } from './local.js';
import { R2StorageDriver } from './r2.js';
import type { StorageService } from './types.js';

export const LOCAL_STORAGE_ROUTE_PREFIX = '/api/v1/_local-storage';

export function createStorage(env: Env, now: () => Date): StorageService {
  if (env.storage.driver === 'r2') {
    return new R2StorageDriver(env.storage, now);
  }
  return new LocalStorageDriver(
    {
      dir: env.storage.dir,
      publicBaseUrl: env.publicBaseUrl,
      secret: env.jwtSecret,
      routePrefix: LOCAL_STORAGE_ROUTE_PREFIX,
    },
    now,
  );
}

export { LocalStorageDriver } from './local.js';
export { registerLocalStorageRoutes } from './local-routes.js';
export { R2StorageDriver } from './r2.js';
export * from './types.js';
