import { describe, expect, it } from 'vitest';
import {
  DEFAULT_CONTROLS,
  LEGACY_DEBUG_KEY,
  STORAGE_KEY,
  consume,
  defaults,
  excludedKeys,
  load,
  picksOf,
  reconcile,
  rowKey,
  save,
  withControls,
  withExcluded,
  withPick,
} from './reviewState';
import type { ReviewPayload, ReviewWork } from './reviewState';

/** A storage that is only as real as these tests need: bytes in, bytes out, and one that can fail. */
function storage(seed: Record<string, string> = {}, failing = false): Storage {
  const items = new Map(Object.entries(seed));
  return {
    get length() {
      return items.size;
    },
    clear: () => items.clear(),
    getItem: (key: string) => items.get(key) ?? null,
    key: (index: number) => [...items.keys()][index] ?? null,
    removeItem: (key: string) => void items.delete(key),
    setItem: (key: string, value: string) => {
      if (failing) {
        throw new Error('QuotaExceededError');
      }
      items.set(key, value);
    },
  };
}

function payload(rows: [string, string][], channels: string[]): ReviewPayload {
  return {
    rows: rows.map(([source, sourceChannelId]) => ({ source, sourceChannelId })),
    channels: channels.map((id) => ({ id })),
  };
}

const ONE_ROW = payload([['provider-a', 'a1']], ['AnimalPlanet.HD', 'AMC.hu']);

describe('load', () => {
  it('starts from the defaults when nothing is stored', () => {
    expect(load(storage())).toEqual({ controls: DEFAULT_CONTROLS, rows: {} });
  });

  it('seeds the debug toggle from the key it used to live under', () => {
    expect(load(storage({ [LEGACY_DEBUG_KEY]: '1' })).controls.debug).toBe(true);
  });

  it('ignores the legacy key once the blob exists, so the blob is the only source', () => {
    const stored = storage({ [LEGACY_DEBUG_KEY]: '1' });
    save(stored, withControls(defaults(), { debug: false }));

    expect(load(stored).controls.debug).toBe(false);
  });

  it('ignores a blob from a version it does not know instead of guessing', () => {
    const stored = storage({ [STORAGE_KEY]: JSON.stringify({ v: 99, controls: { filter: 'all' }, rows: {} }) });

    expect(load(stored)).toEqual({ controls: DEFAULT_CONTROLS, rows: {} });
  });

  it('starts fresh on a blob that is not JSON at all', () => {
    expect(load(storage({ [STORAGE_KEY]: 'not json' }))).toEqual({ controls: DEFAULT_CONTROLS, rows: {} });
  });

  it('falls back per control on a value it cannot use, keeping the rest', () => {
    const stored = storage({
      [STORAGE_KEY]: JSON.stringify({
        v: 1,
        controls: { filter: 'nonsense', fuzzy: true, debug: 'yes', query: 7 },
        rows: {},
      }),
    });

    expect(load(stored).controls).toEqual({ filter: 'needs-decision', fuzzy: true, debug: false, query: '' });
  });

  it('drops entries that carry nothing and ignores parts of the wrong shape', () => {
    const stored = storage({
      [STORAGE_KEY]: JSON.stringify({
        v: 1,
        controls: {},
        rows: {
          'provider-a|a1': { pick: '', excluded: false },
          'provider-a|a2': 'a string',
          'provider-a|a3': { pick: 'AMC.hu' },
        },
      }),
    });

    expect(load(stored).rows).toEqual({ 'provider-a|a3': { pick: 'AMC.hu' } });
  });
});

describe('save', () => {
  it('round trips the controls and the entries', () => {
    const stored = storage();
    const work = withPick(withExcluded(withControls(defaults(), { filter: 'unmatched', query: 'amc' }), 'p|1', false), 'p|2', 'AMC.hu');
    save(stored, work);

    expect(load(stored)).toEqual(work);
  });

  it('does not throw when the browser refuses to store more', () => {
    expect(() => save(storage({}, true), defaults())).not.toThrow();
  });
});

