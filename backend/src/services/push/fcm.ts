import { type App, cert, deleteApp, initializeApp } from 'firebase-admin/app';
import { getMessaging, type Messaging } from 'firebase-admin/messaging';
import type { FirebaseServiceAccount } from '../../config/env.js';
import type { PushData, PushSendResult, PushService } from './types.js';

const UNREGISTERED_CODES = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
  'messaging/invalid-argument',
]);

/**
 * FCM via firebase-admin. Sends data-only, high-priority messages. Never logs tokens or payloads.
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

  async send(tokens: string[], data: PushData): Promise<PushSendResult> {
    if (tokens.length === 0) {
      return {
        successCount: 0,
        failureCount: 0,
        unregisteredTokens: [],
        retryable: false,
        errorSummary: null,
      };
    }
    const res = await this.messaging.sendEachForMulticast({
      tokens,
      data,
      android: { priority: 'high', ttl: 60 * 60 * 1000 },
    });
    const unregisteredTokens: string[] = [];
    let retryable = false;
    let errorSummary: string | null = null;
    res.responses.forEach((r, i) => {
      if (r.success) return;
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
      retryable,
      errorSummary,
    };
  }

  async close(): Promise<void> {
    await deleteApp(this.app);
  }
}
