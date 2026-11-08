import { describe, expect, it } from 'vitest';
import { dropReason } from './SourcesPanel';
import type { Health } from '../types';

function health(overrides: Partial<Health>): Health {
  return {
    sourceId: 'a',
    name: 'Source A',
    state: 'OK',
    programmeCount: 100,
    dropped: 0,
    duplicateSlots: 0,
    partialOverlaps: 0,
    lostToHigherPriority: 0,
    error: null,
    ...overrides,
  };
}

describe('the dropped column', () => {
  it('says nothing rather than zero when the source lost nothing', () => {
    expect(dropReason(health({}))).toBe('—');
  });

  it('names every reason class that contributed', () => {
    expect(dropReason(health({ dropped: 12, duplicateSlots: 5, partialOverlaps: 4, lostToHigherPriority: 3 })))
      .toBe('12 (5 duplicate slot, 4 self-overlap, 3 lower priority)');
  });

  it('omits a class that contributed nothing, so the number still adds up', () => {
    expect(dropReason(health({ dropped: 7, partialOverlaps: 7 }))).toBe('7 (7 self-overlap)');
  });
});