describe('reconcile', () => {
  it('keeps a pick while the row and the channel it names are both in the payload', () => {
    const work = withPick(defaults(), 'provider-a|a1', 'AMC.hu');

    expect(reconcile(work, ONE_ROW)).toBe(work);
  });

  it('drops an entry whose row the payload no longer has', () => {
    const work = withPick(defaults(), 'provider-a|gone', 'AMC.hu');

    expect(reconcile(work, ONE_ROW).rows).toEqual({});
  });

  it('drops an entry whose pick names a channel the payload no longer has', () => {
    const work = withPick(defaults(), 'provider-a|a1', 'CHANNEL.THAT.LEFT');

    expect(reconcile(work, ONE_ROW).rows).toEqual({});
  });

  it('keeps an exclusion while its row exists, even though the row points elsewhere', () => {
    const work = withExcluded(defaults(), 'provider-a|a1', true);

    expect(reconcile(work, ONE_ROW).rows).toEqual({ 'provider-a|a1': { excluded: true } });
  });

  it('drops an exclusion whose row is gone', () => {
    const work = withExcluded(defaults(), 'provider-a|gone', true);

    expect(reconcile(work, ONE_ROW).rows).toEqual({});
  });

  it('keeps both halves when a row is unticked and re-pointed', () => {
    const work = withExcluded(withPick(defaults(), 'provider-a|a1', 'AMC.hu'), 'provider-a|a1', true);

    expect(reconcile(work, ONE_ROW).rows).toEqual({ 'provider-a|a1': { pick: 'AMC.hu', excluded: true } });
  });
});

describe('consume', () => {
  it('removes only the rows it was handed', () => {
    const work = withExcluded(
      withPick(withExcluded(defaults(), 'provider-a|a2', true), 'provider-a|a1', 'AMC.hu'),
      'provider-a|a3',
      true,
    );

    expect(consume(work, ['provider-a|a1']).rows).toEqual({
      'provider-a|a2': { excluded: true },
      'provider-a|a3': { excluded: true },
    });
  });

  it('returns the same object when it removes nothing', () => {
    const work = withExcluded(defaults(), 'provider-a|a2', true);

    expect(consume(work, [])).toBe(work);
    expect(consume(work, ['provider-a|not-there'])).toBe(work);
  });
});

describe('entries', () => {
  it('keeps both halves of a row that is picked and unticked', () => {
    const work = withExcluded(withPick(defaults(), 'p|1', 'AMC.hu'), 'p|1', true);

    expect(work.rows['p|1']).toEqual({ pick: 'AMC.hu', excluded: true });
  });

  it('keeps the pick when the row is unticked and ticked again', () => {
    const unticked = withExcluded(withPick(defaults(), 'p|1', 'AMC.hu'), 'p|1', true);

    expect(unticked.rows).toEqual({ 'p|1': { pick: 'AMC.hu', excluded: true } });
    expect(withExcluded(unticked, 'p|1', false).rows).toEqual({ 'p|1': { pick: 'AMC.hu' } });
  });

  it('stores no entry at all once a row has nothing left in it', () => {
    expect(withExcluded(withExcluded(defaults(), 'p|1', true), 'p|1', false).rows).toEqual({});
    expect(withPick(withPick(defaults(), 'p|1', 'AMC.hu'), 'p|1', '').rows).toEqual({});
  });

  it('treats the dropdown placeholder as no pick, so it cannot survive as a choice nobody made', () => {
    expect(withPick(withPick(defaults(), 'p|1', 'AMC.hu'), 'p|1', '').rows).toEqual({});
  });

  it('derives the picks and the unticked rows the panel renders', () => {
    const work = withPick(withExcluded(withExcluded(defaults(), 'p|2', true), 'p|3', false), 'p|1', 'AMC.hu');

    expect(picksOf(work)).toEqual({ 'p|1': 'AMC.hu' });
    expect(excludedKeys(work)).toEqual(new Set(['p|2']));
  });
});

describe('rowKey', () => {
  it('is the encoding the blob is keyed by, and stays that', () => {
    expect(rowKey({ source: 'provider-a', sourceChannelId: 'a1' })).toBe('provider-a|a1');
  });
});
