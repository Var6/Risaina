import { describe, expect, it } from 'vitest';
import { pushRefFor, pushSosFieldsFor } from '../src/workers/outbox.js';

// Push data must stay content-free: only opaque ids and a timestamp may ride along.
describe('push data fields', () => {
  it('sos.raised carries the SOS id and its creation time in epoch ms, never the message', () => {
    const fields = pushSosFieldsFor({
      type: 'sos.raised',
      payload: { sos: { id: 'abc123', createdAt: '2026-10-26T06:00:00.000Z', message: 'help' } },
    });
    expect(fields).toEqual({ sosId: 'abc123', at: String(Date.parse('2026-10-26T06:00:00.000Z')) });
    expect(JSON.stringify(fields)).not.toContain('help');
  });

  it('other sos events carry only the SOS id', () => {
    expect(pushSosFieldsFor({ type: 'sos.resolved', payload: { sosId: 'abc123', resolvedAt: 'x' } })).toEqual(
      { sosId: 'abc123' },
    );
    expect(pushSosFieldsFor({ type: 'sos.acknowledged', payload: { sosId: 'abc123' } })).toEqual({
      sosId: 'abc123',
    });
  });

  it('tolerates a malformed SOS payload and ignores every other type', () => {
    expect(
      pushSosFieldsFor({ type: 'sos.raised', payload: { sos: { id: 7, createdAt: 'not a date' } } }),
    ).toEqual({});
    expect(pushSosFieldsFor({ type: 'ping.created', payload: { sosId: 'x' } })).toEqual({});
  });

  it('ref is the message or ping id only', () => {
    expect(pushRefFor({ type: 'message.created', payload: { message: { id: 'm1', text: 'hi' } } })).toEqual({
      ref: 'm1',
    });
    expect(pushRefFor({ type: 'ping.created', payload: { ping: { id: 'p1' } } })).toEqual({ ref: 'p1' });
    expect(pushRefFor({ type: 'list.updated', payload: { list: { id: 'l1' } } })).toEqual({});
  });
});
