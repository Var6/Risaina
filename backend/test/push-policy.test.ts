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
