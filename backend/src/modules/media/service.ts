import { fileTypeFromBuffer } from 'file-type';
import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc, MediaCategory, MediaDoc } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { toMediaDto } from '../shared/dto.js';
import type { MediaDto } from '../shared/schemas.js';
import { MediaRepo } from './repo.js';

export interface CreateUploadInput {
  category: MediaCategory;
  mimeType: string;
  sizeBytes: number;
  durationMs?: number;
  width?: number;
  height?: number;
}

const IMAGE_MIMES = ['image/jpeg', 'image/png', 'image/webp', 'image/heic'] as const;
const AUDIO_MIMES = ['audio/mp4', 'audio/aac', 'audio/ogg'] as const;

const ALLOWED_MIMES: Record<MediaCategory, readonly string[]> = {
  photo: IMAGE_MIMES,
  avatar: IMAGE_MIMES,
  wallpaper: IMAGE_MIMES,
  voice: AUDIO_MIMES,
};

const EXT_FOR_MIME: Record<string, string> = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
  'image/heic': 'heic',
  'audio/mp4': 'm4a',
  'audio/aac': 'aac',
  'audio/ogg': 'ogg',
};

/** Declared MIME → set of MIME types `file-type` may report for a genuine file of that kind. */
const SNIFF_COMPATIBLE: Record<string, readonly string[]> = {
  'image/jpeg': ['image/jpeg'],
  'image/png': ['image/png'],
  'image/webp': ['image/webp'],
  'image/heic': ['image/heic', 'image/heif', 'image/heic-sequence', 'image/heif-sequence'],
  'audio/mp4': ['audio/mp4', 'audio/x-m4a', 'video/mp4', 'audio/aac'],
  'audio/aac': ['audio/aac'],
  'audio/ogg': ['audio/ogg', 'audio/opus', 'audio/vorbis'],
};

const SNIFF_BYTES = 4100;

