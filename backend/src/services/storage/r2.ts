import {
  DeleteObjectCommand,
  GetObjectCommand,
  HeadBucketCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import type { ObjectHead, PresignedDownload, PresignedUpload, StorageService } from './types.js';

export interface R2Config {
  accountId: string;
  accessKeyId: string;
  secretAccessKey: string;
  bucket: string;
  endpoint: string;
}

/**
 * Cloudflare R2 via the S3 API (AWS SDK v3). Private bucket; clients only ever receive
 * short-lived presigned URLs. NOTE: this driver has not been exercised against a live bucket in
 * this repository (no credentials available); the request shapes follow the R2 S3-compat docs.
 */
export class R2StorageDriver implements StorageService {
  readonly driver = 'r2' as const;
  private readonly client: S3Client;
  private readonly bucket: string;

  constructor(
    config: R2Config,
    private readonly now: () => Date = () => new Date(),
  ) {
    this.bucket = config.bucket;
    this.client = new S3Client({
      region: 'auto',
      endpoint: config.endpoint,
      forcePathStyle: true,
      credentials: { accessKeyId: config.accessKeyId, secretAccessKey: config.secretAccessKey },
    });
  }

  async presignUpload(
    key: string,
    contentType: string,
    sizeBytes: number,
    ttlSeconds: number,
  ): Promise<PresignedUpload> {
    const cmd = new PutObjectCommand({
      Bucket: this.bucket,
      Key: key,
      ContentType: contentType,
      ContentLength: sizeBytes,
    });
    const url = await getSignedUrl(this.client, cmd, { expiresIn: ttlSeconds });
    return {
      url,
      method: 'PUT',
      headers: { 'Content-Type': contentType, 'Content-Length': String(sizeBytes) },
      expiresAt: new Date(this.now().getTime() + ttlSeconds * 1000),
    };
  }

  async presignDownload(key: string, ttlSeconds: number): Promise<PresignedDownload> {
    const cmd = new GetObjectCommand({ Bucket: this.bucket, Key: key });
    const url = await getSignedUrl(this.client, cmd, { expiresIn: ttlSeconds });
    return { url, expiresAt: new Date(this.now().getTime() + ttlSeconds * 1000) };
  }

  async head(key: string): Promise<ObjectHead | null> {
    try {
      const res = await this.client.send(new HeadObjectCommand({ Bucket: this.bucket, Key: key }));
      return { sizeBytes: res.ContentLength ?? 0, contentType: res.ContentType ?? null };
    } catch (err) {
      if (isNotFound(err)) return null;
      throw err;
    }
  }

  async readPrefix(key: string, bytes: number): Promise<Buffer | null> {
    try {
      const res = await this.client.send(
        new GetObjectCommand({ Bucket: this.bucket, Key: key, Range: `bytes=0-${Math.max(0, bytes - 1)}` }),
      );
      if (!res.Body) return null;
      const arr = await res.Body.transformToByteArray();
      return Buffer.from(arr);
    } catch (err) {
      if (isNotFound(err)) return null;
      throw err;
    }
  }

  async delete(key: string): Promise<void> {
    try {
      await this.client.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: key }));
    } catch (err) {
      if (isNotFound(err)) return;
      throw err;
    }
  }

  async healthCheck(): Promise<'ok' | 'error'> {
    try {
      await this.client.send(new HeadBucketCommand({ Bucket: this.bucket }));
      return 'ok';
    } catch {
      return 'error';
    }
  }
}

function isNotFound(err: unknown): boolean {
  if (typeof err !== 'object' || err === null) return false;
  const e = err as { name?: string; $metadata?: { httpStatusCode?: number } };
  return e.name === 'NotFound' || e.name === 'NoSuchKey' || e.$metadata?.httpStatusCode === 404;
}
