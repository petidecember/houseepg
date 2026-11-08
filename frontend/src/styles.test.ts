import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/** Read from disk rather than importing it: vitest leaves CSS imports stubbed, so `?raw` gives ''. */
const css = readFileSync(resolve('src/styles.css'), 'utf-8');

/**
 * The table's columns are structural: a row must lay out as many cells as the header has columns.
 *
 * `display: flex` on a `<td>` breaks that without touching the markup at all. It takes the cell out of
 * the table's cell layout, so the row's flex boxes collapse into one anonymous cell and every column
 * after it shifts one to the left — the debug text ends up under "Matched to" and the Matched-to
 * control under "Source channel". jsdom has no layout, so a spec that renders the panel cannot see it;
 * the stylesheet is the artefact that was wrong, so this reads the stylesheet instead (HOU-26).
 */

function rules(source: string): { selector: string; body: string }[] {
  const withoutComments = source.replace(/\/\*[\s\S]*?\*\//g, '');
  return [...withoutComments.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((match) => ({
    selector: match[1].replace(/\s+/g, ' ').trim(),
    body: match[2],
  }));
}

describe('the review table keeps its columns', () => {
  it('never overrides the display of a td, because that takes the cell out of the table', () => {
    const overrides = rules(css)
      .filter((rule) => /(^|[\s,])td\b/.test(rule.selector))
      .filter((rule) => /display\s*:/.test(rule.body) && !/display\s*:\s*table-cell/.test(rule.body))
      .map((rule) => rule.selector);

    expect(overrides).toEqual([]);
  });

  it('keeps the layout intent on wrappers inside those cells instead', () => {
    const bySelector = new Map(rules(css).map((rule) => [rule.selector, rule.body]));

    expect(bySelector.get('.decide-inner') ?? '').toMatch(/display:\s*flex/);
    expect(bySelector.get('.source-cell-inner') ?? '').toMatch(/display:\s*flex/);
  });
});

/**
 * The merge preview measures everything from one box. A block's `top`/`height`, a gutter tick and an
 * hour rule are all percentages of the TRACK, so the track has to be a fixed height and the gutter has
 * to have one too — otherwise the same programme lands at a different pixel in each column, and the
 * labels of the time axis drift away from the programmes they name (HOU-25).
 */
describe('the merge preview measures everything from the track', () => {
  /** A selector can be declared more than once; the whole declaration is what the layout depends on. */
  const bodyOf = (selector: string) =>
    rules(css)
      .filter((rule) => rule.selector === selector)
      .map((rule) => rule.body)
      .join('\\n');
  const bySelector = new Map(rules(css).map((rule) => [rule.selector, rule.body]));

  it('gives the track a fixed height rather than a minimum', () => {
    const track = bodyOf('.merge-track');

    expect(track).toMatch(/height:\s*var\(--track-px/);
    expect(track).not.toMatch(/min-height/);
    expect(track).toMatch(/flex:\s*0 0 auto/);
  });

  it('paints the striped sheet inside the track, not across the whole column', () => {
    const track = bodyOf('.merge-track');
    const column = bodyOf('.merge-col');

    expect(track).toMatch(/background-image:/);
    expect(column).not.toMatch(/repeating-linear-gradient/);
  });

  it('lays the gutter out as a head cell above its own track', () => {
    const gutter = bodyOf('.merge-gutter');

    expect(gutter).toMatch(/display:\s*flex/);
    expect(gutter).toMatch(/flex-direction:\s*column/);
    expect(bySelector.get('.merge-gutter-track') ?? '').toMatch(/position:\s*relative/);
  });

  it('keeps the gutter clean by out-ranking the hour-rule rule', () => {
    // `.merge-grid .merge-track` draws the hour rules, and a bare `.merge-gutter .merge-track` sits
    // earlier in the sheet, so the scale came out striped and ruled (HOU-25).
    const gutter = bySelector.get('.merge-grid .merge-gutter .merge-track') ?? '';

    expect(gutter).toMatch(/background-image:\s*none/);
    expect(gutter).toMatch(/background-color:\s*transparent/);
    // Guard the specificity itself: the rule must start with the same .merge-grid prefix.
    expect(bySelector.has('.merge-gutter .merge-track')).toBe(false);
  });

  it('controls the now line with the checkbox, and nothing else', () => {
    // The label is "now line": stripes and hour rules must stay whatever the checkbox says, because
    // hiding those was never what the toggle was read as doing (HOU-25, m03255).
    const hidden = bodyOf('.merge-grid:not(.with-now) .now-line');

    expect(hidden).toMatch(/display:\s*none/);
    expect(css).not.toMatch(/\.with-now\s+\.merge-track/);
    // The hour rules are unconditional on the grid itself, not behind a modifier class.
    expect(bodyOf('.merge-grid .merge-track')).toMatch(/background-image:/);
  });

  it('separates stacked programmes with a hairline', () => {
    // Blocks sit flush in a dense hour; without a separator a run of same-length programmes reads as
    // one undivided band (HOU-25, m03255).
    const after = bySelector.get('.block::after') ?? '';

    expect(after).toMatch(/content:/);
    expect(after).toMatch(/height:\s*1px/);
    expect(after).toMatch(/background:/);
  });

  it('does not draw the hour rules as a pseudo-element of a column', () => {
    const overlays = rules(css)
      .filter((rule) => /::before|::after/.test(rule.selector))
      .filter((rule) => /merge-(col|result)/.test(rule.selector))
      .map((rule) => rule.selector);

    expect(overlays).toEqual([]);
  });
});

describe('the column stack keeps its chrome out of the measured box', () => {
  it('declares the header height the now line assumes', () => {
    // nowLineTop offsets the marker by HEAD_PX. The header's height and the gutter filler's height are
    // written here in the stylesheet, so this pins the number the arithmetic depends on (HOU-25, m03255).
    const bySelector = new Map(rules(css).map((rule) => [rule.selector, rule.body]));

    expect(bySelector.get('.merge-col-head') ?? '').toMatch(/height:\s*32px/);
    expect(bySelector.get('.merge-gutter-filler') ?? '').toMatch(/height:\s*32px/);
  });
});
