import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc, MilestoneDoc, MilestoneKind } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { toMilestoneDto } from '../shared/dto.js';
import type { MilestoneDto } from '../shared/schemas.js';
import { MilestoneRepo } from './repo.js';

export class MilestoneService {
  private readonly repo: MilestoneRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new MilestoneRepo(ctx.db);
  }

  async create(
    couple: CoupleDoc,
    userId: string,
    input: { title: string; date: string; kind: MilestoneKind },
  ): Promise<{ milestone: MilestoneDto }> {
    if ((await this.repo.count(couple._id)) >= MilestoneRepo.max) {
      throw errors.conflict(`At most ${MilestoneRepo.max} milestones per couple`);
    }
    const now = this.ctx.now();
    const uid = new ObjectId(userId);
    const doc: MilestoneDoc = {
      _id: newId(),
      coupleId: couple._id,
      createdBy: uid,
      title: input.title,
      date: input.date,
      kind: input.kind,
      visibility: 'shared',
      createdAt: now,
      updatedAt: now,
    };
    await this.repo.insert(doc);
    const dto = toMilestoneDto(doc);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'milestone.created',
      actorId: uid,
      payload: { milestone: dto },
    });
    return { milestone: dto };
  }

  async list(couple: CoupleDoc): Promise<{ items: MilestoneDto[] }> {
    return { items: (await this.repo.list(couple._id)).map(toMilestoneDto) };
  }

  async update(
    couple: CoupleDoc,
    userId: string,
    id: ObjectId,
    patch: { title?: string; date?: string; kind?: MilestoneKind },
  ): Promise<{ milestone: MilestoneDto }> {
    const existing = await this.repo.findInCouple(id, couple._id);
    if (!existing) throw errors.notFound('Milestone');
    const set: Partial<MilestoneDoc> = { updatedAt: this.ctx.now() };
    if (patch.title !== undefined) set.title = patch.title;
    if (patch.date !== undefined) set.date = patch.date;
    if (patch.kind !== undefined) set.kind = patch.kind;
    const updated = (await this.repo.patch(id, set)) ?? existing;
    const dto = toMilestoneDto(updated);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'milestone.updated',
      actorId: new ObjectId(userId),
      payload: { milestone: dto },
    });
    return { milestone: dto };
  }

  async delete(couple: CoupleDoc, userId: string, id: ObjectId): Promise<void> {
    const existing = await this.repo.findInCouple(id, couple._id);
    if (!existing) throw errors.notFound('Milestone');
    if (await this.repo.delete(id)) {
      await this.ctx.events.emit({
        coupleId: couple._id,
        type: 'milestone.deleted',
        actorId: new ObjectId(userId),
        payload: { milestoneId: id.toHexString() },
      });
    }
  }
}
