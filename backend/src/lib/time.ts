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
