import type { Env } from '../../config/env.js';
import { DisabledPushService } from './disabled.js';
import { FcmPushService } from './fcm.js';
import type { PushService } from './types.js';

export function createPushService(env: Env): PushService {
  if (env.push.enabled) return new FcmPushService(env.push.serviceAccount);
  return new DisabledPushService(env.push.reason);
}

export { DisabledPushService } from './disabled.js';
export { FcmPushService, fcmAndroidConfig } from './fcm.js';
export * from './policy.js';
export * from './types.js';
