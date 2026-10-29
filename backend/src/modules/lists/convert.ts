import type { ListItem, ListMarker } from '../../db/types.js';

/**
 * Pure helpers for POST /lists/:id/convert. A note's lines become checklist items and back, so the
 * text a person typed is never lost: whatever does not fit the limits stays where it was (the rest
 * of the body, or the remaining items).
 */

export interface ParsedLine {
  text: string;
  done: boolean;
}

// A leading bullet or number ("- ", "* ", "• ", "1. ", "2) "). A marker needs a space after it, so
// "-5 °C" and "1.5 kg rice" keep their text.
const MARKER_RE = /^(?:[-*•·◦‣]|\d{1,3}[.)])(?:\s+|$)/u;
// A checkbox: Markdown "[ ]" / "[x]", or the glyphs this module writes ("☐", "☑") and common ticks.
const BOX_RE = /^(\[\s?\]|\[[xX]\]|☐|☑|☒|✓|✔|✅)(?:\s+|$)/u;
// Only an x or a tick mark ticks a box. Anything else BOX_RE takes is an empty box, including the
// tab or no-break space a checklist copied from a web page has between its brackets.
const TICKED_BOX_RE = /^(?:\[[xX]\]|[☑☒✓✔✅])$/u;

/** One body line without its bullet, number and checkbox, or null when nothing is left. */
export function parseLine(line: string): ParsedLine | null {
  let s = line.trim();
  let done = false;
  let marker = false;
  let box = false;
  // A marker and a box may come in either order ("1. [x] milk", "☑ 1. milk"), each at most once.
  for (let pass = 0; pass < 2; pass++) {
    const m = marker ? null : MARKER_RE.exec(s);
    if (m) {
      s = s.slice(m[0].length);
      marker = true;
      continue;
    }
    const b = box ? null : BOX_RE.exec(s);
    if (b) {
      done = TICKED_BOX_RE.test(b[1]!);
      s = s.slice(b[0].length);
      box = true;
      continue;
    }
    break;
  }
  s = s.trim();
  return s === '' ? null : { text: s, done };
}

const isHighSurrogate = (code: number) => code >= 0xd800 && code <= 0xdbff;

/** Cuts text into pieces of at most `max` UTF-16 units, at the last space when there is one. */
export function splitText(text: string, max: number): string[] {
  const out: string[] = [];
  let s = text.trim();
  while (s.length > max) {
    let cut = max;
    for (let i = max; i > 0; i--) {
      if (/\s/.test(s[i]!)) {
        cut = i;
        break;
      }
    }
    // A hard cut must not split an emoji (or any surrogate pair) in half.
    if (cut === max && isHighSurrogate(s.charCodeAt(cut - 1))) cut--;
    const piece = s.slice(0, cut).trim();
    if (piece) out.push(piece);
    s = s.slice(cut).trim();
  }
  if (s) out.push(s);
  return out;
}

/**
 * Note → checklist: each non-blank line becomes an item (markers stripped, "[x]" ticks it, lines
 * over `maxText` cut into several items). Once `room` items are reached, that line and every line
 * after it stay in the body, unchanged.
 */
export function bodyToItems(
  body: string,
  room: number,
  maxText: number,
): { items: ParsedLine[]; rest: string } {
  const lines = body.split(/\r\n|\r|\n/);
  const items: ParsedLine[] = [];
  for (let i = 0; i < lines.length; i++) {
    const parsed = parseLine(lines[i]!);
    if (!parsed) continue;
    const pieces = splitText(parsed.text, maxText);
    if (items.length + pieces.length > room) return { items, rest: lines.slice(i).join('\n') };
    for (const text of pieces) items.push({ text, done: parsed.done });
  }
  return { items, rest: '' };
}

/** The line prefix for an item at `index` in a list drawn with this format. */
export function linePrefix(index: number, done: boolean, marker: ListMarker, checkboxes: boolean): string {
  const lead = marker === 'number' ? `${index + 1}. ` : marker === 'bullet' ? '• ' : '';
  return lead + (checkboxes ? (done ? '☑ ' : '☐ ') : '');
}

/**
 * Checklist → note: the items are written below the existing body as lines, drawn as the list was
 * shown (numbers, bullets, ☐ / ☑). Items whose line would take the body over `maxBody` stay items.
 */
export function itemsToBody(
  items: ListItem[],
  format: { marker: ListMarker; checkboxes: boolean },
  body: string,
  maxBody: number,
): { body: string; rest: ListItem[] } {
  let out = body.replace(/\s+$/, '');
  for (let i = 0; i < items.length; i++) {
    const item = items[i]!;
    const line = linePrefix(i, item.done, format.marker, format.checkboxes) + item.text;
    const next = out === '' ? line : `${out}\n${line}`;
    if (next.length > maxBody) return { body: out, rest: items.slice(i) };
    out = next;
  }
  return { body: out, rest: [] };
}
