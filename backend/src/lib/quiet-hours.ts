import { isValidTimeZone } from './time.js';

export interface QuietHoursWindow {
  enabled: boolean;
  start: string; // 'HH:mm'
  end: string; // 'HH:mm'
  timeZone: string; // IANA
}

export function parseHHmm(value: string): number | null {
  const m = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(value);
  if (!m) return null;
  return Number(m[1]) * 60 + Number(m[2]);
}

/** Minutes since local midnight for `at` in `timeZone`. */
export function localMinutes(at: Date, timeZone: string): number {
  const fmt = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  });
  let hour = 0;
  let minute = 0;
  for (const part of fmt.formatToParts(at)) {
    if (part.type === 'hour') hour = Number(part.value) % 24;
    if (part.type === 'minute') minute = Number(part.value);
  }
  return hour * 60 + minute;
}

/**
 * True when `at` falls inside the recipient's quiet-hours window, evaluated in the recipient's
 * time zone. Windows may cross midnight (e.g. 22:00 → 07:00). start === end is treated as an
 * empty window (never quiet) rather than a 24 h window.
 */
export function isInQuietHours(window: QuietHoursWindow, at: Date): boolean {
  if (!window.enabled) return false;
  const start = parseHHmm(window.start);
  const end = parseHHmm(window.end);
  if (start === null || end === null || start === end) return false;
  const tz = isValidTimeZone(window.timeZone) ? window.timeZone : 'UTC';
  const now = localMinutes(at, tz);
  if (start < end) return now >= start && now < end;
  // crosses midnight
  return now >= start || now < end;
}
