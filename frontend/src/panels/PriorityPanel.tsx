import { useEffect, useState } from 'react';
import { api } from '../api';
import { useLoader } from '../useLoader';
import type { PrioritySource } from '../types';

/**
 * The only configuration the UI may change: the source order. Reordering decides which source wins a
 * slot, so it warns about the DVR consequence and says whether the new order was actually saved.
 */
export function PriorityPanel() {
  const { data, error, loading, reload } = useLoader(api.priority);
  const [order, setOrder] = useState<PrioritySource[]>([]);
  const [dirty, setDirty] = useState(false);
  const [notice, setNotice] = useState<{ text: string; tone: 'ok' | 'warn' | 'bad' } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (data) {
      setOrder(data.sources);
      setDirty(false);
    }
  }, [data]);

  const move = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= order.length) {
      return;
    }
    const next = [...order];
    [next[index], next[target]] = [next[target], next[index]];
    setOrder(next);
    setDirty(true);
    setNotice(null);
  };

  const apply = async () => {
    setBusy(true);
    setNotice(null);
    try {
      const result = await api.reorder(order.map((source) => source.id));
      setNotice(
        result.persisted
          ? { text: 'Order applied and saved to the configuration file.', tone: 'ok' }
          : {
              text: `Order applied to the running merge, but not saved: ${result.detail}. A restart would lose it.`,
              tone: 'warn',
            },
      );
      setDirty(false);
      await reload();
    } catch (e) {
      setNotice({ text: e instanceof Error ? e.message : String(e), tone: 'bad' });
    } finally {
      setBusy(false);
    }
  };

  if (loading && !data) {
    return <p className="muted">Loading priority…</p>;
  }
  if (error) {
    return <p className="error">{error}</p>;
  }

  return (
    <section>
      <h2>Priority</h2>
      <p className="hint">
        Highest first: the first source wins a slot when two sources cover it. Reordering never changes the
        channel ids the feed emits — only an explicit re-seed can, and that is under Configuration.
      </p>
      {notice && <p className={notice.tone === 'bad' ? 'error' : notice.tone === 'warn' ? 'notice' : 'ok-note'}>{notice.text}</p>}
      <ol className="priority">
        {order.map((source, index) => (
          <li key={source.id}>
            <span className="rank">{index + 1}</span>
            <span className="grow">
              <strong>{source.name}</strong>
              <span className="muted"> {source.id}</span>
              <span className="muted"> · every {source.refreshInterval}</span>
            </span>
            <button type="button" onClick={() => move(index, -1)} disabled={index === 0 || busy}>
              ↑
            </button>
            <button type="button" onClick={() => move(index, 1)} disabled={index === order.length - 1 || busy}>
              ↓
            </button>
          </li>
        ))}
        {order.length === 0 && <li className="muted">No sources are configured.</li>}
      </ol>
      <div className="actions">
        <button type="button" className="primary" onClick={() => void apply()} disabled={!dirty || busy}>
          {busy ? 'Saving…' : 'Apply order'}
        </button>
        <button
          type="button"
          onClick={() => {
            setOrder(data?.sources ?? []);
            setDirty(false);
            setNotice(null);
          }}
          disabled={!dirty || busy}
        >
          Reset
        </button>
      </div>
    </section>
  );
}
