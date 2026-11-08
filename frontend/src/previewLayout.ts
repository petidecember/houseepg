import type { PreviewWindow } from './types';

/**
 * The geometry of the merge preview (design §6.6). Everything here is a pure function of the window
 * and the times the API returned, so the layout can be tested without a browser: a programme's place
 * in a column is a percentage of the shared window, which is what makes its height its duration.
 */

/** How much of the guide the preview opens on. */
export const DEFAULT_HOURS = 5;

/** The day picker only steps across days, and the span never reaches past a day. */
export const MIN_HOURS = 1;
export const MAX_HOURS = 24;

/** A tick is drawn every half hour; the halves are labelled too, just dimmer. */
const TICK_MINUTES = 30;

const HALF_HOUR = TICK_MINUTES * 60_000;

export function twoDigits(value: number): string {
  return String(value).padStart(2, '0');
}

/** `2026-11-05`: what `<input type="date">` wants, in the viewer's zone. */
export function dayOf(date: Date): string {
  return `${date.getFullYear()}-${twoDigits(date.getMonth() + 1)}-${twoDigits(date.getDate())}`;
}

/** `20:30`: what `<input type="time">` wants, in the viewer's zone. */
export function timeOf(date: Date): string {
  return `${twoDigits(date.getHours())}:${twoDigits(date.getMinutes())}`;
}

/** `20:30` out of an ISO instant, in the viewer's zone; empty when the value is unusable. */
export function timeOfIso(iso: string): string {
  const at = Date.parse(iso);
  return Number.isNaN(at) ? '' : timeOf(new Date(at));
}

/** Where the preview opens: today, at the current time, with the default span. */
export function openingWindow(now: Date): PreviewWindow {
  return { day: dayOf(now), from: timeOf(now), hours: DEFAULT_HOURS };
}

/**
 * The window is a local wall-clock day plus a time, so it is parsed without an offset: `new Date` reads
 * an offset-less date-time as local time, which is the zone the panel is showing.
 */
export function windowStart(window: PreviewWindow): Date {
  return new Date(`${window.day}T${window.from}:00`);
}

export function windowStop(window: PreviewWindow): Date {
  return new Date(windowStart(window).getTime() + window.hours * 3_600_000);
}

/** A block's place in its column, as percentages of the shared height. Null when it misses the window. */
export function geometry(
  startIso: string,
  stopIso: string,
  window: PreviewWindow,
): { top: number; height: number } | null {
  const start = Date.parse(startIso);
  const stop = Date.parse(stopIso);
  const from = windowStart(window).getTime();
  const to = windowStop(window).getTime();
  if (Number.isNaN(start) || Number.isNaN(stop) || !(to > from)) {
    return null;
  }
  const clampedStart = Math.max(start, from);
  const clampedStop = Math.min(stop, to);
  if (!(clampedStop > clampedStart)) {
    return null;
  }
  return {
    top: ((clampedStart - from) / (to - from)) * 100,
    height: ((clampedStop - clampedStart) / (to - from)) * 100,
  };
}

export type Tick = { top: number; label: string; half: boolean };

/**
 * Half-hour ticks, in percentages of the track's height. Every tick is labelled — the mock drew half
 * hours dimmer, not blank — and the window's own start is always a tick, so the top of the axis reads
 * the time the preview begins at even when that is not on a half hour (the panel opens on "now").
 */
export function ticks(window: PreviewWindow): Tick[] {
  const from = windowStart(window).getTime();
  const to = windowStop(window).getTime();
  if (!(to > from)) {
    return [];
  }
  const marks: Tick[] = [{ top: 0, label: timeOf(new Date(from)), half: new Date(from).getMinutes() !== 0 }];
  const first = Math.ceil(from / HALF_HOUR) * HALF_HOUR;
  for (let at = first === from ? from + HALF_HOUR : first; at <= to; at += HALF_HOUR) {
    const when = new Date(at);
    const half = when.getMinutes() !== 0;
    marks.push({ top: ((at - from) / (to - from)) * 100, label: timeOf(when), half });
  }
  return marks;
}

/** The track is a fixed pixel height, so a percentage can be turned back into the room a block has. */
export const TRACK_PX = 320;
/** The floor a block is allowed to reach: enough for one readable line plus its separator. */
export const MIN_BLOCK_PX = 22;

/** A track only grows to keep the shortest block readable, and never past this. */
export const MAX_TRACK_PX = 1400;

/**
 * How tall the track has to be for this window. A dense hour would squeeze a 15-minute programme into
 * 16 px and its title would be unreadable, so the track grows until the shortest entry has
 * MIN_BLOCK_PX to itself — which is why the page scrolls on a busy window instead of squashing it.
 */
export function trackHeight(window: PreviewWindow, shortestMinutes: number | null): number {
  if (shortestMinutes === null || !(shortestMinutes > 0) || !(window.hours > 0)) {
    return TRACK_PX;
  }
  const pixelsPerMinute = MIN_BLOCK_PX / shortestMinutes;
  return Math.round(Math.min(MAX_TRACK_PX, Math.max(TRACK_PX, pixelsPerMinute * window.hours * 60)));
}

