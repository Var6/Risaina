import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { EVENT_TYPES } from '../src/db/types.js';
import { fcmAndroidConfig } from '../src/services/push/fcm.js';
import {
  GAME_NOTIFYING_ACTIONS,
  GAME_UNPUSHED_ACTIONS,
  NORMAL_PUSH_TTL_MS,
  type PushPolicyPriority,
  pushPolicyFor,
  quietPolicy,
  SYNC_COLLAPSE_KEY,
} from '../src/services/push/policy.js';

/**
 * Every event type the server emits today, plus types other modules add later (handled by string),
 * with the priority the recipient's phone should get. High = a visible notification on that phone.
 */
const TABLE: [
  type: string,
  payload: Record<string, unknown>,
  priority: PushPolicyPriority,
  bypass: boolean,
][] = [
  ['ping.created', {}, 'high', false],
  ['ping.delivered', {}, 'normal', false],
  ['ping.seen', {}, 'normal', false],
  ['message.created', {}, 'high', false],
  ['message.read', {}, 'normal', false],
  ['message.deleted', {}, 'normal', false],
  ['message.opened', {}, 'normal', false],
  ['media.ready', {}, 'normal', false],
  ['memory.created', {}, 'normal', false],
  ['memory.updated', {}, 'normal', false],
  ['memory.deleted', {}, 'normal', false],
  ['milestone.created', {}, 'normal', false],
  ['milestone.updated', {}, 'normal', false],
  ['milestone.deleted', {}, 'normal', false],
  ['couple.updated', {}, 'normal', false],
  ['couple.unlinked', {}, 'high', false],
  ['partner.updated', {}, 'normal', false],
  ['list.created', {}, 'normal', false],
  ['list.updated', {}, 'normal', false],
  ['list.deleted', {}, 'normal', false],
  ['widget.updated', {}, 'high', false],
  ['sos.raised', {}, 'high', true],
  ['sos.acknowledged', {}, 'normal', false],
  // Added by other modules; strings so this file does not depend on them.
  ['sos.resolved', {}, 'high', true],
  ['sos.delivered', {}, 'normal', false],
  ['location.started', {}, 'high', false],
  ['location.stopped', {}, 'normal', false],
  ['something.new', {}, 'normal', false],
  // Games: by action.
  ['game.updated', { action: 'created' }, 'high', false],
  ['game.updated', { action: 'moved' }, 'high', false],
  ['game.updated', { action: 'revealed' }, 'high', false],
  ['game.updated', { action: 'draw' }, 'high', false],
  ['game.updated', { action: 'rematch' }, 'high', false],
  ['game.updated', { action: 'challenged' }, 'high', false],
  ['game.updated', { action: 'chosen' }, 'high', false],
  ['game.updated', { action: 'done' }, 'high', false],
  ['game.updated', { action: 'confirmed' }, 'high', false],
  ['game.updated', { action: 'rejected' }, 'high', false],
  ['game.updated', { action: 'finished' }, 'high', false],
  ['game.updated', { action: 'matched' }, 'high', false],
  ['game.updated', { action: 'answered' }, 'none', false],
  ['game.updated', { action: 'cancelled' }, 'none', false],
  ['game.updated', { action: 'ended' }, 'none', false],
  ['game.updated', { action: 'some_future_action' }, 'normal', false],
  ['game.updated', {}, 'normal', false],
  ['game.updated', { action: 42 }, 'normal', false],
];

