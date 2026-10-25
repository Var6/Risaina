import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc, ListDoc, ListItem } from '../../db/types.js';
import { randomToken } from '../../lib/crypto.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { toListDto } from '../shared/dto.js';
import type { ListDto } from '../shared/schemas.js';
import { ListRepo } from './repo.js';
import type { CreateListInput, PatchListInput } from './schemas.js';

/** 12-char URL-safe random id (9 random bytes, base64url). */
function newItemId(): string {
  return randomToken(9);
}

/** Without expectedVersion, a PATCH that loses a compare-and-set race is re-applied on fresh state. */
const MAX_PATCH_ATTEMPTS = 5;

export class ListService {
  private readonly repo: ListRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new ListRepo(ctx.db);
  }

  async create(couple: CoupleDoc, userId: string, input: CreateListInput): Promise<{ list: ListDto }> {
    if ((await this.repo.count(couple._id)) >= ListRepo.max) {
      throw errors.conflict(`At most ${ListRepo.max} lists per couple`);
    }
    const now = this.ctx.now();
    const uid = new ObjectId(userId);
    const doc: ListDoc = {
      _id: newId(),
      coupleId: couple._id,
      createdBy: uid,
      updatedBy: uid,
      title: input.title,
      kind: input.kind,
      body: input.body ?? '',
      items: (input.items ?? []).map((i) => {
        const done = i.done ?? false;
        return { id: newItemId(), text: i.text, done, doneBy: done ? uid : null, doneAt: done ? now : null };
      }),
      pinned: input.pinned ?? false,
      version: 1,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
    };
    await this.repo.insert(doc);
    const dto = toListDto(doc);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'list.created',
      actorId: uid,
      payload: { list: dto },
    });
    return { list: dto };
  }

  async list(couple: CoupleDoc): Promise<{ items: ListDto[] }> {
    return { items: (await this.repo.list(couple._id)).map(toListDto) };
  }

  async update(
    couple: CoupleDoc,
    userId: string,
    id: ObjectId,
    patch: PatchListInput,
  ): Promise<{ list: ListDto }> {
    const uid = new ObjectId(userId);
    for (let attempt = 0; attempt < MAX_PATCH_ATTEMPTS; attempt++) {
      const current = await this.repo.findInCouple(id, couple._id);
      if (!current) throw errors.notFound('List');
      if (patch.expectedVersion !== undefined && patch.expectedVersion !== current.version) {
        throw versionConflict(current);
      }
      const now = this.ctx.now();
      const set: Partial<ListDoc> = { updatedAt: now, updatedBy: uid };
      if (patch.title !== undefined) set.title = patch.title;
      if (patch.body !== undefined) set.body = patch.body;
      if (patch.pinned !== undefined) set.pinned = patch.pinned;
      if (patch.items !== undefined) set.items = mergeItems(current.items, patch.items, uid, now);

      const updated = await this.repo.patchAtVersion(id, couple._id, current.version, set);
      if (!updated) {
        // Changed or deleted between read and write. With expectedVersion the client asked for
        // strict optimistic concurrency, so report the conflict; otherwise retry on fresh state.
        if (patch.expectedVersion !== undefined) {
          const fresh = await this.repo.findInCouple(id, couple._id);
          if (!fresh) throw errors.notFound('List');
          throw versionConflict(fresh);
        }
        continue;
      }
      const dto = toListDto(updated);
      await this.ctx.events.emit({
        coupleId: couple._id,
        type: 'list.updated',
        actorId: uid,
        payload: { list: dto },
      });
      return { list: dto };
    }
    throw errors.conflict('The list is being edited concurrently; try again');
  }

  async delete(couple: CoupleDoc, userId: string, id: ObjectId): Promise<void> {
    const uid = new ObjectId(userId);
    if (!(await this.repo.softDelete(id, couple._id, uid, this.ctx.now()))) {
      throw errors.notFound('List');
    }
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'list.deleted',
      actorId: uid,
      payload: { listId: id.toHexString() },
    });
  }
}

function versionConflict(current: ListDoc) {
  return errors.versionConflict(`The list changed (now at version ${current.version})`, {
    list: toListDto(current),
  });
}

/**
 * The client sends the whole items array. doneBy/doneAt are server-owned: kept for an item whose id
 * already existed with the same `done`, set to (actor, now) when it flips to done (or arrives done
 * as a new item), cleared when undone. Missing / unknown ids become new items.
 */
function mergeItems(
  previous: ListItem[],
  incoming: { id?: string | undefined; text: string; done: boolean }[],
  actor: ObjectId,
  now: Date,
): ListItem[] {
  const byId = new Map(previous.map((i) => [i.id, i]));
  const taken = new Set(incoming.flatMap((i) => (i.id === undefined ? [] : [i.id])));
  return incoming.map((item) => {
    let id = item.id;
    if (id === undefined) {
      do id = newItemId();
      while (taken.has(id));
      taken.add(id);
    }
    const prev = item.id === undefined ? undefined : byId.get(item.id);
    if (!item.done) return { id, text: item.text, done: false, doneBy: null, doneAt: null };
    if (prev?.done) return { id, text: item.text, done: true, doneBy: prev.doneBy, doneAt: prev.doneAt };
    return { id, text: item.text, done: true, doneBy: actor, doneAt: now };
  });
}
