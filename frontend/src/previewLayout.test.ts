import { describe, expect, it } from 'vitest';
import {
  DEFAULT_HOURS,
  bounded,
  dataRange,
  dayOf,
  geometry,
  lostReason,
  mergeQuery,
  nowMarker,
  openingWindow,
  ticks,
  timeOf,
  weaknessText,
  windowStart,
  windowStop,
} from './previewLayout';

/** A fixed window so the percentages below are arithmetic, not wall-clock. */
const WINDOW = { day: '2026-11-05', from: '20:00', hours: 5 };

describe('the preview window', () => {
  it('opens on today, at the current time, with the default span', () => {
    const at = new Date(2026, 10, 5, 21, 37);

    expect(openingWindow(at)).toEqual({ day: '2026-11-05', from: '21:37', hours: DEFAULT_HOURS });
  });

  it('is a local wall-clock window, so its stop is the span later', () => {
    expect(timeOf(windowStart(WINDOW))).toBe('20:00');
    expect(timeOf(windowStop(WINDOW))).toBe('01:00');
    expect(windowStop(WINDOW).getTime() - windowStart(WINDOW).getTime()).toBe(5 * 3_600_000);
  });

  it('keeps the day inside the days the channel has data for', () => {
    const range = dataRange([
      { start: '2026-11-05T20:00:00+01:00', stop: '2026-11-05T22:00:00+01:00' },
      { start: '2026-11-07T20:00:00+01:00', stop: '2026-11-07T23:00:00+01:00' },
    ]);

    expect(dayOf(range!.first)).toBe('2026-11-05');
    expect(dayOf(range!.last)).toBe('2026-11-07');
    expect(bounded({ day: '2026-11-01', from: '20:00', hours: 5 }, range).day).toBe('2026-11-05');
    expect(bounded({ day: '2026-12-01', from: '20:00', hours: 5 }, range).day).toBe('2026-11-07');
    // A start before the first programme on the first day is pulled up to it; nothing else moves.
    expect(bounded({ day: '2026-11-05', from: '06:00', hours: 5 }, range).from).toBe('20:00');
    expect(bounded({ day: '2026-11-07', from: '23:30', hours: 5 }, range).from).toBe('23:00');
  });

  it('clamps the span to a sane number of hours even without any range', () => {
    expect(bounded({ day: '2026-11-05', from: '20:00', hours: 0 }, null).hours).toBe(1);
    expect(bounded({ day: '2026-11-05', from: '20:00', hours: 99 }, null).hours).toBe(24);
  });

  it('leaves a window alone when nothing is out of bounds', () => {
    const range = dataRange([{ start: '2026-11-05T20:00:00+01:00', stop: '2026-11-05T22:00:00+01:00' }]);

    expect(bounded(WINDOW, range)).toEqual(WINDOW);
  });
});

describe('the geometry of a column', () => {
  it('makes a programme’s height its duration, measured against the whole window', () => {
    // 20:00-21:00 is the first hour of a five-hour window: one fifth from the top.
    const box = geometry('2026-11-05T20:00:00+01:00', '2026-11-05T21:00:00+01:00', WINDOW);

    expect(box).toEqual({ top: 0, height: 20 });
  });

  it('clips an entry that runs past the window to the window itself', () => {
    const box = geometry('2026-11-05T23:00:00+01:00', '2026-11-06T03:00:00+01:00', WINDOW);

    expect(box).toEqual({ top: 60, height: 40 });
  });

  it('drops an entry that misses the window entirely', () => {
    expect(geometry('2026-11-05T10:00:00+01:00', '2026-11-05T11:00:00+01:00', WINDOW)).toBeNull();
    expect(geometry('2026-11-06T10:00:00+01:00', '2026-11-06T11:00:00+01:00', WINDOW)).toBeNull();
    expect(geometry('nonsense', 'nonsense', WINDOW)).toBeNull();
  });

  it('marks the half hours, labelling only the full ones', () => {
    const marks = ticks(WINDOW);

    expect(marks.map((mark) => mark.label)).toEqual(['20:00', '', '21:00', '', '22:00', '', '23:00', '', '00:00', '', '01:00']);
    expect(marks.filter((mark) => mark.half)).toHaveLength(5);
    expect(marks[0].top).toBe(0);
    expect(marks[10].top).toBe(100);
  });

  it('puts the now line where the clock is, and only inside the window', () => {
    expect(nowMarker(WINDOW, new Date('2026-11-05T21:30:00+01:00'))).toBe(30);
    expect(nowMarker(WINDOW, new Date('2026-11-05T19:00:00+01:00'))).toBeNull();
    expect(nowMarker(WINDOW, new Date('2026-11-06T02:00:00+01:00'))).toBeNull();
  });
});

describe('the call and the verdict text', () => {
  it('sends the window and the draft order, so the preview never needs its own endpoint', () => {
    const query = mergeQuery(WINDOW, ['b', 'a']);

    expect(query).toContain('day=2026-11-05');
    expect(query).toContain('from=20%3A00');
    expect(query).toContain('hours=5');
    expect(query.match(/order=/g)).toHaveLength(2);
    expect(new URLSearchParams(query).getAll('order')).toEqual(['b', 'a']);
  });

  it('states a loss with the window it lost and who took it', () => {
    expect(lostReason('2026-11-05T20:00:00+01:00', '2026-11-05T21:00:00+01:00', 'Alpha TV')).toBe(
      'lost 20:00–21:00 — Alpha TV claimed it first',
    );
  });

  it('names the provider behind each field the merge cost the winner', () => {
    expect(weaknessText([{ field: 'description', from: 'b' }], (id) => (id === 'b' ? 'Beta Télé' : id))).toBe(
      'description — Beta Télé had one',
    );
    expect(weaknessText([], (id) => id)).toBe('');
  });

  it('formats a day and a time the way the date and time inputs want them', () => {
    expect(dayOf(new Date(2026, 0, 3, 8, 5))).toBe('2026-01-03');
    expect(timeOf(new Date(2026, 0, 3, 8, 5))).toBe('08:05');
  });
});
