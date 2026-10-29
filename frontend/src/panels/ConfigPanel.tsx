import { useState } from 'react';
import { api } from '../api';
import type { ReseedPreview } from '../types';

/**
 * The escape hatches around a boot-only configuration (ADR-0005): re-read the file, dump what the
 * process is actually holding to the log, and the one deliberate operation that may move channel ids.
 */
export function ConfigPanel() {
  const [notice, setNotice] = useState<{ text: string; tone: 'ok' | 'warn' | 'bad' } | null>(null);
  const [reseed, setReseed] = useState<ReseedPreview | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const run = async (label: string, action: () => Promise<void>) => {
    setBusy(label);
    setNotice(null);
    try {
      await action();
    } catch (e) {
      setNotice({ text: e instanceof Error ? e.message : String(e), tone: 'bad' });
    } finally {
      setBusy(null);
    }
  };

  const reload = () =>
    run('reload', async () => {
      const result = await api.reload();
      const parts = [
        result.added.length ? `added ${result.added.join(', ')}` : null,
        result.removed.length ? `removed ${result.removed.join(', ')}` : null,
        result.orderChanged ? 'order changed' : null,
      ].filter((part): part is string => part !== null);
      setNotice({
        text: parts.length
          ? `Configuration reloaded: ${parts.join('; ')}. Channel ids did not move; a new source serves as a pass-through until a re-seed.`
          : 'Configuration reloaded; nothing changed.',
        tone: 'ok',
      });
    });

  const dump = () =>
    run('dump', async () => {
      await api.dump();
      setNotice({
        text: 'The in-memory configuration, credentials included, was written to the application log.',
        tone: 'ok',
      });
    });

  const preview = () =>
    run('preview', async () => {
      const result = await api.reseedPreview();
      setReseed(result);
      setNotice(
        result.changesAnything
          ? { text: 'A re-seed would change emitted channel ids. Review the diff before applying.', tone: 'warn' }
          : { text: 'A re-seed would not change a single emitted channel id.', tone: 'ok' },
      );
    });

  const apply = () =>
    run('apply', async () => {
      const confirmed = window.confirm(
        'Re-seeding replaces the channel registry. Ids that disappear break any DVR mapping that uses them. Continue?',
      );
      if (!confirmed) {
        return;
      }
      const result = await api.reseed();
      setReseed(result);
      setNotice({
        text: `Registry re-seeded: ${result.proposed.length} channel(s), ${result.added.length} id(s) added, ${result.removed.length} removed.`,
        tone: 'ok',
      });
    });

  return (
    <section>
      <h2>Configuration</h2>
      <p className="hint">
        The YAML file is read at boot. A hand edit takes effect after a reload or a restart, and a reorder made
        before that reload can overwrite it.
      </p>
      {notice && <p className={notice.tone === 'bad' ? 'error' : notice.tone === 'warn' ? 'notice' : 'ok-note'}>{notice.text}</p>}
      <div className="actions">
        <button type="button" onClick={() => void reload()} disabled={busy !== null}>
          {busy === 'reload' ? 'Reloading…' : 'Reload config'}
        </button>
        <button type="button" onClick={() => void dump()} disabled={busy !== null}>
          {busy === 'dump' ? 'Dumping…' : 'Dump config to log'}
        </button>
        <button type="button" onClick={() => void preview()} disabled={busy !== null}>
          {busy === 'preview' ? 'Checking…' : 'Preview re-seed'}
        </button>
        <button type="button" className="danger" onClick={() => void apply()} disabled={busy !== null || !reseed}>
          {busy === 'apply' ? 'Re-seeding…' : 'Re-seed registry'}
        </button>
      </div>

      {reseed && (
        <div className="reseed">
          <h3>Re-seed diff</h3>
          <p>
            <span className="badge ok">{reseed.added.length} added</span>{' '}
            <span className="badge failed">{reseed.removed.length} removed</span>
          </p>
          {reseed.added.length > 0 && <p>Would start emitting: {reseed.added.join(', ')}</p>}
          {reseed.removed.length > 0 && (
            <p className="error">Would stop emitting (breaks DVR mappings): {reseed.removed.join(', ')}</p>
          )}
          <p className="muted">{reseed.proposed.length} channel(s) in the proposed registry.</p>
        </div>
      )}
    </section>
  );
}
