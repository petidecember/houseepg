import { describe, expect, it } from 'vitest';
import {
  DEFAULT_HOURS,
  MAX_TRACK_PX,
  MIN_BLOCK_PX,
  TRACK_PX,
  shortestEntry,
  trackHeight,
  bounded,
  dataRange,
  dayOf,
  detailLevel,
  geometry,
  lostReason,
  mergeQuery,
  HEAD_PX,
  nowLineTop,
  nowMarker,
  openingWindow,
  pickerRange,
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

  it('bounds the pickers by the whole guide, not by the entries the window drew', () => {
    // The window holds one afternoon; the backend says the channel is published across three days.
    const drawn = [{ start: '2026-11-06T20:00:00+01:00', stop: '2026-11-06T21:00:00+01:00' }];
    const range = pickerRange(
      { first: '2026-11-05T06:00:00+01:00', last: '2026-11-08T05:00:00+01:00' },
      drawn,
    )!;

    expect(dayOf(range.first)).toBe('2026-11-05');
    expect(dayOf(range.last)).toBe('2026-11-08');
    // Every day the guide holds is reachable, which is what made them unselectable before.
    expect(bounded({ day: '2026-11-05', from: '20:00', hours: 5 }, range).day).toBe('2026-11-05');
    expect(bounded({ day: '2026-11-08', from: '20:00', hours: 5 }, range).day).toBe('2026-11-08');
  });

  it('falls back to the drawn entries when the backend reports no published span', () => {
    const drawn = [
      { start: '2026-11-06T20:00:00+01:00', stop: '2026-11-06T21:00:00+01:00' },
      { start: '2026-11-07T20:00:00+01:00', stop: '2026-11-07T21:00:00+01:00' },
    ];

    expect(dayOf(pickerRange({ first: null, last: null }, drawn)!.last)).toBe('2026-11-07');
    expect(pickerRange(undefined, drawn)).toEqual(dataRange(drawn));
    expect(pickerRange(undefined, [])).toBeNull();
  });

  it('lets a start time move to a later slot of the same day but not past the last one', () => {
    // The bug: choosing a time later than the current one then stepping back was refused, because the
    // bound came from the window's own entries rather than the channel's published span.
    const range = pickerRange(
      { first: '2026-11-05T06:00:00+01:00', last: '2026-11-08T05:00:00+01:00' },
      [],
    )!;

    expect(bounded({ day: '2026-11-06', from: '22:00', hours: 5 }, range).from).toBe('22:00');
    expect(bounded({ day: '2026-11-06', from: '02:00', hours: 5 }, range).from).toBe('02:00');
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

describe('the track grows so a busy hour stays readable', () => {
  it('keeps the default height when nothing is unusually short', () => {
    expect(trackHeight(WINDOW, 60)).toBe(TRACK_PX);
    expect(trackHeight(WINDOW, null)).toBe(TRACK_PX);
  });

  it('grows until the shortest entry has room for a line', () => {
    // 15-minute entries in a 5 h window: 20 of them, each needs MIN_BLOCK_PX, so the track is 440 px.
    expect(trackHeight(WINDOW, 15)).toBe(20 * MIN_BLOCK_PX);
  });

  it('stops growing at the ceiling', () => {
    expect(trackHeight(WINDOW, 1)).toBe(MAX_TRACK_PX);
  });

  it('takes the shortest span it is given', () => {
    expect(shortestEntry([])).toBeNull();
    expect(shortestEntry([{ start: '2026-11-05T20:00:00+01:00', stop: '2026-11-05T21:00:00+01:00' }])).toBe(60);
    expect(
      shortestEntry([
        { start: '2026-11-05T20:00:00+01:00', stop: '2026-11-05T21:00:00+01:00' },
        { start: '2026-11-05T21:00:00+01:00', stop: '2026-11-05T21:15:00+01:00' },
      ]),
    ).toBe(15);
    // An unusable span is ignored rather than counted as zero.
    expect(shortestEntry([{ start: 'nonsense', stop: 'nonsense' }])).toBeNull();
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

  it('labels every half hour, marking the halves so they can be drawn dimmer', () => {
    const marks = ticks(WINDOW);

    expect(marks.map((mark) => mark.label)).toEqual([
      '20:00', '20:30', '21:00', '21:30', '22:00', '22:30', '23:00', '23:30', '00:00', '00:30', '01:00',
    ]);
    expect(marks.filter((mark) => mark.half)).toHaveLength(5);
    expect(marks[0].top).toBe(0);
    expect(marks[10].top).toBe(100);
  });

  it('labels the top of the axis with the time the window opens on', () => {
    // The panel opens on the current time, which is almost never a half hour: without its own tick the
    // first label sat 6.7% down and the top of the scale was unreadable.
    const marks = ticks({ day: '2026-11-06', from: '23:40', hours: 5 });

    expect(marks[0]).toEqual({ top: 0, label: '23:40', half: true });
    // 00:00 is twenty minutes into a five-hour window, and is a full hour, so not dimmed.
    expect(marks[1]).toEqual({ top: (20 / 300) * 100, label: '00:00', half: false });
  });

  it('does not repeat a tick when the window opens on the half hour', () => {
    const marks = ticks({ day: '2026-11-06', from: '23:30', hours: 1 });

    expect(marks.map((mark) => mark.label)).toEqual(['23:30', '00:00', '00:30']);
  });

  it('puts the now line where the clock is, and only inside the window', () => {
    expect(nowMarker(WINDOW, new Date('2026-11-05T21:30:00+01:00'))).toBe(30);
    expect(nowMarker(WINDOW, new Date('2026-11-05T19:00:00+01:00'))).toBeNull();
    expect(nowMarker(WINDOW, new Date('2026-11-06T02:00:00+01:00'))).toBeNull();
  });
});

describe('the now line is placed against the track, not the column stack', () => {
  it('offsets the track by the header before applying the marker', () => {
    expect(nowLineTop(0, 1320)).toBe(HEAD_PX);
    expect(nowLineTop(50, 1320)).toBe(HEAD_PX + 660);
    expect(nowLineTop(100, 1320)).toBe(HEAD_PX + 1320);
  });

  it('never lands past the track, which is what the container percentage did', () => {
    const stack = HEAD_PX + 1320 + 30;
    const containerBasis = HEAD_PX + stack;

    expect(nowLineTop(100, 1320)).toBeLessThanOrEqual(HEAD_PX + 1320);
    expect(nowLineTop(100, 1320)).not.toBe(containerBasis);
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

  it('gives a short block its title only, so it cannot paint over its neighbour', () => {
    // A 15-minute programme in a 5-hour window of the 320 px track: 16 px of room.
    expect(detailLevel(5)).toBe('title');
    // A 30-minute one: 32 px, room for the verdict.
    expect(detailLevel(10)).toBe('verdict');
    // An hour: 64 px, room for the weaker callout too.
    expect(detailLevel(20)).toBe('full');
  });

  it('formats a day and a time the way the date and time inputs want them', () => {
    expect(dayOf(new Date(2026, 0, 3, 8, 5))).toBe('2026-01-03');
    expect(timeOf(new Date(2026, 0, 3, 8, 5))).toBe('08:05');
  });
});