describe('pushPolicyFor', () => {
  it.each(TABLE)('%s %j → %s (bypass %s)', (type, payload, priority, bypass) => {
    const p = pushPolicyFor(type, payload);
    expect(p.priority).toBe(priority);
    expect(p.bypassPrefs).toBe(bypass);
    if (priority === 'normal') {
      // Silent pushes collapse into one "sync now" and may wait a day for a dozing phone.
      expect(p.collapseKey).toBe(SYNC_COLLAPSE_KEY);
      expect(p.ttlMs).toBe(NORMAL_PUSH_TTL_MS);
    } else {
      // A notifying push must never be replaced by a later silent one.
      expect(p.collapseKey).toBeUndefined();
    }
  });

  /**
   * Every high type must end in a visible notification on the recipient's phone, or FCM learns to
   * deprioritise it. Mirrors the app: EventReducer.kt maps these to Incoming items that
   * RisainaNotifier.kt posts (game.updated: only the notifying actions; widget.updated: the quiet
   * "updated your widget"; sos.*: SosAlert; location.started: the location stream's notification).
   */
  const CLIENT_NOTIFIES: ReadonlySet<string> = new Set([
    'ping.created',
    'message.created',
    'couple.unlinked',
    'widget.updated',
    'game.updated',
    'sos.raised',
    'sos.resolved',
    'location.started',
  ]);

  it('only pushes high what the app turns into a visible notification', () => {
    for (const [type, payload, priority] of TABLE) {
      if (priority === 'high')
        expect(CLIENT_NOTIFIES.has(type), `${type} ${JSON.stringify(payload)}`).toBe(true);
    }
  });

  /**
   * game.updated is checked per action, not as a whole type: a high game push whose action the app
   * has no text for ends in no notification (RisainaNotifier returns early), which is exactly what
   * makes FCM deprioritise the phone. The app's list is read from RisainaNotifier.kt itself, so the
   * two cannot drift apart.
   */
  const notifierKt = fileURLToPath(
    new URL(
      '../../android/app/src/main/java/com/risaina/app/core/notifications/RisainaNotifier.kt',
      import.meta.url,
    ),
  );
  const gamesService = fileURLToPath(new URL('../src/modules/games/service.ts', import.meta.url));

  /** Actions the client's `is Incoming.Game ->` branch has a notification text for. */
  function clientGameActions(source: string): Set<string> {
    const start = source.indexOf('is Incoming.Game ->');
    const end = source.indexOf('?: return', start);
    const block = start >= 0 && end > start ? source.slice(start, end) : '';
    return new Set([...block.matchAll(/"([a-z_]+)"(?=\s*(?:,|->))/g)].map((m) => m[1]!));
  }

  /** Actions this server emits today (the GameAction union in the games module). */
  function emittedGameActions(source: string): Set<string> {
    const union = /export type GameAction =([^;]+);/.exec(source)?.[1] ?? '';
    return new Set([...union.matchAll(/'([a-z_]+)'/g)].map((m) => m[1]!));
  }

  /**
   * High in the policy (main-session decision) but not emitted yet: the games stream adds these
   * actions together with their notification texts in RisainaNotifier.kt. The test below fails as
   * soon as the server emits one without a client text.
   */
  const AWAITING_GAMES_STREAM: ReadonlySet<string> = new Set(['finished', 'matched']);

  it.skipIf(!existsSync(notifierKt))(
    'pushes a game action high only if the app posts a notification for it',
    () => {
      const client = clientGameActions(readFileSync(notifierKt, 'utf8'));
      const emitted = emittedGameActions(readFileSync(gamesService, 'utf8'));
      // The parsers still understand both files (a refactor must update this test, not pass it).
      expect(client.has('moved')).toBe(true);
      expect(emitted.has('moved')).toBe(true);
      for (const action of GAME_NOTIFYING_ACTIONS) {
        if (client.has(action)) continue;
        expect(AWAITING_GAMES_STREAM.has(action), `game.updated ${action}: no client text`).toBe(true);
        expect(emitted.has(action), `game.updated ${action} is emitted but the app posts nothing`).toBe(
          false,
        );
      }
      // And nothing the app announces is silently left out of the high list.
      for (const action of client) expect(GAME_NOTIFYING_ACTIONS.has(action), action).toBe(true);
    },
  );

  it('covers every event type the server can emit', () => {
    const covered = new Set(TABLE.map(([type]) => type));
    for (const type of EVENT_TYPES) expect(covered.has(type), type).toBe(true);
  });

  it('lists each game action in exactly one group', () => {
    for (const a of GAME_NOTIFYING_ACTIONS) expect(GAME_UNPUSHED_ACTIONS.has(a)).toBe(false);
  });

  it('tolerates a missing payload', () => {
    expect(pushPolicyFor('game.updated', null).priority).toBe('normal');
    expect(pushPolicyFor('ping.created').priority).toBe('high');
  });

  it('returns a fresh object each time (callers may not mutate shared state)', () => {
    const a = pushPolicyFor('ping.created', {});
    a.priority = 'none';
    expect(pushPolicyFor('ping.created', {}).priority).toBe('high');
  });
});

describe('quietPolicy (notifications turned off)', () => {
  it('turns a notifying push into a silent normal one instead of skipping it', () => {
    expect(quietPolicy(pushPolicyFor('ping.created', {}))).toEqual({
      priority: 'normal',
      bypassPrefs: false,
      collapseKey: SYNC_COLLAPSE_KEY,
      ttlMs: NORMAL_PUSH_TTL_MS,
    });
    expect(quietPolicy(pushPolicyFor('game.updated', { action: 'moved' })).priority).toBe('normal');
  });

  it('keeps SOS high', () => {
    expect(quietPolicy(pushPolicyFor('sos.raised', {})).priority).toBe('high');
    expect(quietPolicy(pushPolicyFor('sos.resolved', {})).priority).toBe('high');
  });

  it('leaves normal and none unchanged', () => {
    expect(quietPolicy(pushPolicyFor('partner.updated', {})).priority).toBe('normal');
    expect(quietPolicy(pushPolicyFor('game.updated', { action: 'cancelled' })).priority).toBe('none');
  });
});

describe('fcmAndroidConfig', () => {
  it('maps priority, ttl (default one hour) and the optional collapse key', () => {
    expect(fcmAndroidConfig({ priority: 'high' })).toEqual({ priority: 'high', ttl: 3_600_000 });
    expect(fcmAndroidConfig({ priority: 'normal', collapseKey: 'sync', ttlMs: NORMAL_PUSH_TTL_MS })).toEqual({
      priority: 'normal',
      ttl: NORMAL_PUSH_TTL_MS,
      collapseKey: 'sync',
    });
  });
});
