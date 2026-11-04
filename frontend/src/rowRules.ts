import { rowKey } from './reviewState';
import type { ReviewRow } from './types';

/**
 * The two rules the review panel applies to every row: what the row is attached to, and whether a
 * write is possible at all.
 *
 * They live here, away from the component, because they are the part that can be wrong in a way the
 * tests can catch: a row the matcher could not attach has no channel to show, and the value the
 * dropdown carries for it must never be mistaken for a channel somebody chose.
 */

/**
 * The value the dropdown carries when the matcher attached nothing: the row is served under its own
 * pass-through id. It is deliberately not a plausible channel id, so it cannot collide with one.
 */
export const UNMATCHED = '\u0000unmatched';

/** The part of the registry these rules need: whether a channel id exists. A `Map` satisfies it. */
export type ChannelLookup = { has(id: string): boolean };

/**
 * What the row is attached to: the user's pick, else the best candidate, else the channel it already
 * points at — or {@link UNMATCHED} when that id is not a registry channel, which is exactly the
 * matcher-less row. The dropdown shows the sentinel's own option, so the column never reads as empty.
 */
export function chosen(row: ReviewRow, picks: Record<string, string>, registry: ChannelLookup): string {
  const picked = picks[rowKey(row)];
  if (picked) {
    return picked;
  }
  const best = row.candidates[0]?.id;
  if (best) {
    return best;
  }
  return registry.has(row.canonicalId) ? row.canonicalId : UNMATCHED;
}

/**
 * A row can only be written when the user picked a channel or the matcher offered one. The display
 * fallback — the channel the row already points at — is not a choice, and {@link UNMATCHED} is not a
 * channel, so neither can make a row writable.
 */
export function writable(row: ReviewRow, picks: Record<string, string>): boolean {
  return Boolean(picks[rowKey(row)]) || row.candidates.length > 0;
}

/** What one bulk apply writes: the rows on screen, writable, not already decided and not unticked. */
export function accepted(
  rows: ReviewRow[],
  picks: Record<string, string>,
  excluded: Set<string>,
): ReviewRow[] {
  return rows.filter((row) => writable(row, picks) && !row.curated && !excluded.has(rowKey(row)));
}
