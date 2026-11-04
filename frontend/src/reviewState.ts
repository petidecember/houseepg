/**
 * The match review tab's own state, kept in the browser so a reload never loses work.
 *
 * Deliberately React-free: everything the panel has to remember — the view controls, the per-row picks
 * and the unticked rows — is loaded, changed and saved through plain functions, so the rules can be
 * tested without rendering anything.
 *
 * Validation happens twice, for two different reasons. `load` checks the blob itself, because
 * `localStorage` belongs to the browser and to every earlier version of this app. `reconcile` checks
 * the work against the payload, because a pick only means something while the channel it names is
 * still there.
 */

export type ReviewFilter = 'needs-decision' | 'unmatched' | 'curated' | 'all';

const FILTERS: readonly string[] = ['needs-decision', 'unmatched', 'curated', 'all'];

export type ReviewControls = {
  filter: ReviewFilter;
  fuzzy: boolean;
  debug: boolean;
  query: string;
};

/** One row's unsaved work. A row with no entry is accepted, which is how every fresh row starts. */
export type ReviewEntry = {
  pick?: string;
  excluded?: boolean;
};

export type ReviewWork = {
  controls: ReviewControls;
  rows: Record<string, ReviewEntry>;
};

/** The part of the payload validation needs: the rows that exist, and the channels a pick may name. */
export type ReviewPayload = {
  rows: { source: string; sourceChannelId: string }[];
  channels: { id: string }[];
};

export const STORAGE_KEY = 'houseepg.review.v1';
export const LEGACY_DEBUG_KEY = 'houseepg.review.debug';

const VERSION = 1;

export const DEFAULT_CONTROLS: ReviewControls = {
  filter: 'needs-decision',
  fuzzy: false,
  debug: false,
  query: '',
};

export function defaults(): ReviewWork {
  return { controls: { ...DEFAULT_CONTROLS }, rows: {} };
}

/** The row's identity inside the blob: used whole, never split back apart. */
export function rowKey(row: { source: string; sourceChannelId: string }): string {
  return `${row.source}|${row.sourceChannelId}`;
}

/**
 * The stored work, or the defaults. A blob from a version this one does not know is ignored rather
 * than guessed at. The debug toggle falls back to the key it used to live under, so the one setting
 * that already persisted keeps its value.
 */
export function load(storage: Storage): ReviewWork {
  const raw = read(storage, STORAGE_KEY);
  if (raw === null) {
    return { controls: { ...DEFAULT_CONTROLS, debug: read(storage, LEGACY_DEBUG_KEY) === '1' }, rows: {} };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return defaults();
  }
  if (!isRecord(parsed) || parsed.v !== VERSION) {
    return defaults();
  }
  return { controls: controlsOf(parsed.controls), rows: entriesOf(parsed.rows) };
}

export function save(storage: Storage, work: ReviewWork): void {
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify({ v: VERSION, controls: work.controls, rows: work.rows }));
  } catch {
    // A full or blocked storage must not break the panel: the in-memory state is still correct.
  }
}

/**
 * Drops the entries that no longer mean anything: a row the payload does not have, or a pick naming a
 * channel the payload does not have. An exclusion survives as long as its row does — the row is real
 * even when what it points at is not — and a row with no entry starts accepted.
 *
 * Returns the same object when nothing was dropped, so a caller can set state with it unconditionally.
 */
export function reconcile(work: ReviewWork, payload: ReviewPayload): ReviewWork {
  const rows = new Set(payload.rows.map(rowKey));
  const channels = new Set(payload.channels.map((channel) => channel.id));
  const kept: Record<string, ReviewEntry> = {};
  let dropped = 0;
  for (const [key, entry] of Object.entries(work.rows)) {
    const namesMissingChannel = entry.pick !== undefined && !channels.has(entry.pick);
    if (!rows.has(key) || namesMissingChannel) {
      dropped++;
      continue;
    }
    kept[key] = entry;
  }
  return dropped === 0 ? work : { ...work, rows: kept };
}

/** The rows just written are done: their entries are consumed, and nobody else's. */
export function consume(work: ReviewWork, keys: string[]): ReviewWork {
  const kept = { ...work.rows };
  let removed = false;
  for (const key of keys) {
    if (kept[key] !== undefined) {
      delete kept[key];
      removed = true;
    }
  }
  return removed ? { ...work, rows: kept } : work;
}

export function withControls(work: ReviewWork, patch: Partial<ReviewControls>): ReviewWork {
  return { ...work, controls: { ...work.controls, ...patch } };
}

export function withPick(work: ReviewWork, key: string, pick: string): ReviewWork {
  const entry: ReviewEntry = { ...work.rows[key] };
  if (pick === '') {
    // The dropdown's placeholder is not a pick: storing it would survive a reload as a choice nobody
    // made, and `load` drops it again anyway. One rule, both directions.
    delete entry.pick;
  } else {
    entry.pick = pick;
  }
  return writeEntry(work, key, entry);
}

export function withExcluded(work: ReviewWork, key: string, excluded: boolean): ReviewWork {
  const entry: ReviewEntry = { ...work.rows[key] };
  if (excluded) {
    entry.excluded = true;
  } else {
    delete entry.excluded;
  }
  return writeEntry(work, key, entry);
}

/** The picks the panel renders, keyed like the blob. */
export function picksOf(work: ReviewWork): Record<string, string> {
  const picks: Record<string, string> = {};
  for (const [key, entry] of Object.entries(work.rows)) {
    if (entry.pick !== undefined) {
      picks[key] = entry.pick;
    }
  }
  return picks;
}

/** The unticked rows. */
export function excludedKeys(work: ReviewWork): Set<string> {
  const keys = new Set<string>();
  for (const [key, entry] of Object.entries(work.rows)) {
    if (entry.excluded === true) {
      keys.add(key);
    }
  }
  return keys;
}

/** An entry with nothing left in it is not an entry, so it is stored as no entry at all. */
function writeEntry(work: ReviewWork, key: string, entry: ReviewEntry): ReviewWork {
  const rows = { ...work.rows };
  if (entry.pick === undefined && entry.excluded === undefined) {
    delete rows[key];
  } else {
    rows[key] = entry;
  }
  return { ...work, rows };
}

function controlsOf(raw: unknown): ReviewControls {
  const stored = isRecord(raw) ? raw : {};
  return {
    filter:
      typeof stored.filter === 'string' && FILTERS.includes(stored.filter)
        ? (stored.filter as ReviewFilter)
        : DEFAULT_CONTROLS.filter,
    fuzzy: stored.fuzzy === true,
    debug: stored.debug === true,
    query: typeof stored.query === 'string' ? stored.query : '',
  };
}

function entriesOf(raw: unknown): Record<string, ReviewEntry> {
  if (!isRecord(raw)) {
    return {};
  }
  const entries: Record<string, ReviewEntry> = {};
  for (const [key, value] of Object.entries(raw)) {
    if (!isRecord(value)) {
      continue;
    }
    const entry: ReviewEntry = {};
    if (typeof value.pick === 'string' && value.pick !== '') {
      entry.pick = value.pick;
    }
    if (value.excluded === true) {
      entry.excluded = true;
    }
    if (entry.pick === undefined && entry.excluded === undefined) {
      continue;
    }
    entries[key] = entry;
  }
  return entries;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function read(storage: Storage, key: string): string | null {
  try {
    return storage.getItem(key);
  } catch {
    return null;
  }
}
