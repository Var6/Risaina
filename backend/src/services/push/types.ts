export interface PushSendResult {
  successCount: number;
  failureCount: number;
  /** Tokens FCM reported as no longer valid; the caller removes them from the database. */
  unregisteredTokens: string[];
  /**
   * Tokens FCM accepted (in the order sent). Accepted only means FCM queued the message, not that
   * the phone received it; the app reports receipt itself (POST /devices/:deviceId/push-ack).
   */
  acceptedTokens: string[];
  /** True when at least one failure looks retryable (network, 5xx, quota). */
  retryable: boolean;
  /** Short, non-sensitive summary of the last failure (error code only, never token or payload). */
  errorSummary: string | null;
}

/** Data-only FCM payload mirroring a minimal event (see API.md). No intimate content. */
export interface PushData extends Record<string, string> {
  type: string;
  eventId: string;
  coupleId: string;
  seq: string;
}

/**
 * Android delivery priority. `high` wakes a dozing phone at once and is for pushes that end in a
 * visible notification; `normal` is delivered in the next maintenance window or when the screen
 * turns on, which is right for silent widget/home refreshes (see services/push/policy.ts).
 */
export type PushPriority = 'high' | 'normal';

export interface PushSendOptions {
  priority: PushPriority;
  /** Undelivered messages with the same key replace each other (FCM keeps only the newest). */
  collapseKey?: string;
  /** How long FCM keeps trying to deliver. Defaults to one hour. */
  ttlMs?: number;
}

export interface PushService {
  readonly enabled: boolean;
  readonly provider: 'fcm' | 'disabled' | 'fake';
  /** `opts` defaults to `{ priority: 'high' }` (the behaviour before per-type priorities). */
  send(tokens: string[], data: PushData, opts?: PushSendOptions): Promise<PushSendResult>;
}
