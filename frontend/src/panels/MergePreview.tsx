import { useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import type {
  Channel,
  MergePreview as PreviewPayload,
  PreviewColumn,
  PreviewEntry,
  PreviewWindow,
  PrioritySource,
} from '../types';
import {
  MAX_HOURS,
  MIN_HOURS,
  bounded,
  dataRange,
  geometry,
  lostReason,
  mergeQuery,
  nowMarker,
  openingWindow,
  timeOfIso,
  ticks,
  weaknessText,
  windowStop,
} from '../previewLayout';

/**
 * The merge preview (design §6.6): one channel, the draft order the panel is holding, and the feed that
 * order would produce. It never changes anything — Apply order is still the only control that does.
 */
export function MergePreview({
  order,
  dirty,
  channels,
}: {
  order: PrioritySource[];
  dirty: boolean;
  channels: Channel[];
}) {
  const [selected, setSelected] = useState<string | null>(null);
  const [window, setWindow] = useState<PreviewWindow>(() => openingWindow(new Date()));
  const [guideLines, setGuideLines] = useState(true);
  const [preview, setPreview] = useState<PreviewPayload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    if (selected === null && channels.length > 0) {
      setSelected(channels[0].id);
    }
  }, [channels, selected]);

  // The `now` line is a fact about the wall clock, so it is only as fresh as the last look.
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(timer);
  }, []);

  const query = useMemo(() => mergeQuery(window, order.map((source) => source.id)), [window, order]);

  useEffect(() => {
    if (selected === null) {
      return;
    }
    let live = true;
    setLoading(true);
    setError(null);
    api
      .merge(selected, query)
      .then((result) => {
        if (live) {
          setPreview(result);
        }
      })
      .catch((e: unknown) => {
        if (live) {
          setPreview(null);
          setError(e instanceof Error ? e.message : String(e));
        }
      })
      .finally(() => {
        if (live) {
          setLoading(false);
        }
      });
    return () => {
      live = false;
    };
  }, [selected, query]);

  const nameOf = (id: string) => order.find((source) => source.id === id)?.name ?? id;

  // Everything the channel has in the guide, so the pickers cannot step outside its own data.
  const range = useMemo(() => {
    const spans = (preview?.columns ?? []).flatMap((column) =>
      column.entries.map((entry) => ({ start: entry.start, stop: entry.stop })),
    );
    return dataRange(spans);
  }, [preview]);

  const shift = (deltaDays: number) => {
    const next = new Date(`${window.day}T${window.from}:00`);
    next.setDate(next.getDate() + deltaDays);
    setWindow(bounded({ ...window, day: next.toISOString().slice(0, 10) }, range));
  };

  const markDraft = dirty ? 'not applied yet' : 'as applied';

  return (
    <section className="merge-preview">
      <h3>Merge preview {dirty && <span className="badge stale">{markDraft}</span>}</h3>
      <p className="hint">
        One channel, the order above, and what the feed would serve. Stripes mean nothing is published for
        that time; colour is the verdict. Nothing changes until <strong>Apply order</strong>.
      </p>

      <div className="merge-controls">
        <label>
          channel
          <select value={selected ?? ''} onChange={(event) => setSelected(event.target.value)}>
            {channels.map((channel) => (
              <option key={channel.id} value={channel.id}>
                {channel.name} ({channel.id})
              </option>
            ))}
          </select>
        </label>
        <button type="button" onClick={() => shift(-1)} disabled={!range}>
          ‹
        </button>
        <label>
          day
          <input
            type="date"
            value={window.day}
            min={range ? range.first.toISOString().slice(0, 10) : undefined}
            max={range ? range.last.toISOString().slice(0, 10) : undefined}
            onChange={(event) => setWindow(bounded({ ...window, day: event.target.value }, range))}
          />
        </label>
        <button type="button" onClick={() => shift(1)} disabled={!range}>
          ›
        </button>
        <label>
          from
          <input
            type="time"
            value={window.from}
            onChange={(event) => setWindow(bounded({ ...window, from: event.target.value }, range))}
          />
        </label>
        <label>
          span
          <input
            type="number"
            min={MIN_HOURS}
            max={MAX_HOURS}
            value={window.hours}
            onChange={(event) =>
              setWindow(bounded({ ...window, hours: Number(event.target.value) }, range))
            }
          />
          h
        </label>
        <label className="merge-toggle">
          <input type="checkbox" checked={guideLines} onChange={(event) => setGuideLines(event.target.checked)} />
          guide lines
        </label>
        <span className="muted">
          {window.from} → {timeOfIso(windowStop(window).toISOString())}
        </span>
        {loading && <span className="muted">loading…</span>}
      </div>

      {error && <p className="error">{error}</p>}
      {!error && channels.length === 0 && <p className="muted">The registry is empty: no channel to preview.</p>}

      {preview && (
        <div
          className={`merge-grid${guideLines ? ' with-rules' : ''}`}
          style={{ ['--hour-step' as string]: `${100 / window.hours}%` }}
        >
          <div className="merge-gutter">
            {ticks(window).map((tick, index) => (
              <span key={index} className={`tick${tick.half ? ' half' : ''}`} style={{ top: `${tick.top}%` }}>
                {tick.label}
              </span>
            ))}
          </div>
          {preview.columns.map((column, index) => (
            <MergeColumn
              key={column.source}
              rank={index + 1}
              column={column}
              window={window}
              now={now}
              nameOf={nameOf}
            />
          ))}
          <MergeResult preview={preview} window={window} now={now} nameOf={nameOf} />
        </div>
      )}

      {preview && (
        <p className="hint merge-footer">
          {preview.result.window.coverage.length} span(s) covered · {preview.result.window.gaps.length} gap(s) ·
          nothing changes until Apply order
        </p>
      )}
    </section>
  );
}

