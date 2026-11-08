import { useState } from 'react';
import { api } from '../api';
import { useLoader } from '../useLoader';
import type { Health } from '../types';

/**
 * What the slot rule took from this source: entries the feed does not serve because another entry already
 * claimed an overlapping slot (ADR-0001). A bare programme count hides that loss entirely, which is how
 * an 8 % drop on live-hutv went unnoticed. The reason classes are the operator's lever — a duplicate slot
 * is a provider shipping an entry twice, a self-overlap is one publishing a nested pair, and a loss to a
 * higher priority is the rule working as intended.
 */
export function dropReason(health: Health): string {
  if (health.dropped <= 0) {
    return '—';
  }
  const parts: string[] = [];
  if (health.duplicateSlots > 0) {
    parts.push(`${health.duplicateSlots} duplicate slot`);
  }
  if (health.partialOverlaps > 0) {
    parts.push(`${health.partialOverlaps} self-overlap`);
  }
  if (health.lostToHigherPriority > 0) {
    parts.push(`${health.lostToHigherPriority} lower priority`);
  }
  return `${health.dropped} (${parts.join(', ')})`;
}

/** Per-source health and "refresh now"; the state a failed fetch leaves is the point of the panel. */
export function SourcesPanel() {
  const { data, error, loading, reload } = useLoader(api.health);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const refresh = async (sourceId: string) => {
    setBusy(sourceId);
    setNotice(null);
    try {
      const health = await api.refresh(sourceId);
      setNotice(`${health.sourceId}: ${health.state} (${health.programmeCount} programmes)`);
      await reload();
    } catch (e) {
      setNotice(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  };

  if (loading && !data) {
    return <p className="muted">Loading sources…</p>;
  }
  if (error) {
    return <p className="error">{error}</p>;
  }

  return (
    <section>
      <h2>Sources</h2>
      <p className="hint">
        Sources are declared in the YAML configuration. This panel can only refresh them and show what the last
        refresh did; adding, editing or disabling one is a file change followed by a reload (ADR-0005).
      </p>
      {notice && <p className="notice">{notice}</p>}
      <table>
        <thead>
          <tr>
            <th>Source</th>
            <th>State</th>
            <th>Programmes</th>
            <th>Dropped</th>
            <th>Last error</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {(data ?? []).map((health: Health) => (
            <tr key={health.sourceId}>
              <td>
                <strong>{health.name}</strong>
                <span className="muted"> {health.sourceId}</span>
              </td>
              <td>
                <span className={`badge ${health.state.toLowerCase()}`}>{health.state}</span>
              </td>
              <td>{health.programmeCount}</td>
              <td>{dropReason(health)}</td>
              <td className="error">{health.error ?? ''}</td>
              <td>
                <button type="button" onClick={() => void refresh(health.sourceId)} disabled={busy !== null}>
                  {busy === health.sourceId ? 'Refreshing…' : 'Refresh now'}
                </button>
              </td>
            </tr>
          ))}
          {(data ?? []).length === 0 && (
            <tr>
              <td colSpan={6} className="muted">
                No sources are configured.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  );
}
