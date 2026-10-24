export interface PresignedUpload {
  url: string;
  method: 'PUT';
  headers: Record<string, string>;
  expiresAt: Date;
}

export interface PresignedDownload {
  url: string;
  expiresAt: Date;
}

export interface ObjectHead {
  sizeBytes: number;
  contentType: string | null;
}

export type StorageDriverName = 'local' | 'r2';

/**
 * Object storage abstraction. Keys are always generated server-side
 * (`couples/{coupleId}/{category}/{mediaId}.{ext}`) and never accepted from clients.
 */
export interface StorageService {
  readonly driver: StorageDriverName;
  presignUpload(
    key: string,
    contentType: string,
    sizeBytes: number,
    ttlSeconds: number,
  ): Promise<PresignedUpload>;
  presignDownload(key: string, ttlSeconds: number): Promise<PresignedDownload>;
  /** `null` when the object does not exist. */
  head(key: string): Promise<ObjectHead | null>;
  /** First `bytes` bytes of the object (for magic-number sniffing); `null` when missing. */
  readPrefix(key: string, bytes: number): Promise<Buffer | null>;
  /** Idempotent: deleting a missing object is not an error. */
  delete(key: string): Promise<void>;
  healthCheck(): Promise<'ok' | 'error'>;
}

export const STORAGE_KEY_REGEX =
  /^couples\/[a-f0-9]{24}\/(photo|voice|avatar|wallpaper)\/[a-f0-9]{24}\.[a-z0-9]{1,8}$/;

export function isSafeStorageKey(key: string): boolean {
  return STORAGE_KEY_REGEX.test(key) && !key.includes('..');
}
