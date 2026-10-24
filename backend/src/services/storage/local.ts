import { createReadStream } from 'node:fs';
import { mkdir, open, readFile, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import type { Readable } from 'node:stream';
import { deriveSubkey, hmacHex, safeEqualHex } from '../../lib/crypto.js';
import {
  isSafeStorageKey,
  type ObjectHead,
  type PresignedDownload,
  type PresignedUpload,
  type StorageService,
} from './types.js';

export interface LocalStorageConfig {
  dir: string;
  publicBaseUrl: string;
  /** HMAC key material (derived from JWT_SECRET via HKDF; never the secret itself). */
  secret: string;
  routePrefix: string; // e.g. /api/v1/_local-storage
}

export interface LocalObjectMeta {
  contentType: string;
  sizeBytes: number;
  storedAt: string;
}

/**
 * DEVELOPMENT-ONLY storage driver. Files live under `dir`; upload/download URLs are HMAC-signed,
 * expiring URLs served by `registerLocalStorageRoutes` on this same server. It exists so the full
 * presign → PUT → complete → signed GET journey can run with no R2 credentials. `loadEnv` refuses
 * it when NODE_ENV=production.
 */
export class LocalStorageDriver implements StorageService {
  readonly driver = 'local' as const;
  readonly dir: string;
  private readonly hmacKey: Buffer;
  private readonly baseUrl: string;

  constructor(
    config: LocalStorageConfig,
    private readonly now: () => Date = () => new Date(),
  ) {
    this.dir = path.resolve(config.dir);
    this.hmacKey = deriveSubkey(config.secret, 'risaina-local-storage-v1');
    this.baseUrl = `${config.publicBaseUrl.replace(/\/+$/, '')}${config.routePrefix}/object`;
  }

  // --- signing -----------------------------------------------------------------------------

  sign(method: 'PUT' | 'GET', key: string, expiresAtMs: number, contentType: string): string {
    return hmacHex(this.hmacKey, `${method}\n${key}\n${expiresAtMs}\n${contentType}`);
  }

  verify(method: 'PUT' | 'GET', key: string, expiresAtMs: number, contentType: string, sig: string): boolean {
    if (!isSafeStorageKey(key)) return false;
    if (!Number.isFinite(expiresAtMs) || expiresAtMs <= this.now().getTime()) return false;
    return safeEqualHex(this.sign(method, key, expiresAtMs, contentType), sig);
  }

  async presignUpload(
    key: string,
    contentType: string,
    sizeBytes: number,
    ttlSeconds: number,
  ): Promise<PresignedUpload> {
    this.assertKey(key);
    const expiresAt = new Date(this.now().getTime() + ttlSeconds * 1000);
    const exp = expiresAt.getTime();
    const sig = this.sign('PUT', key, exp, contentType);
    const q = new URLSearchParams({ key, exp: String(exp), ct: contentType, sig });
    return {
      url: `${this.baseUrl}?${q.toString()}`,
      method: 'PUT',
      headers: { 'Content-Type': contentType, 'Content-Length': String(sizeBytes) },
      expiresAt,
    };
  }

  async presignDownload(key: string, ttlSeconds: number): Promise<PresignedDownload> {
    this.assertKey(key);
    const expiresAt = new Date(this.now().getTime() + ttlSeconds * 1000);
    const exp = expiresAt.getTime();
    const sig = this.sign('GET', key, exp, '');
    const q = new URLSearchParams({ key, exp: String(exp), sig });
    return { url: `${this.baseUrl}?${q.toString()}`, expiresAt };
  }

  // --- object operations ---------------------------------------------------------------------

  async putObject(key: string, body: Buffer, contentType: string): Promise<void> {
    this.assertKey(key);
    const file = this.filePath(key);
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, body);
    const meta: LocalObjectMeta = {
      contentType,
      sizeBytes: body.byteLength,
      storedAt: this.now().toISOString(),
    };
    await writeFile(this.metaPath(key), JSON.stringify(meta));
  }

  async openReadStream(key: string): Promise<{ stream: Readable; head: ObjectHead } | null> {
    const head = await this.head(key);
    if (!head) return null;
    return { stream: createReadStream(this.filePath(key)), head };
  }

  async head(key: string): Promise<ObjectHead | null> {
    if (!isSafeStorageKey(key)) return null;
    try {
      const s = await stat(this.filePath(key));
      let contentType: string | null = null;
      try {
        const meta = JSON.parse(await readFile(this.metaPath(key), 'utf8')) as Partial<LocalObjectMeta>;
        contentType = typeof meta.contentType === 'string' ? meta.contentType : null;
      } catch {
        contentType = null;
      }
      return { sizeBytes: s.size, contentType };
    } catch {
      return null;
    }
  }

  async readPrefix(key: string, bytes: number): Promise<Buffer | null> {
    if (!isSafeStorageKey(key)) return null;
    let fh: Awaited<ReturnType<typeof open>> | null = null;
    try {
      fh = await open(this.filePath(key), 'r');
      const buf = Buffer.alloc(bytes);
      const { bytesRead } = await fh.read(buf, 0, bytes, 0);
      return buf.subarray(0, bytesRead);
    } catch {
      return null;
    } finally {
      await fh?.close();
    }
  }

  async delete(key: string): Promise<void> {
    if (!isSafeStorageKey(key)) return;
    await rm(this.filePath(key), { force: true });
    await rm(this.metaPath(key), { force: true });
  }

  async healthCheck(): Promise<'ok' | 'error'> {
    try {
      await mkdir(this.dir, { recursive: true });
      return 'ok';
    } catch {
      return 'error';
    }
  }

  // --- paths -------------------------------------------------------------------------------

  private assertKey(key: string): void {
    if (!isSafeStorageKey(key)) throw new Error('Unsafe storage key');
  }

  private filePath(key: string): string {
    const resolved = path.resolve(this.dir, key);
    if (!resolved.startsWith(`${this.dir}${path.sep}`)) throw new Error('Path escapes storage dir');
    return resolved;
  }

  private metaPath(key: string): string {
    return `${this.filePath(key)}.meta.json`;
  }
}
