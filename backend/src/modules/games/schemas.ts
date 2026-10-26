import { z } from 'zod';
import { TRIVIA_DIFFICULTIES } from '../../db/types.js';
import { ChallengeKind, Game, RpsMove } from '../shared/schemas.js';
import { DECK_IDS, TRIVIA_CATEGORY_IDS, TRIVIA_QUESTION_COUNT } from './catalog.js';

const challengeText = z.string().trim().min(1).max(200).meta({ description: 'Trimmed, 1–200 chars' });

/**
 * Only 'none' may be sent: the default (the blind truth or dare) is expressed by leaving `prize`
 * out. That keeps "Play again" bodies valid everywhere: an explicit default would be rejected by
 * an older server's strict `{ kind }` body.
 */
const NoPrize = z
  .literal('none')
  .meta({ description: "'none' = just for fun, the winner gets no truth or dare. Omit for the default." });

/**
 * Per-kind options; `{ kind: 'rps' }` from older apps stays valid. Each branch is strict, so an
 * option on the wrong kind (a deck on rps, a prize on This or That) is a 400.
 */
export const CreateGameBody = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('rps'), prize: NoPrize.optional() }).strict(),
  z.object({ kind: z.literal('tictactoe'), prize: NoPrize.optional() }).strict(),
  z
    .object({
      kind: z.literal('trivia'),
      prize: NoPrize.optional(),
      category: z
        .literal(TRIVIA_CATEGORY_IDS)
        .optional()
        .meta({ description: 'A curated Open Trivia DB category id; omit for a random curated one' }),
      difficulty: z.enum(TRIVIA_DIFFICULTIES).optional().meta({ description: 'Omit for any difficulty' }),
    })
    .strict(),
  z
    .object({
      kind: z.literal('this_or_that'),
      deck: z.enum(DECK_IDS).optional().meta({ description: "Prompt deck; omit for 'mix'" }),
    })
    .strict(),
]);
export type CreateGameInput = z.infer<typeof CreateGameBody>;

export const MoveBody = z.object({ move: RpsMove }).strict();

/**
 * One generic move endpoint. The service checks `kind` against the game first (400 "update
 * Risaina" on a mismatch), then the status, then the turn, then the move itself.
 */
export const PlayBody = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('rps'), move: RpsMove }).strict(),
  z
    .object({
      kind: z.literal('tictactoe'),
      cell: z.number().int().min(0).max(8).meta({ description: 'row * 3 + col' }),
    })
    .strict(),
  z
    .object({
      kind: z.literal('trivia'),
      item: z
        .number()
        .int()
        .min(0)
        .max(TRIVIA_QUESTION_COUNT - 1)
        .meta({
          description:
            'The question being answered; a retried answer gets 409 already_moved, never the next one',
        }),
      choice: z.number().int().min(0).max(3),
    })
    .strict(),
  z
    .object({
      kind: z.literal('this_or_that'),
      picks: z
        .array(z.number().int().min(0).max(1))
        .min(1)
        .max(10)
        .meta({ description: 'All picks at once (0 = first choice), one per prompt, in order' }),
    })
    .strict(),
]);
export type PlayInput = z.infer<typeof PlayBody>;
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
export const CatalogResponse = z
  .object({
    kinds: z.array(
      z.object({
        kind: z.string(),
        title: z.string(),
        emoji: z.string(),
        blurb: z.string(),
        competitive: z.boolean().meta({ description: 'Has a winner (and so the optional truth or dare)' }),
      }),
    ),
    trivia: z.object({
      questionCount: z.number().int(),
      difficulties: z.array(z.string()),
      categories: z.array(z.object({ id: z.number().int(), name: z.string() })),
      attribution: z.string(),
      attributionUrl: z.string(),
      licenseUrl: z.string().meta({ description: 'The CC BY-SA 4.0 licence; link it from the credit too' }),
    }),
    thisOrThat: z.object({
      promptCount: z.number().int(),
      decks: z.array(z.object({ id: z.string(), name: z.string(), emoji: z.string() })),
    }),
  })
  .meta({ id: 'GameCatalog' });

export const SuggestionsResponse = z.object({
  truths: z.array(z.string()),
  dares: z.array(z.string()),
});
