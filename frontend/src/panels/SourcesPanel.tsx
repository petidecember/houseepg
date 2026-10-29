import { useState } from 'react';
import { api } from '../api';
import { useLoader } from '../useLoader';
import type { Health } from '../types';

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
              <td colSpan={5} className="muted">
                No sources are configured.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  );
}
