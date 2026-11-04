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
