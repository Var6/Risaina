import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc, ListDoc, ListItem } from '../../db/types.js';
import { randomToken } from '../../lib/crypto.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { isDuplicateKey } from '../auth/service.js';
import { toListDto } from '../shared/dto.js';
import type { ListDto } from '../shared/schemas.js';
import { bodyToItems, itemsToBody } from './convert.js';
import { ListRepo } from './repo.js';
import {
  type ConvertListInput,
  type CreateListInput,
  MAX_BODY,
  MAX_ITEM_TEXT,
  MAX_LIST_ITEMS,
  type PatchListInput,
} from './schemas.js';

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

  /**
   * With a clientKey, a repeat (the phone retrying a create whose answer it never got) returns the
   * list the first request made, without a second list or event. The unique index settles a race
   * between two such requests.
   */
  async create(
    couple: CoupleDoc,
    userId: string,
    input: CreateListInput,
  ): Promise<{ list: ListDto; created: boolean }> {
    if (input.clientKey !== undefined) {
      const first = await this.repo.findByClientKey(couple._id, input.clientKey);
      if (first) return replay(first);
    }
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
      items: (input.items ?? []).map((i) => newItem(newItemId(), i.text, i.done ?? false, uid, now)),
      pinned: input.pinned ?? false,
      version: 1,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
      marker: input.marker ?? 'none',
      checkboxes: input.checkboxes ?? true,
      ...(input.clientKey !== undefined ? { clientKey: input.clientKey } : {}),
    };
    try {
      await this.repo.insert(doc);
    } catch (err) {
      if (isDuplicateKey(err) && input.clientKey !== undefined) {
        const first = await this.repo.findByClientKey(couple._id, input.clientKey);
        if (first) return replay(first);
      }
      throw err;
    }
    const dto = toListDto(doc);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'list.created',
      actorId: uid,
      payload: { list: dto },
    });
    return { list: dto, created: true };
  }

  async list(couple: CoupleDoc): Promise<{ items: ListDto[] }> {
    return { items: (await this.repo.list(couple._id)).map(toListDto) };
  }

  async get(couple: CoupleDoc, id: ObjectId): Promise<{ list: ListDto }> {
    const list = await this.repo.findInCouple(id, couple._id);
    if (!list) throw errors.notFound('List');
    return { list: toListDto(list) };
  }

  async update(
    couple: CoupleDoc,
    userId: string,
    id: ObjectId,
    patch: PatchListInput,
  ): Promise<{ list: ListDto }> {
    const uid = new ObjectId(userId);
    return this.applyAtVersion(couple, uid, id, patch.expectedVersion, (current, now) => {
      const set: Partial<ListDoc> = {};
      if (patch.title !== undefined) set.title = patch.title;
      if (patch.body !== undefined) set.body = patch.body;
      if (patch.pinned !== undefined) set.pinned = patch.pinned;
      if (patch.marker !== undefined) set.marker = patch.marker;
      if (patch.checkboxes !== undefined) set.checkboxes = patch.checkboxes;
      if (patch.items !== undefined) set.items = mergeItems(current.items, patch.items, uid, now);
      return set;
    });
  }

  /**
   * Turns a note into a checklist or back (PATCH cannot change `kind`). Nothing typed is lost: lines
   * that do not fit in 200 items stay in the body, and items whose lines would take the body over
   * 10,000 characters stay items. Converting to the kind the list already has only applies the
   * format fields. Same version rules and the same `list.updated` event as PATCH.
   */
  async convert(
    couple: CoupleDoc,
    userId: string,
    id: ObjectId,
    input: ConvertListInput,
  ): Promise<{ list: ListDto }> {
    const uid = new ObjectId(userId);
    return this.applyAtVersion(couple, uid, id, input.expectedVersion, (current, now) => {
      const set: Partial<ListDoc> = {};
      if (input.marker !== undefined) set.marker = input.marker;
      if (input.checkboxes !== undefined) set.checkboxes = input.checkboxes;
      if (input.to === current.kind) return set;
      set.kind = input.to;
      if (input.to === 'checklist') {
        // Items a note may already hold (an older app saved items onto it) come first.
        const room = Math.max(0, MAX_LIST_ITEMS - current.items.length);
        const { items, rest } = bodyToItems(current.body, room, MAX_ITEM_TEXT);
        set.items = [...current.items, ...items.map((i) => newItem(newItemId(), i.text, i.done, uid, now))];
        set.body = rest;
      } else {
        // Drawn as the checklist was shown, not as the format it is converted to.
        const format = { marker: current.marker ?? 'none', checkboxes: current.checkboxes ?? true };
        const { body, rest } = itemsToBody(current.items, format, current.body, MAX_BODY);
        set.body = body;
        set.items = rest;
      }
      return set;
    });
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

  /**
   * Compare-and-set on `version`, then `list.updated`. `build` turns the current list into the
   * fields to $set (only the fields it returns change, so fields an older app does not send, like
   * marker and checkboxes, are kept).
   */
  private async applyAtVersion(
    couple: CoupleDoc,
    uid: ObjectId,
    id: ObjectId,
    expectedVersion: number | undefined,
    build: (current: ListDoc, now: Date) => Partial<ListDoc>,
  ): Promise<{ list: ListDto }> {
    for (let attempt = 0; attempt < MAX_PATCH_ATTEMPTS; attempt++) {
      const current = await this.repo.findInCouple(id, couple._id);
      if (!current) throw errors.notFound('List');
      if (expectedVersion !== undefined && expectedVersion !== current.version) {
        throw versionConflict(current);
      }
      const now = this.ctx.now();
      const set: Partial<ListDoc> = { ...build(current, now), updatedAt: now, updatedBy: uid };

      const updated = await this.repo.patchAtVersion(id, couple._id, current.version, set);
      if (!updated) {
        // Changed or deleted between read and write. With expectedVersion the client asked for
        // strict optimistic concurrency, so report the conflict; otherwise retry on fresh state.
        if (expectedVersion !== undefined) {
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
}

function versionConflict(current: ListDoc) {
  return errors.versionConflict(`The list changed (now at version ${current.version})`, {
    list: toListDto(current),
  });
}

/** A repeated create: the first list, unless it has been deleted since (then it is gone: 404). */
function replay(first: ListDoc): { list: ListDto; created: boolean } {
  if (first.deletedAt) throw errors.notFound('List');
  return { list: toListDto(first), created: false };
}

function newItem(id: string, text: string, done: boolean, actor: ObjectId, now: Date): ListItem {
  return {
    id,
    text,
    done,
    doneBy: done ? actor : null,
    doneAt: done ? now : null,
    addedBy: actor,
    addedAt: now,
  };
}

/**
 * The client sends the whole items array. doneBy/doneAt are server-owned: kept for an item whose id
 * already existed with the same `done`, set to (actor, now) when it flips to done (or arrives done
 * as a new item), cleared when undone. Missing / unknown ids become new items, stamped with
 * addedBy/addedAt; an existing item keeps its own (null on items from before they existed).
 * An existing item also keeps any other field stored on it, so a field a newer app adds survives a
 * save from an older app that only knows id/text/done.
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
    if (!prev) return newItem(id, item.text, item.done, actor, now);
    const kept = {
      ...prev,
      id,
      text: item.text,
      addedBy: prev.addedBy ?? null,
      addedAt: prev.addedAt ?? null,
    };
    if (!item.done) return { ...kept, done: false, doneBy: null, doneAt: null };
    if (prev.done) return { ...kept, done: true, doneBy: prev.doneBy, doneAt: prev.doneAt };
    return { ...kept, done: true, doneBy: actor, doneAt: now };
  });
}
