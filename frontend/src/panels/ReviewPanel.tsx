import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import { useLoader } from '../useLoader';
import { UNMATCHED, accepted as acceptedRows, chosen as chosenFor, writable as isWritable } from '../rowRules';
import {
  consume,
  excludedKeys,
  load,
  picksOf,
  reconcile,
  rowKey,
  save,
  withControls,
  withExcluded,
  withPick,
} from '../reviewState';
import type { ReviewControls, ReviewFilter, ReviewWork } from '../reviewState';
import type { Decision, ReviewRow } from '../types';

/**
 * The match review: one row per source channel that still needs a decision, the source's own logo next
 * to the channel it is attached to, and one action per row. The state is derived by the server — the
 * same target is a confirmation, a different one is a correction — so the user is never asked a question
 * that has no consequence.
 *
 * What the user has done so far lives in the browser (see `reviewState`): the controls, the picks and
 * the unticked rows survive a reload, and a write consumes only the rows it wrote, so a save is not a
 * reset of everything else.
 */
export function ReviewPanel() {
  const [work, setWork] = useState<ReviewWork>(() => load(localStorage));
  const [notice, setNotice] = useState<{ text: string; tone: 'ok' | 'bad' } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [preview, setPreview] = useState<number | null>(null);

  const { filter, fuzzy, debug, query } = work.controls;
  const fetchReview = useCallback(() => api.review(fuzzy, filter), [fuzzy, filter]);
  const { data, error, loading, reload } = useLoader(fetchReview);

  // The panel's state is the browser's copy of it: one write-through, no other place to keep it.
  useEffect(() => {
    save(localStorage, work);
  }, [work]);

  // Work only means something while the payload still has the row and the channel it names. What
  // survives is exactly what the user left behind, unticked rows included.
  useEffect(() => {
    if (data) {
      setWork((current) => reconcile(current, data));
    }
  }, [data]);

  const channels = data?.channels ?? [];
  const rows = data?.rows ?? [];
  const byId = useMemo(() => new Map(channels.map((channel) => [channel.id, channel])), [channels]);
  const target = useMemo(() => picksOf(work), [work]);
  const excluded = useMemo(() => excludedKeys(work), [work]);

  const update = (patch: Partial<ReviewControls>) => setWork((current) => withControls(current, patch));

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (needle === '') {
      return rows;
    }
    return rows.filter(
      (row) =>
        row.sourceChannelId.toLowerCase().includes(needle) ||
        (row.sourceChannelName ?? '').toLowerCase().includes(needle) ||
        row.canonicalId.toLowerCase().includes(needle) ||
        row.source.toLowerCase().includes(needle),
    );
  }, [rows, query]);

  /** What the row is attached to; the rule lives in `rowRules` so it can be tested without rendering. */
  const chosen = (row: ReviewRow): string => chosenFor(row, target, byId);

  /**
   * Whether a write is possible at all: a pick the user made, or a candidate the matcher offered. The
   * display fallback — the row's current match id — is not a choice, so it never makes a row writable,
   * and neither does the unmatched sentinel.
   */
  const writable = (row: ReviewRow): boolean => isWritable(row, target);

  /** What one accept writes: the rows on screen, writable, not already decided and not unticked. */
  const accepted = acceptedRows(visible, target, excluded);

  const decisions = (rows: ReviewRow[]): Decision[] =>
    rows.map((row) => ({
      source: row.source,
      sourceChannelId: row.sourceChannelId,
      canonicalId: chosen(row),
    }));

  const saveRow = async (row: ReviewRow) => {
    const canonicalId = chosen(row);
    if (canonicalId === UNMATCHED) {
      return;
    }
    setBusy(rowKey(row));
    setNotice(null);
    try {
      await api.decide({ source: row.source, sourceChannelId: row.sourceChannelId, canonicalId });
      setNotice({
        text: `${row.sourceChannelName ?? row.sourceChannelId} → ${byId.get(canonicalId)?.name ?? canonicalId}`,
        tone: 'ok',
      });
      // The row is written, so its entry is done; every other row keeps the state the user left it in.
      setWork((current) => consume(current, [rowKey(row)]));
      await reload();
    } catch (e) {
      setNotice({ text: e instanceof Error ? e.message : String(e), tone: 'bad' });
    } finally {
      setBusy(null);
    }
  };

  const previewAccept = async () => {
    setNotice(null);
    try {
      const result = await api.accept(decisions(accepted), true);
      setPreview(result.count);
    } catch (e) {
      setNotice({ text: e instanceof Error ? e.message : String(e), tone: 'bad' });
    }
  };

  const applyAccept = async () => {
    setBusy('accept');
    const written = accepted.map(rowKey);
    try {
      const result = await api.accept(decisions(accepted), false);
      setPreview(null);
      setNotice({ text: `Curated ${result.count} row(s).`, tone: 'ok' });
      setWork((current) => consume(current, written));
      await reload();
    } catch (e) {
      setNotice({ text: e instanceof Error ? e.message : String(e), tone: 'bad' });
    } finally {
      setBusy(null);
    }
  };

  if (loading && !data) {
    return <p className="muted">Loading the review…</p>;
  }
  if (error) {
    return <p className="error">{error}</p>;
  }

  return (
    <section>
      <h2>Match review</h2>
      <p className="hint">
        One row per source channel that still needs a decision. The dropdown opens on the best candidate; the
        source logo on the left and the selected channel's logo on the right are there to compare. A saved
        decision survives every later automatic pass. Uncheck the rows that look wrong and accept the rest in
        one go.
      </p>
      {notice && <p className={notice.tone === 'bad' ? 'error' : 'ok-note'}>{notice.text}</p>}
      <div className="actions">
        <label>
          Show{' '}
          <select value={filter} onChange={(event) => update({ filter: event.target.value as ReviewFilter })}>
            <option value="needs-decision">Needs a decision</option>
            <option value="unmatched">Unmatched</option>
            <option value="curated">Curated</option>
            <option value="all">All</option>
          </select>
        </label>
        <label>
          <input
            type="checkbox"
            checked={fuzzy}
            onChange={(event) => update({ fuzzy: event.target.checked })}
          />{' '}
          Suggest similar names
        </label>
        <label>
          <input type="checkbox" checked={debug} onChange={(event) => update({ debug: event.target.checked })} />{' '}
          Debug
        </label>
        <input
          type="search"
          placeholder="Filter by channel name, id or source"
          value={query}
          onChange={(event) => update({ query: event.target.value })}
        />
        <span className="muted">
          {visible.length} row(s), {channels.length} registry channels
        </span>
        {preview === null ? (
          <button
            type="button"
            onClick={() => void previewAccept()}
            disabled={busy !== null || accepted.length === 0}
          >
            Accept matches…
          </button>
        ) : (
          <span className="bulk-confirm">
            <span className="muted">Would curate {preview} row(s).</span>
            <button type="button" onClick={() => void applyAccept()} disabled={busy !== null || preview === 0}>
              Apply
            </button>
            <button type="button" onClick={() => setPreview(null)} disabled={busy !== null}>
              Cancel
            </button>
          </span>
        )}
      </div>
      <table>
        <thead>
          <tr>
            <th className="pick" />
            <th>Source channel</th>
            <th>Matched to</th>
            {debug && <th>Evidence</th>}
          </tr>
        </thead>
        <tbody>
          {visible.map((row) => {
            const id = rowKey(row);
            const attached = chosen(row);
            const selected = byId.get(attached);
            return (
              <tr key={id}>
                <td className="pick">
                  <input
                    type="checkbox"
                    aria-label={`Include ${row.sourceChannelName ?? row.sourceChannelId}`}
                    checked={!excluded.has(id)}
                    disabled={!writable(row) || row.curated || busy !== null}
                    onChange={(event) => setWork((current) => withExcluded(current, id, !event.target.checked))}
                  />
                </td>
                <td className="source-cell">
                  <ChannelLogo url={row.sourceIconUrl} name={row.sourceChannelName ?? row.sourceChannelId} />
                  <span>
                    <strong>{row.sourceChannelName ?? row.sourceChannelId}</strong>
                    <span className="muted"> {row.source}</span>
                  </span>
                </td>
                <td className="decide">
                  <select
                    value={chosen(row)}
                    onChange={(event) => setWork((current) => withPick(current, id, event.target.value))}
                    disabled={busy !== null}
                  >
                    {attached === UNMATCHED && (
                      <option value={UNMATCHED}>Unmatched — served as {row.canonicalId}</option>
                    )}
                    {row.candidates.length > 0 && (
                      <optgroup label="Best matches">
                        {row.candidates.map((candidate) => (
                          <option key={candidate.id} value={candidate.id}>
                            {optionLabel(candidate)}
                          </option>
                        ))}
                      </optgroup>
                    )}
                    <optgroup label="All channels">
                      {channels.map((channel) => (
                        <option key={channel.id} value={channel.id}>
                          {optionLabel(channel)}
                        </option>
                      ))}
                    </optgroup>
                  </select>
                  <ChannelLogo url={selected?.iconUrl ?? null} name={selected?.name ?? (attached === UNMATCHED ? row.canonicalId : '—')} />
                  <button
                    type="button"
                    onClick={() => void saveRow(row)}
                    disabled={busy !== null || !writable(row)}
                  >
                    Save decision
                  </button>
                </td>
                {debug && (
                  <td className="muted debug">
                    {row.state} · {row.confidence}
                    {row.candidates[0] ? ` · ${row.candidates[0].score.toFixed(2)}` : ''}
                  </td>
                )}
              </tr>
            );
          })}
          {visible.length === 0 && (
            <tr>
              <td colSpan={debug ? 4 : 3} className="muted">
                Nothing needs a decision here.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  );
}

/** A logo, or a neutral placeholder: a missing icon must not look like a broken page. */
function ChannelLogo({ url, name }: { url: string | null; name: string }) {
  const [failed, setFailed] = useState(false);
  if (!url || failed) {
    return (
      <span className="logo placeholder" aria-hidden="true">
        {initials(name)}
      </span>
    );
  }
  return <img className="logo" src={url} alt="" onError={() => setFailed(true)} />;
}

function initials(name: string): string {
  const words = name.split(/[^A-Za-z0-9]+/).filter((word) => word !== '');
  return words
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? '')
    .join('');
}

/** Name plus the quality tag, unless the name already carries it. No channel number: it is noise here. */
function optionLabel(channel: { name: string; qualityTag: string | null }): string {
  const tag = channel.qualityTag?.toUpperCase();
  if (!tag || channel.name.toUpperCase().includes(tag)) {
    return channel.name;
  }
  return `${channel.name} · ${tag}`;
}

