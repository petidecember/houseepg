import { useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import { channelLabel } from '../channelLabel';
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
  dayOf,
  detailLevel,
  geometry,
  lostReason,
  mergeQuery,
  nowLineTop,
  nowMarker,
  openingWindow,
  pickerRange,
  shortestEntry,
  timeOfIso,
  ticks,
  trackHeight,
  windowStart,
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
  const [showNow, setShowNow] = useState(true);
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

  // Every entry the rows are drawing for this window: what decides how tall a shortest block has to be.
  const drawn = useMemo(
    () =>
      (preview?.columns ?? []).flatMap((column) =>
        column.entries.map((entry) => ({ start: entry.start, stop: entry.stop })),
      ),
    [preview],
  );

  // The pickers are bounded by the backend's `available`, which spans the whole guide rather than the
  // window on screen — the window's own entries only ever cover the days already in view (HOU-25).
  const range = useMemo(() => pickerRange(preview?.available, drawn), [preview, drawn]);

  const track = useMemo(() => trackHeight(window, shortestEntry(drawn)), [window, drawn]);

  const shift = (deltaDays: number) => {
    // `toISOString` is UTC and would step to the wrong day west of Greenwich after a local midnight.
    const next = windowStart(window);
    next.setDate(next.getDate() + deltaDays);
    setWindow(bounded({ ...window, day: dayOf(next) }, range));
  };

  const markDraft = dirty ? 'not applied yet' : 'as applied';
  const marker = nowMarker(window, now);

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
              <option key={channel.id} value={channel.id} title={channel.id}>
                {channelLabel(channel)}
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
            min={range ? dayOf(range.first) : undefined}
            max={range ? dayOf(range.last) : undefined}
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
        <label className="merge-span">
          span
          <span className="merge-span-row">
            <input
              type="range"
              min={MIN_HOURS}
              max={MAX_HOURS}
              value={window.hours}
              onChange={(event) =>
                setWindow(bounded({ ...window, hours: Number(event.target.value) }, range))
              }
            />
            <span className="merge-span-value">{window.hours} h</span>
          </span>
        </label>
        <label className="merge-toggle">
          <input type="checkbox" checked={showNow} onChange={(event) => setShowNow(event.target.checked)} />
          now line
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
          className={`merge-grid${showNow ? ' with-now' : ''}`}
          style={{
            ['--hour-step' as string]: `${100 / window.hours}%`,
            ['--track-px' as string]: `${track}px`,
          }}
        >
          <div className="merge-gutter-filler"></div>
          <div className="merge-gutter">
            <div className="merge-track merge-gutter-track">
              {ticks(window).map((tick, index) => (
                <span key={index} className={`tick${tick.half ? ' half' : ''}`} style={{ top: `${tick.top}%` }}>
                  {tick.label}
                </span>
              ))}
            </div>
          </div>
          <div className='merge-columns'>
            {preview.columns.map((column, index) => (
              <MergeColumn
                key={column.source}
                rank={index + 1}
                column={column}
                window={window}
                trackPx={track}
                nameOf={nameOf}
              />
            ))}
            <MergeResult preview={preview} window={window} trackPx={track} nameOf={nameOf} />
            {marker !== null && <span className="now-line" style={{ top: `${nowLineTop(marker, track)}px` }} />}
          </div>
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
  trackPx,
  nameOf,
}: {
  rank: number;
  column: PreviewColumn;
  window: PreviewWindow;
  trackPx: number;
  nameOf: (id: string) => string;
}) {
  return (
    <div className="merge-col">
      <div className="merge-col-head">
        <span className="rank">{rank}</span> {nameOf(column.source)}
      </div>
      <div className="merge-track">
        {column.entries.map((entry, index) => (
          <EntryBlock key={index} entry={entry} window={window} trackPx={trackPx} nameOf={nameOf} />
        ))}
      </div>
      <div className="merge-col-case">{column.caseLabel}</div>
    </div>
  );
}

function EntryBlock({
  entry,
  window,
  trackPx,
  nameOf,
}: {
  entry: PreviewEntry;
  window: PreviewWindow;
  trackPx: number;
  nameOf: (id: string) => string;
}) {
  const box = geometry(entry.start, entry.stop, window);
  if (!box) {
    return null;
  }
  const tone = entry.fate === 'WON' ? 'won' : entry.fate === 'LOST' ? 'lost' : 'excluded';
  // A short programme gets only what fits; the rest would paint over the block below it.
  const detail = detailLevel(box.height, trackPx);
  const verdict =
    entry.fate === 'LOST'
      ? entry.lostTo
        ? lostReason(entry.start, entry.stop, nameOf(entry.lostTo))
        : 'lost every overlap'
      : null;
  return (
    <div
      className={`block ${tone}${detail === 'title' ? ' tight' : ''}`}
      style={{ top: `${box.top}%`, height: `${box.height}%` }}
      title={[entry.title ?? '—', verdict, entry.weaker.length > 0 ? `weaker: ${weaknessText(entry.weaker, nameOf)}` : null]
        .filter(Boolean)
        .join(' · ')}
    >
      <span className="block-title">{entry.title ?? '—'}</span>
      {detail !== 'title' && verdict && <span className="block-why">{verdict}</span>}
      {detail === 'full' && entry.weaker.length > 0 && (
        <span className="block-weak">weaker: {weaknessText(entry.weaker, nameOf)}</span>
      )}
    </div>
  );
}

function MergeResult({
  preview,
  window,
  trackPx,
  nameOf,
}: {
  preview: PreviewPayload;
  window: PreviewWindow;
  trackPx: number;
  nameOf: (id: string) => string;
}) {
  return (
    <div className="merge-col merge-result">
      <div className="merge-col-head">Merge result</div>
      <div className="merge-track">
        {preview.result.entries.map((entry, index) => {
          const box = geometry(entry.start, entry.stop, window);
          if (!box) {
            return null;
          }
          const detail = detailLevel(box.height, trackPx);
          const winner = `✓ ${nameOf(entry.source)}`;
          return (
            <div
              key={index}
              className={`block won${detail === 'title' ? ' tight' : ''}`}
              style={{ top: `${box.top}%`, height: `${box.height}%` }}
              title={[entry.title ?? '—', winner, entry.weaker.length > 0 ? `weaker: ${weaknessText(entry.weaker, nameOf)}` : null]
                .filter(Boolean)
                .join(' · ')}
            >
              <span className="block-title">{entry.title ?? '—'}</span>
              {detail !== 'title' && <span className="block-winner">{winner}</span>}
              {detail === 'full' && entry.weaker.length > 0 && (
                <span className="block-weak">weaker: {weaknessText(entry.weaker, nameOf)}</span>
              )}
            </div>
          );
        })}
      </div>
      <div className="merge-col-case">the feed</div>
    </div>
  );
}
