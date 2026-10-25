import { z } from 'zod';
import { ChallengeKind, Game, GameKind, RpsMove } from '../shared/schemas.js';

const challengeText = z.string().trim().min(1).max(200).meta({ description: 'Trimmed, 1–200 chars' });

export const CreateGameBody = z.object({ kind: GameKind }).strict();
export const MoveBody = z.object({ move: RpsMove }).strict();
export const ChallengeBody = z
  .object({
    truth: challengeText.meta({ description: 'The truth question (trimmed, 1–200 chars)' }),
    dare: challengeText.meta({ description: 'The dare (trimmed, 1–200 chars)' }),
  })
  .strict();
export const ChooseBody = z
  .object({ kind: ChallengeKind.meta({ description: 'Blind pick: the loser has not seen either text' }) })
  .strict();
export const CompleteBody = z
  .object({
    answer: z.string().trim().max(500).optional().meta({
      description:
        'Trimmed, ≤ 500 chars. Required (non-empty) when the loser chose truth; optional for a dare ("" ⇒ null)',
    }),
  })
  .strict();
export const RejectBody = z
  .object({
    note: z
      .string()
      .trim()
      .max(200)
      .optional()
      .meta({ description: 'Optional note to the loser, trimmed, ≤ 200 chars ("" ⇒ null)' }),
  })
  .strict();

export const GameListQuery = z.object({
  limit: z.coerce.number().int().min(1).max(50).default(20),
});

export const GameResponse = z.object({ game: Game });
export const GameListResponse = z.object({ items: z.array(Game) });
export const SuggestionsResponse = z.object({
  truths: z.array(z.string()),
  dares: z.array(z.string()),
});