function MergeColumn({
  rank,
  column,
  window,
  now,
  nameOf,
}: {
  rank: number;
  column: PreviewColumn;
  window: PreviewWindow;
  now: Date;
  nameOf: (id: string) => string;
}) {
  const marker = nowMarker(window, now);
  return (
    <div className="merge-col">
      <div className="merge-col-head">
        <span className="rank">{rank}</span> {nameOf(column.source)}
      </div>
      <div className="merge-track">
        {column.entries.map((entry, index) => (
          <EntryBlock key={index} entry={entry} window={window} nameOf={nameOf} />
        ))}
        {marker !== null && <span className="now-line" style={{ top: `${marker}%` }} />}
      </div>
      <div className="merge-col-case">{column.caseLabel}</div>
    </div>
  );
}

function EntryBlock({
  entry,
  window,
  nameOf,
}: {
  entry: PreviewEntry;
  window: PreviewWindow;
  nameOf: (id: string) => string;
}) {
  const box = geometry(entry.start, entry.stop, window);
  if (!box) {
    return null;
  }
  const tone = entry.fate === 'WON' ? 'won' : entry.fate === 'LOST' ? 'lost' : 'excluded';
  return (
    <div className={`block ${tone}`} style={{ top: `${box.top}%`, height: `${box.height}%` }}>
      <span className="block-title">{entry.title ?? '—'}</span>
      {entry.fate === 'LOST' && entry.lostTo && (
        <span className="block-why">{lostReason(entry.start, entry.stop, nameOf(entry.lostTo))}</span>
      )}
      {entry.fate === 'LOST' && !entry.lostTo && <span className="block-why">lost every overlap</span>}
      {entry.weaker.length > 0 && (
        <span className="block-weak">weaker: {weaknessText(entry.weaker, nameOf)}</span>
      )}
    </div>
  );
}

function MergeResult({
  preview,
  window,
  now,
  nameOf,
}: {
  preview: PreviewPayload;
  window: PreviewWindow;
  now: Date;
  nameOf: (id: string) => string;
}) {
  const marker = nowMarker(window, now);
  return (
    <div className="merge-col merge-result">
      <div className="merge-col-head">Merge result</div>
      <div className="merge-track">
        {preview.result.entries.map((entry, index) => {
          const box = geometry(entry.start, entry.stop, window);
          if (!box) {
            return null;
          }
          return (
            <div key={index} className="block won" style={{ top: `${box.top}%`, height: `${box.height}%` }}>
              <span className="block-title">{entry.title ?? '—'}</span>
              <span className="block-winner">✓ {nameOf(entry.source)}</span>
              {entry.weaker.length > 0 && (
                <span className="block-weak">weaker: {weaknessText(entry.weaker, nameOf)}</span>
              )}
            </div>
          );
        })}
        {marker !== null && <span className="now-line" style={{ top: `${marker}%` }} />}
      </div>
      <div className="merge-col-case">the feed</div>
    </div>
  );
}
