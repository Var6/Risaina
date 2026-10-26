import { type App, cert, deleteApp, initializeApp } from 'firebase-admin/app';
import { getMessaging, type Messaging } from 'firebase-admin/messaging';
import type { FirebaseServiceAccount } from '../../config/env.js';
import type { PushData, PushSendOptions, PushSendResult, PushService } from './types.js';

const UNREGISTERED_CODES = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
  'messaging/invalid-argument',
]);

/** FCM's default for this app: keep trying to deliver for an hour. */
export const DEFAULT_PUSH_TTL_MS = 60 * 60 * 1000;

/**
 * FCM via firebase-admin. Sends data-only messages at the priority the outbox chose for the event
 * type (services/push/policy.ts). Never logs tokens or payloads.
 * NOTE: not exercised against a live Firebase project in this repository (no credentials).
 */
export class FcmPushService implements PushService {
  readonly enabled = true;
  readonly provider = 'fcm' as const;
  private readonly app: App;
  private readonly messaging: Messaging;

  constructor(serviceAccount: FirebaseServiceAccount) {
    this.app = initializeApp(
      {
        credential: cert({
          projectId: serviceAccount.projectId,
          clientEmail: serviceAccount.clientEmail,
          privateKey: serviceAccount.privateKey,
        }),
      },
      `risaina-${Date.now()}`,
    );
    this.messaging = getMessaging(this.app);
  }

  async send(
    tokens: string[],
    data: PushData,
    opts: PushSendOptions = { priority: 'high' },
  ): Promise<PushSendResult> {
    if (tokens.length === 0) {
      return {
        successCount: 0,
        failureCount: 0,
        unregisteredTokens: [],
        acceptedTokens: [],
        retryable: false,
        errorSummary: null,
      };
    }
    const res = await this.messaging.sendEachForMulticast({
      tokens,
      data,
      android: fcmAndroidConfig(opts),
    });
    const unregisteredTokens: string[] = [];
    const acceptedTokens: string[] = [];
    let retryable = false;
    let errorSummary: string | null = null;
    res.responses.forEach((r, i) => {
      if (r.success) {
        const t = tokens[i];
        if (t) acceptedTokens.push(t);
        return;
      }
      const code = r.error?.code ?? 'unknown';
      if (UNREGISTERED_CODES.has(code)) {
        const t = tokens[i];
        if (t) unregisteredTokens.push(t);
      } else {
        retryable = true;
      }
      errorSummary = code;
    });
    return {
      successCount: res.successCount,
      failureCount: res.failureCount,
      unregisteredTokens,
      acceptedTokens,
      retryable,
      errorSummary,
    };
  }

  async close(): Promise<void> {
    await deleteApp(this.app);
  }
}

/** The `android` block of an FCM message. Exported so the mapping is unit-tested without Firebase. */
export function fcmAndroidConfig(opts: PushSendOptions): {
  priority: 'high' | 'normal';
  ttl: number;
  collapseKey?: string;
} {
  const config: { priority: 'high' | 'normal'; ttl: number; collapseKey?: string } = {
    priority: opts.priority,
    ttl: opts.ttlMs ?? DEFAULT_PUSH_TTL_MS,
  };
  if (opts.collapseKey) config.collapseKey = opts.collapseKey;
  return config;
}
