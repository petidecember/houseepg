import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import { useLoader } from '../useLoader';
import type { ReviewRow } from '../types';

const DEBUG_KEY = 'houseepg.review.debug';

type Filter = 'needs-decision' | 'all' | 'curated' | 'unmatched';

/**
 * The match review: one row per source channel that still needs a decision, the source's own logo next
 * to the channel it is attached to, and one action per row. The state is derived by the server — the
 * same target is a confirmation, a different one is a correction — so the user is never asked a question
 * that has no consequence.
 */
export function ReviewPanel() {
  const [filter, setFilter] = useState<Filter>('needs-decision');
  const [fuzzy, setFuzzy] = useState(false);
  const [debug, setDebug] = useState(() => localStorage.getItem(DEBUG_KEY) === '1');
  const [query, setQuery] = useState('');
  const [target, setTarget] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState<{ text: string; tone: 'ok' | 'bad' } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [preview, setPreview] = useState<number | null>(null);

  const load = useCallback(() => api.review(fuzzy, filter), [fuzzy, filter]);
  const { data, error, loading, reload } = useLoader(load);

  useEffect(() => {
    localStorage.setItem(DEBUG_KEY, debug ? '1' : '0');
  }, [debug]);

  const channels = data?.channels ?? [];
  const rows = data?.rows ?? [];
  const byId = useMemo(() => new Map(channels.map((channel) => [channel.id, channel])), [channels]);

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

  /** The value the dropdown opens on: the user's pick, else the best candidate, else the current match. */
  const chosen = (row: ReviewRow): string => {
    const picked = target[key(row)];
    if (picked) {
      return picked;
    }
    const best = row.candidates[0]?.id;
    if (best) {
      return best;
    }
    return byId.has(row.canonicalId) ? row.canonicalId : '';
  };

  const save = async (row: ReviewRow) => {
    const canonicalId = chosen(row);
    if (canonicalId === '') {
      return;
    }
    setBusy(key(row));
    setNotice(null);
    try {
      await api.decide({ source: row.source, sourceChannelId: row.sourceChannelId, canonicalId });
      setNotice({
        text: `${row.sourceChannelName ?? row.sourceChannelId} → ${byId.get(canonicalId)?.name ?? canonicalId}`,
        tone: 'ok',
      });
      await reload();
    } catch (e) {
      setNotice({ text: e instanceof Error ? e.message : String(e), tone: 'bad' });
    } finally {
      setBusy(null);
    }
  };

  const previewBulk = async () => {
    setNotice(null);
    try {
      const result = await api.bulkAccept(true);
      setPreview(result.count);
    } catch (e) {
      setNotice({ text: e instanceof Error ? e.message : String(e), tone: 'bad' });
    }
  };

  const applyBulk = async () => {
    setBusy('bulk');
    try {
      const result = await api.bulkAccept(false);
      setPreview(null);
      setNotice({ text: `Curated ${result.count} row(s).`, tone: 'ok' });
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
        decision survives every later automatic pass.
      </p>
      {notice && <p className={notice.tone === 'bad' ? 'error' : 'ok-note'}>{notice.text}</p>}
      <div className="actions">
        <label>
          Show{' '}
          <select value={filter} onChange={(event) => setFilter(event.target.value as Filter)}>
            <option value="needs-decision">Needs a decision</option>
            <option value="unmatched">Unmatched</option>
            <option value="curated">Curated</option>
            <option value="all">All</option>
          </select>
        </label>
        <label>
          <input type="checkbox" checked={fuzzy} onChange={(event) => setFuzzy(event.target.checked)} /> Suggest
          similar names
        </label>
        <label>
          <input type="checkbox" checked={debug} onChange={(event) => setDebug(event.target.checked)} /> Debug
        </label>
        <input
          type="search"
          placeholder="Filter by channel name, id or source"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <span className="muted">
          {visible.length} row(s), {channels.length} registry channels
        </span>
        {preview === null ? (
          <button type="button" onClick={() => void previewBulk()} disabled={busy !== null}>
            Accept exact matches…
          </button>
        ) : (
          <span className="bulk-confirm">
            <span className="muted">Would curate {preview} row(s).</span>
            <button type="button" onClick={() => void applyBulk()} disabled={busy !== null || preview === 0}>
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
            <th>Source channel</th>
            <th>Matched to</th>
            {debug && <th>Evidence</th>}
          </tr>
        </thead>
        <tbody>
          {visible.map((row) => {
            const id = key(row);
            const selected = byId.get(chosen(row));
            return (
              <tr key={id}>
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
                    onChange={(event) => setTarget({ ...target, [id]: event.target.value })}
                    disabled={busy !== null}
                  >
                    {chosen(row) === '' && <option value="">Choose a channel…</option>}
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
                  <ChannelLogo url={selected?.iconUrl ?? null} name={selected?.name ?? '—'} />
                  <button
                    type="button"
                    onClick={() => void save(row)}
                    disabled={busy !== null || chosen(row) === ''}
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
              <td colSpan={debug ? 3 : 2} className="muted">
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

function key(row: ReviewRow): string {
  return `${row.source}|${row.sourceChannelId}`;
}