/** The shortest entry a drawing column holds, in minutes, or null when nothing is drawn. */
export function shortestEntry(spans: { start: string; stop: string }[]): number | null {
  let shortest: number | null = null;
  for (const span of spans) {
    const start = Date.parse(span.start);
    const stop = Date.parse(span.stop);
    if (Number.isNaN(start) || Number.isNaN(stop) || !(stop > start)) {
      continue;
    }
    const minutes = (stop - start) / 60_000;
    shortest = shortest === null ? minutes : Math.min(shortest, minutes);
  }
  return shortest;
}

/** What fits in a block. A 15-minute programme is 16 px tall and cannot hold three lines. */
export type Detail = 'title' | 'verdict' | 'full';

/**
 * How much of a block to draw, from its height. The design asks for the verdict inline, but a block
 * that is too short would paint its text over the next one — so the extra lines are dropped rather
 * than squeezed (HOU-25: three 15-minute 9.Tv programmes stacked their titles over each other).
 */
export function detailLevel(heightPercent: number, trackPx = TRACK_PX): Detail {
  const room = (heightPercent / 100) * trackPx;
  if (room >= 42) {
    return 'full';
  }
  return room >= 26 ? 'verdict' : 'title';
}

/** Where the `now` line falls, or null when it falls outside the window. */
export function nowMarker(window: PreviewWindow, now: Date): number | null {
  const from = windowStart(window).getTime();
  const to = windowStop(window).getTime();
  const at = now.getTime();
  if (!(to > from) || at < from || at > to) {
    return null;
  }
  return ((at - from) / (to - from)) * 100;
}

/**
 * The column header's height, which `.merge-col-head` and `.merge-gutter-filler` both set in the
 * stylesheet. Everything that has to clear the header uses this one number.
 */
export const HEAD_PX = 32;
/**
 * Where the `now` line sits, in pixels from the top of the column stack.
 *
 * The line is a child of `.merge-columns`, which is 32 px of header and 30 px of case label taller than
 * the track itself. A percentage there is a percentage of the WRONG box: it drifts by the case label's
 * height, so near the end of the window the line lands below the last programme, over the footer
 * (HOU-25, m03255). `marker` is a percentage of the TRACK, so it has to be applied to the track's own
 * height and then offset past the header.
 *
 * HEAD_PX is `.merge-col-head`'s and `.merge-gutter-filler`'s height; all three must change together.
 */
export function nowLineTop(marker: number, trackPx: number): number {
  return HEAD_PX + (marker / 100) * trackPx;
}

/**
 * What the day and start pickers may reach: the channel's own published span as the backend reports it,
 * falling back to the window's entries only when the backend did not answer with one. The fallback
 * matters because a window-clipped entry list can only ever cover the days already on screen, which is
 * what made every day but today unselectable (HOU-25, m03255).
 */
export function pickerRange(
  available: { first: string | null; last: string | null } | null | undefined,
  drawn: { start: string; stop: string }[],
): DataRange | null {
  const first = available?.first;
  const last = available?.last;
  if (first && last) {
    return { first: new Date(first), last: new Date(last) };
  }
  return dataRange(drawn);
}

/** The spans a channel actually has, which is what bounds the day and start pickers. */
export type DataRange = { first: Date; last: Date };

export function dataRange(spans: { start: string; stop: string }[]): DataRange | null {
  let first: number | null = null;
  let last: number | null = null;
  for (const span of spans) {
    const start = Date.parse(span.start);
    const stop = Date.parse(span.stop);
    if (Number.isNaN(start) || Number.isNaN(stop)) {
      continue;
    }
    first = first === null ? start : Math.min(first, start);
    last = last === null ? stop : Math.max(last, stop);
  }
  return first === null || last === null ? null : { first: new Date(first), last: new Date(last) };
}

/**
 * Keeps a chosen window inside the days the channel has data for. Only the start moves: a window that
 * runs past the last programme is a real state of the feed and the stripes say so.
 */
export function bounded(window: PreviewWindow, range: DataRange | null): PreviewWindow {
  const hours = Math.min(MAX_HOURS, Math.max(MIN_HOURS, Math.round(window.hours)));
  if (!range) {
    return { ...window, hours };
  }
  const firstDay = dayOf(range.first);
  const lastDay = dayOf(range.last);
  const day = window.day < firstDay ? firstDay : window.day > lastDay ? lastDay : window.day;
  let from = window.from;
  if (day === firstDay && from < timeOf(range.first)) {
    from = timeOf(range.first);
  }
  if (day === lastDay && from > timeOf(range.last)) {
    from = timeOf(range.last);
  }
  return { day, from, hours };
}

/** The call the panel makes: the window plus the draft order being previewed. */
export function mergeQuery(window: PreviewWindow, order: string[]): string {
  const params = new URLSearchParams({
    day: window.day,
    from: window.from,
    hours: String(window.hours),
  });
  order.forEach((id) => params.append('order', id));
  return params.toString();
}

/** `lost 20:00–21:00 — Alpha TV claimed it first`, the only verdict text the preview shows. */
export function lostReason(startIso: string, stopIso: string, lostToName: string): string {
  return `lost ${timeOfIso(startIso)}–${timeOfIso(stopIso)} — ${lostToName} claimed it first`;
}

/** `description — Beta Télé had one`, repeated for every field the displaced entry had. */
export function weaknessText(weaker: { field: string; from: string }[], nameOf: (id: string) => string): string {
  return weaker.map((weak) => `${weak.field} — ${nameOf(weak.from)} had one`).join('; ');
}
