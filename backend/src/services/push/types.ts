export interface PushSendResult {
  successCount: number;
  failureCount: number;
  /** Tokens FCM reported as no longer valid; the caller removes them from the database. */
  unregisteredTokens: string[];
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

export interface PushService {
  readonly enabled: boolean;
  readonly provider: 'fcm' | 'disabled' | 'fake';
  send(tokens: string[], data: PushData): Promise<PushSendResult>;
}
