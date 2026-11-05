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

/** A tick is drawn every half hour; only the full hours are labelled. */
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

/** Half-hour ticks, in percentages of the height; `label` is set on the full hours only. */
export function ticks(window: PreviewWindow): { top: number; label: string; half: boolean }[] {
  const from = windowStart(window).getTime();
  const to = windowStop(window).getTime();
  if (!(to > from)) {
    return [];
  }
  const first = Math.ceil(from / HALF_HOUR) * HALF_HOUR;
  const marks: { top: number; label: string; half: boolean }[] = [];
  for (let at = first; at <= to; at += HALF_HOUR) {
    const when = new Date(at);
    const half = when.getMinutes() !== 0;
    marks.push({ top: ((at - from) / (to - from)) * 100, label: half ? '' : timeOf(when), half });
  }
  return marks;
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
