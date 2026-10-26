/**
 * Which events are pushed to the partner's phone, and how urgently.
 *
 * Why this exists: FCM lowers an app's high-priority pushes to normal (for that phone, judged over
 * about seven days) when they rarely end in a visible notification. Every event used to go out as
 * high priority, although most (receipts, list edits, widget changes) show nothing, so a phone
 * could end up getting even pings and messages late. Now only events the recipient SEES as a
 * notification are high; silent ones are normal (they still wake the app to refresh widgets, at
 * the next Doze maintenance window or screen-on); a few are not pushed at all.
 *
 * Event types are handled as plain strings, not the EventType union, so types added by other
 * modules (and any type this table does not know yet) still get a sensible default: normal.
 */

export type PushPolicyPriority = 'high' | 'normal' | 'none';

export interface PushPolicy {
  /** `none` = record the event as skipped (`not_pushed`); the socket and the next sync deliver it. */
  priority: PushPolicyPriority;
  /** Pushed at full priority even when the recipient turned notifications off or is in quiet hours. */
  bypassPrefs: boolean;
  /** FCM keeps only the newest undelivered message per key. Set for silent "please sync" pushes. */
  collapseKey?: string;
  ttlMs?: number;
}

/**
 * One key for every silent push: each one only means "sync now", and a sync fetches everything, so
 * a phone that was offline needs just the newest. (FCM keys are per app per phone, and one phone
 * belongs to one couple, so the key needs no couple id.)
 */
export const SYNC_COLLAPSE_KEY = 'sync';
/** Silent pushes may wait for the phone longer than notifying ones: the widget refresh still helps. */
export const NORMAL_PUSH_TTL_MS = 24 * 60 * 60 * 1000;

/**
 * Always end in a visible notification on the recipient's phone. widget.updated ("Their widget":
 * mood, note or photo) is the main widget content, so it is high too, and the app posts a quiet
 * "<partner> updated your widget" notification while the widget redraws (main-session decision).
 */
const HIGH_TYPES: ReadonlySet<string> = new Set([
  'ping.created',
  'message.created',
  'couple.unlinked',
  'location.started',
  'widget.updated',
]);

/** Safety alerts: high priority, through notifications-off and quiet hours. */
const BYPASS_TYPES: ReadonlySet<string> = new Set(['sos.raised', 'sos.resolved']);

/** game.updated actions that notify the other player (their turn, a result, a challenge, ...). */
export const GAME_NOTIFYING_ACTIONS: ReadonlySet<string> = new Set([
  'created',
  'moved',
  'revealed',
  'draw',
  'rematch',
  'challenged',
  'chosen',
  'done',
  'confirmed',
  'rejected',
  'finished',
  'matched',
]);

/**
 * game.updated actions that are stored but never pushed: a partner's single trivia answer (up to 5
 * per player per duel) changes only a progress count, and the card catches up when the app opens.
 * 'cancelled' and 'ended' are NOT here: they go out as a silent normal push so the other phone
 * clears a now-stale "Your turn" notification and the card without waiting to be opened.
 */
export const GAME_UNPUSHED_ACTIONS: ReadonlySet<string> = new Set(['answered']);

const HIGH: PushPolicy = { priority: 'high', bypassPrefs: false };
const BYPASS: PushPolicy = { priority: 'high', bypassPrefs: true };
const NORMAL: PushPolicy = {
  priority: 'normal',
  bypassPrefs: false,
  collapseKey: SYNC_COLLAPSE_KEY,
  ttlMs: NORMAL_PUSH_TTL_MS,
};
const NONE: PushPolicy = { priority: 'none', bypassPrefs: false };

/** The push policy for an event, before the recipient's preferences are applied. */
export function pushPolicyFor(type: string, payload?: Record<string, unknown> | null): PushPolicy {
  if (BYPASS_TYPES.has(type)) return { ...BYPASS };
  if (HIGH_TYPES.has(type)) return { ...HIGH };
  if (type === 'game.updated') {
    const action = typeof payload?.action === 'string' ? payload.action : null;
    if (action !== null && GAME_NOTIFYING_ACTIONS.has(action)) return { ...HIGH };
    if (action !== null && GAME_UNPUSHED_ACTIONS.has(action)) return { ...NONE };
  }
  // Receipts, media.ready, lists, memories, milestones, profile/couple changes, the SOS sender's
  // "seen"/"delivered" ticks, location.stopped and anything unknown: a silent refresh.
  return { ...NORMAL };
}

/**
 * The recipient turned notifications off: send a silent normal-priority push instead of none at
 * all, so their widgets still change. It used to be skipped for good, which froze the widgets. A
 * high push the app answers with no notification would also teach FCM to deprioritise the phone.
 * (Quiet hours stay high: the app still posts, silently, on its quiet channels.) Bypass types (SOS)
 * stay high.
 */
export function quietPolicy(policy: PushPolicy): PushPolicy {
  if (policy.bypassPrefs || policy.priority !== 'high') return policy;
  return { ...NORMAL };
}
