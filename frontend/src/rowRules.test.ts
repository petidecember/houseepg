import { describe, expect, it } from 'vitest';
import { UNMATCHED, accepted, chosen, writable } from './rowRules';
import type { ReviewRow } from './types';

function row(overrides: Partial<ReviewRow> = {}): ReviewRow {
  return {
    source: 'provider-b',
    sourceChannelId: 'ATV Extra (HD).hu',
    sourceChannelName: 'ATV Extra',
    sourceIconUrl: null,
    canonicalId: 'ATV Extra (HD).hu',
    confidence: 'NONE',
    state: 'UNMATCHED',
    curated: false,
    candidates: [],
    ...overrides,
  };
}

const candidate = (id: string) => ({
  id,
  name: id,
  iconUrl: null,
  qualityTag: null,
  confidence: 'NORMALIZED_NAME',
  score: 0.6,
});

const registry = (...ids: string[]) => new Map(ids.map((id) => [id, { id }]));

describe('chosen', () => {
  it('prefers the pick the user made', () => {
    const picked = row({ candidates: [candidate('AMC.hu')] });

    expect(chosen(picked, { 'provider-b|ATV Extra (HD).hu': 'Bravo.hu' }, registry('Bravo.hu'))).toBe('Bravo.hu');
  });

  it('falls back to the best candidate', () => {
    const suggested = row({ candidates: [candidate('AMC.hu'), candidate('Bravo.hu')] });

    expect(chosen(suggested, {}, registry('AMC.hu'))).toBe('AMC.hu');
  });

  it('falls back to the channel the row already points at, when the registry has it', () => {
    const attached = row({ state: 'AUTO', canonicalId: 'AMC.hu', confidence: 'NORMALIZED_NAME' });

    expect(chosen(attached, {}, registry('AMC.hu'))).toBe('AMC.hu');
  });

  it('resolves a row the matcher could not attach to the unmatched sentinel, never to nothing', () => {
    const unmatched = row();

    expect(chosen(unmatched, {}, registry('AMC.hu'))).toBe(UNMATCHED);
    expect(chosen(unmatched, {}, registry('AMC.hu'))).not.toBe('');
  });

  it('keeps the sentinel distinguishable from any channel id', () => {
    expect(UNMATCHED).not.toBe('');
    expect(registry(UNMATCHED, 'AMC.hu').has(UNMATCHED)).toBe(true);
    // A pass-through id is the source's own id; the sentinel must not be reachable through one.
    expect(row().canonicalId).not.toBe(UNMATCHED);
  });
});

describe('writable', () => {
  it('is true when the user picked a channel, even for a row the matcher could not attach', () => {
    expect(writable(row(), { 'provider-b|ATV Extra (HD).hu': 'AMC.hu' })).toBe(true);
  });

  it('is true when the matcher offered a candidate', () => {
    expect(writable(row({ candidates: [candidate('AMC.hu')] }), {})).toBe(true);
  });

  it('is false for a row with neither, so the sentinel can never be written', () => {
    expect(writable(row(), {})).toBe(false);
  });
});

describe('accepted', () => {
  const picked = row({ sourceChannelId: 'picked', candidates: [candidate('AMC.hu')] });
  const suggested = row({ sourceChannelId: 'suggested', candidates: [candidate('Bravo.hu')] });
  const unticked = row({ sourceChannelId: 'unticked', candidates: [candidate('AMC.hu')] });
  const curated = row({ sourceChannelId: 'curated', candidates: [candidate('AMC.hu')], curated: true });
  const bare = row({ sourceChannelId: 'bare' });

  it('is the rows on screen that are writable, not curated and not unticked', () => {
    const rows = [picked, suggested, unticked, curated, bare];

    expect(accepted(rows, {}, new Set(['provider-b|unticked']))).toEqual([picked, suggested]);
  });

  it('includes a row the user rescued by hand, and never the sentinel-only row', () => {
    const pickedByHand = { 'provider-b|bare': 'AMC.hu' };

    expect(accepted([bare], pickedByHand, new Set())).toEqual([bare]);
    expect(accepted([bare], {}, new Set())).toEqual([]);
  });
});
