import { useEffect, useState } from 'react';
import { api } from '../api';
import { useLoader } from '../useLoader';
import type { Programme, Timeline } from '../types';

const LIMIT = 200;

/**
 * The merged guide for one channel: every programme as the winning source emitted it, so a wrong merge
 * order or a wrong match is visible rather than theoretical.
 */
export function GuidePanel() {
  const registry = useLoader(api.registry);
  const [selected, setSelected] = useState<string | null>(null);
  const [timeline, setTimeline] = useState<Timeline | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const channels = registry.data?.channels ?? [];

  useEffect(() => {
    if (selected === null && channels.length > 0) {
      setSelected(channels[0].id);
    }
  }, [channels, selected]);

  useEffect(() => {
    if (selected === null) {
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    api
      .preview(selected)
      .then((result) => {
        if (!cancelled) {
          setTimeline(result);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setTimeline(null);
          setError(e instanceof Error ? e.message : String(e));
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [selected]);

  if (registry.error) {
    return <p className="error">{registry.error}</p>;
  }

  return (
    <section>
      <h2>Guide</h2>
      <p className="hint">
        The merged timeline for one channel. Each row is served verbatim from the source that won the slot, so
        the source column explains every gap and every clash.
      </p>
      <div className="actions">
        <label>
          Channel{' '}
          <select value={selected ?? ''} onChange={(event) => setSelected(event.target.value)}>
            {channels.map((channel) => (
              <option key={channel.id} value={channel.id}>
                {channel.name} ({channel.id})
              </option>
            ))}
          </select>
        </label>
        {timeline?.materializedAt && (
          <span className="muted">materialized {new Date(timeline.materializedAt).toLocaleString()}</span>
        )}
        {loading && <span className="muted">Loading…</span>}
      </div>
      {error && <p className="error">{error}</p>}
      {channels.length === 0 && <p className="muted">The registry is empty: no channel has been matched yet.</p>}
      {timeline && (
        <>
          <p className="muted">
            {timeline.programmes.length} programme(s); showing the first {Math.min(LIMIT, timeline.programmes.length)}.
          </p>
          <table>
            <thead>
              <tr>
                <th>Start</th>
                <th>Stop</th>
                <th>Title</th>
                <th>Category</th>
                <th>Episode</th>
                <th>Source</th>
              </tr>
            </thead>
            <tbody>
              {timeline.programmes.slice(0, LIMIT).map((programme: Programme, index: number) => (
                <tr key={`${programme.start}-${index}`}>
                  <td>{time(programme.start)}</td>
                  <td>{time(programme.stop)}</td>
                  <td>{programme.title}</td>
                  <td>{programme.category ?? ''}</td>
                  <td>{programme.episode ?? ''}</td>
                  <td className="muted">{programme.source}</td>
                </tr>
              ))}
              {timeline.programmes.length === 0 && (
                <tr>
                  <td colSpan={6} className="muted">
                    This channel has no programmes in the current guide.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}

function time(value: string): string {
  return new Date(value).toLocaleString(undefined, {
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    day: '2-digit',
    month: '2-digit',
  });
}
