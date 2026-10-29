import { useMemo, useState } from 'react';
import { api } from '../api';
import { useLoader } from '../useLoader';
import type { Match } from '../types';

/**
 * The auto-match review: what each source channel was attached to, on what evidence, and the two
 * decisions that fix it. A curated row survives every later automatic pass.
 */
export function ReviewPanel() {
  const { data, error, loading, reload } = useLoader(api.registry);
  const [filter, setFilter] = useState<'unmatched' | 'all' | 'curated'>('unmatched');
  const [query, setQuery] = useState('');
  const [target, setTarget] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState<{ text: string; tone: 'ok' | 'bad' } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const channels = data?.channels ?? [];
  const matches = data?.matches ?? [];

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return matches
      .filter((match) => {
        if (filter === 'unmatched') return match.state === 'UNMATCHED';
        if (filter === 'curated') return match.curated;
        return true;
      })
      .filter(
        (match) =>
          needle === '' ||
          match.sourceChannelId.toLowerCase().includes(needle) ||
          match.canonicalId.toLowerCase().includes(needle) ||
          match.source.toLowerCase().includes(needle),
      )
      .slice(0, 200);
  }, [matches, filter, query]);

  const decide = async (match: Match, decision: 'confirmed' | 'corrected') => {
    const canonicalId = target[key(match)] ?? match.canonicalId;
    setBusy(key(match));
    setNotice(null);
    try {
      await api.decide({
        source: match.source,
        sourceChannelId: match.sourceChannelId,
        canonicalId,
        decision,
      });
      setNotice({ text: `${match.source}/${match.sourceChannelId} → ${canonicalId} (${decision}).`, tone: 'ok' });
      await reload();
    } catch (e) {
      setNotice({ text: e instanceof Error ? e.message : String(e), tone: 'bad' });
    } finally {
      setBusy(null);
    }
  };

  if (loading && !data) {
    return <p className="muted">Loading the registry…</p>;
  }
  if (error) {
    return <p className="error">{error}</p>;
  }

  return (
    <section>
      <h2>Match review</h2>
      <p className="hint">
        One row per source channel. Confirming freezes the match against later automatic passes; correcting
        points it at another registry channel. A channel with no match is served as a pass-through under its own
        id.
      </p>
      {notice && <p className={notice.tone === 'bad' ? 'error' : 'ok-note'}>{notice.text}</p>}
      <div className="actions">
        <label>
          Show{' '}
          <select value={filter} onChange={(event) => setFilter(event.target.value as typeof filter)}>
            <option value="unmatched">Unmatched ({matches.filter((m) => m.state === 'UNMATCHED').length})</option>
            <option value="curated">Curated ({matches.filter((m) => m.curated).length})</option>
            <option value="all">All ({matches.length})</option>
          </select>
        </label>
        <input
          type="search"
          placeholder="Filter by channel id, canonical id or source"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <span className="muted">{channels.length} registry channels</span>
      </div>
      <table>
        <thead>
          <tr>
            <th>Source channel</th>
            <th>Matched to</th>
            <th>Evidence</th>
            <th>Decide</th>
          </tr>
        </thead>
        <tbody>
          {visible.map((match) => {
            const id = key(match);
            return (
              <tr key={id}>
                <td>
                  <strong>{match.sourceChannelId}</strong>
                  <span className="muted"> {match.source}</span>
                </td>
                <td>{match.canonicalId}</td>
                <td>
                  <span className={`badge ${match.curated ? 'ok' : match.state === 'UNMATCHED' ? 'failed' : 'stale'}`}>
                    {match.state}
                  </span>
                  <span className="muted"> {match.confidence}</span>
                </td>
                <td className="decide">
                  <select
                    value={target[id] ?? match.canonicalId}
                    onChange={(event) => setTarget({ ...target, [id]: event.target.value })}
                    disabled={busy !== null}
                  >
                    {channels.map((channel) => (
                      <option key={channel.id} value={channel.id}>
                        {channel.name} ({channel.id})
                      </option>
                    ))}
                  </select>
                  <button type="button" onClick={() => void decide(match, 'confirmed')} disabled={busy !== null}>
                    Confirm
                  </button>
                  <button type="button" onClick={() => void decide(match, 'corrected')} disabled={busy !== null}>
                    Correct
                  </button>
                </td>
              </tr>
            );
          })}
          {visible.length === 0 && (
            <tr>
              <td colSpan={4} className="muted">
                Nothing to review here.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  );
}

function key(match: Match): string {
  return `${match.source}|${match.sourceChannelId}`;
}
