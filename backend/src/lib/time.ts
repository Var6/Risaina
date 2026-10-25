export type Clock = () => Date;

export const systemClock: Clock = () => new Date();

export function addSeconds(d: Date, s: number): Date {
  return new Date(d.getTime() + s * 1000);
}

export function addMinutes(d: Date, m: number): Date {
  return addSeconds(d, m * 60);
}

export function addHours(d: Date, h: number): Date {
  return addMinutes(d, h * 60);
}

export function addDays(d: Date, days: number): Date {
  return addHours(d, days * 24);
}

export function iso(d: Date): string {
  return d.toISOString();
}

export function isoOrNull(d: Date | null | undefined): string | null {
  return d ? d.toISOString() : null;
}

export function isValidTimeZone(tz: string): boolean {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

const BIRTHDAY_RE = /^(?:(\d{4})|-)-(\d{2})-(\d{2})$/;

/**
 * 'YYYY-MM-DD' (a real calendar date, year >= 1900, not after today + 1 day for time-zone slack) or
 * '--MM-DD' when the year is private (Feb 29 allowed there, since some year has it).
 */
export function isValidBirthday(s: string, now: Date = new Date()): boolean {
  const m = BIRTHDAY_RE.exec(s);
  if (!m) return false;
  const month = Number(m[2]);
  const day = Number(m[3]);
  if (month < 1 || month > 12 || day < 1) return false;
  if (m[1] === undefined) return day <= new Date(Date.UTC(2000, month, 0)).getUTCDate(); // 2000: leap
  const year = Number(m[1]);
  if (year < 1900) return false;
  const d = new Date(Date.UTC(year, month - 1, day));
  if (d.getUTCMonth() !== month - 1 || d.getUTCDate() !== day) return false;
  return d.getTime() <= now.getTime() + 24 * 3600 * 1000;
}

/**
 * Fixes letter case only ('europe/berlin' → 'Europe/Berlin'); assumes isValidTimeZone(tz). Other
 * valid ids are kept exactly as sent: ICU would rewrite current names to legacy aliases
 * ('Asia/Kolkata' → 'Asia/Calcutta'), which clients should not see change under them.
 */
export function canonicalTimeZone(tz: string): string {
  const resolved = new Intl.DateTimeFormat('en-US', { timeZone: tz }).resolvedOptions().timeZone;
  return resolved.toLowerCase() === tz.toLowerCase() ? resolved : tz;
}