export class MediaService {
  private readonly repo: MediaRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new MediaRepo(ctx.db);
  }

  maxBytesFor(category: MediaCategory): number {
    const m = this.ctx.env.media;
    switch (category) {
      case 'photo':
        return m.maxPhotoBytes;
      case 'voice':
        return m.maxVoiceBytes;
      case 'avatar':
        return m.maxAvatarBytes;
      case 'wallpaper':
        return m.maxWallpaperBytes;
    }
  }

  async createUpload(couple: CoupleDoc, ownerId: string, input: CreateUploadInput) {
    const allowed = ALLOWED_MIMES[input.category];
    if (!allowed.includes(input.mimeType)) {
      throw errors.unsupportedMedia(
        `${input.mimeType} is not allowed for ${input.category} (allowed: ${allowed.join(', ')})`,
      );
    }
    const max = this.maxBytesFor(input.category);
    if (input.sizeBytes > max)
      throw errors.payloadTooLarge(`${input.category} uploads are limited to ${max} bytes`);
    if (input.category === 'voice') {
      if (input.durationMs === undefined) throw errors.validation('durationMs is required for voice uploads');
      if (input.durationMs > this.ctx.env.media.maxVoiceDurationMs) {
        throw errors.payloadTooLarge(
          `voice notes are limited to ${this.ctx.env.media.maxVoiceDurationMs} ms`,
        );
      }
    }

    const now = this.ctx.now();
    const id = newId();
    const ext = EXT_FOR_MIME[input.mimeType] ?? 'bin';
    const doc: MediaDoc = {
      _id: id,
      coupleId: couple._id,
      ownerId: new ObjectId(ownerId),
      category: input.category,
      mimeType: input.mimeType,
      sizeBytes: input.sizeBytes,
      durationMs: input.durationMs ?? null,
      width: input.width ?? null,
      height: input.height ?? null,
      status: 'pending',
      storageKey: `couples/${couple._id.toHexString()}/${input.category}/${id.toHexString()}.${ext}`,
      createdAt: now,
      readyAt: null,
      deletedAt: null,
      objectDeleted: false,
    };
    await this.repo.insert(doc);
    const presigned = await this.ctx.storage.presignUpload(
      doc.storageKey,
      doc.mimeType,
      doc.sizeBytes,
      this.ctx.env.media.uploadUrlTtlSeconds,
    );
    return {
      media: toMediaDto(doc),
      upload: {
        url: presigned.url,
        method: presigned.method,
        headers: presigned.headers,
        expiresAt: presigned.expiresAt.toISOString(),
      },
    };
  }

  private async loadInCouple(couple: CoupleDoc, mediaId: ObjectId): Promise<MediaDoc> {
    const media = await this.repo.findById(mediaId);
    if (!media) throw errors.notFound('Media');
    if (!media.coupleId.equals(couple._id)) throw errors.forbidden('Media belongs to another couple');
    return media;
  }

  /** HEAD + first-bytes magic sniff, then `ready` + `media.ready` event. */
  async complete(couple: CoupleDoc, userId: string, mediaId: ObjectId): Promise<{ media: MediaDto }> {
    const media = await this.loadInCouple(couple, mediaId);
    const uid = new ObjectId(userId);
    if (!media.ownerId.equals(uid)) throw errors.forbidden('Only the uploader can complete an upload');
    if (media.status === 'ready') return { media: toMediaDto(media) };
    if (media.status === 'deleted') throw errors.notFound('Media');

    const head = await this.ctx.storage.head(media.storageKey);
    if (!head) throw errors.mediaNotReady('Object has not been uploaded yet');
    const max = this.maxBytesFor(media.category);
    if (head.sizeBytes > max) {
      await this.discard(media);
      throw errors.payloadTooLarge(`Uploaded object exceeds ${max} bytes`);
    }
    if (head.sizeBytes !== media.sizeBytes) {
      await this.discard(media);
      throw errors.validation('Uploaded object size does not match the declared sizeBytes', {
        declared: media.sizeBytes,
        actual: head.sizeBytes,
      });
    }
    if (head.contentType && head.contentType.split(';')[0]?.trim() !== media.mimeType) {
      await this.discard(media);
      throw errors.unsupportedMedia('Uploaded Content-Type does not match the declared mimeType');
    }

    const prefix = await this.ctx.storage.readPrefix(media.storageKey, SNIFF_BYTES);
    const sniffed = prefix ? await fileTypeFromBuffer(prefix) : undefined;
    const compatible = SNIFF_COMPATIBLE[media.mimeType] ?? [];
    if (!sniffed || !compatible.includes(sniffed.mime)) {
      await this.discard(media);
      throw errors.unsupportedMedia(
        sniffed
          ? `Object content (${sniffed.mime}) does not match ${media.mimeType}`
          : 'Object content is not a recognised media file',
      );
    }

    const ready = (await this.repo.markReady(media._id, head.sizeBytes, this.ctx.now())) ?? media;
    const dto = toMediaDto(ready);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'media.ready',
      actorId: uid,
      payload: { media: dto },
    });
    return { media: dto };
  }

  async downloadUrl(couple: CoupleDoc, mediaId: ObjectId): Promise<{ url: string; expiresAt: string }> {
    const media = await this.loadInCouple(couple, mediaId);
    // Nobody re-fetches a view-once photo: the sender has the local file, the recipient uses
    // POST /messages/:id/open exactly once.
    if (media.viewOnce) throw errors.viewOnce();
    if (media.status !== 'ready') throw errors.mediaNotReady();
    const signed = await this.ctx.storage.presignDownload(
      media.storageKey,
      this.ctx.env.media.downloadUrlTtlSeconds,
    );
    return { url: signed.url, expiresAt: signed.expiresAt.toISOString() };
  }

  async delete(couple: CoupleDoc, userId: string, mediaId: ObjectId): Promise<void> {
    const media = await this.loadInCouple(couple, mediaId);
    if (!media.ownerId.equals(new ObjectId(userId)))
      throw errors.forbidden('Only the owner can delete media');
    if (media.status === 'deleted') return;
    await this.discard(media);
  }

  /** Mark deleted and remove the storage object (deferred to the cleanup worker on failure). */
  private async discard(media: MediaDoc): Promise<void> {
    const now = this.ctx.now();
    let objectDeleted = false;
    try {
      await this.ctx.storage.delete(media.storageKey);
      objectDeleted = true;
    } catch (err) {
      this.ctx.logger.warn(
        { mediaId: media._id.toHexString(), err: (err as Error).message },
        'storage delete failed; deferred to cleanup',
      );
    }
    await this.repo.markDeleted(media._id, now, objectDeleted);
    if (objectDeleted) await this.repo.setObjectDeleted(media._id);
  }
}
