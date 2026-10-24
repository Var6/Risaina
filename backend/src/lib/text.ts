const segmenter = new Intl.Segmenter('en', { granularity: 'grapheme' });

export function graphemeCount(value: string): number {
  let n = 0;
  for (const _ of segmenter.segment(value)) n++;
  return n;
}

export function normaliseEmail(email: string): string {
  return email.trim().toLowerCase();
}
