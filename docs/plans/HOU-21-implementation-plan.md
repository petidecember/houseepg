# HOU-21 — One "Accept matches…" button: implementation plan

Status: planned (grill complete, no code written yet).
Companion: `docs/plans/HOU-21-test-plan.md`.
Supersedes: the bulk accept HOU-12 shipped ("Accept exact matches…").

## Goal

Replace the per-row grind, and the filter-blind exact-match button that came with it, with one action over
the rows the user is looking at: every visible, checked, valid row whose decision is not yet stored, written
at its current dropdown value, with a count preview before anything is written.

## Settled design

- One button, "Accept matches…", where "Accept exact matches…" sits today.
- **Scope = the rows the user sees**: the current filter (`needs-decision` | `unmatched` | `curated` | `all`)
  plus the search box. Nothing outside the visible list is written.
- A per-row checkbox, checked by default, in a new leading column; unchecking excludes the row.
- **The written value is the row's current dropdown value** (`chosen(row)`), so a row the user re-pointed by
  hand is written at their choice rather than at the matcher's original guess.
- **"Valid" means the user picked a channel, or `candidates[0]` exists.** The third step of `chosen(row)`
  (`byId.has(row.canonicalId) ? row.canonicalId : ''`) is a display fallback and must never make a row
  writable: it can render a channel the user never chose, and a bulk write would confirm it.
- **Row predicate = decision not yet stored** (`!row.curated`). Exact rows are included, so the same button
  also freezes settled exact rows from the All view, where they are on screen.
- Preview first: "Would curate N row(s).", then Apply/Cancel — the pattern the old button already used.
- **"Accept exact matches…" is deleted.** It was global and filter-blind, so on the default view it wrote rows
  that were not on screen. Its freeze job moves to the All view.
- **Recovery is per row** through the Curated filter: no undo, no marker recording whether a decision came
  from an apply or from a hand save. The checkbox and the preview count are the guard before the write.

## Scope boundary

In: the accept endpoint, the application use case that validates a supplied set, the checkbox column and the
button in the panel, and the deletion of the old global bulk accept.

Out (own cards): HOU-22's async fix, deferred behind the measurement task; registry authority UI, duplicate
canonical channel detection, tier-2 fuzzy promotion, guide timetable (HOU-13).

## Shape of the work

One branch `feature/HOU-21-accept-matches`, one commit, squashed into master. HOU-12 needed six slices
because it touched the matcher, the DTOs, a new endpoint and the whole panel; this is one use case plus one
panel change.

## Change

### Application

`BulkAcceptMatches` becomes `AcceptMatches`, with `Result accept(List<Decision> wanted, boolean dryRun)` where
`Decision(source, sourceChannelId, canonicalId)`.

It no longer computes the set — `matchStore.decisions()` was the old button's way of seeing past the filter.
The set is the visible list, so the UI supplies it and the use case validates it:

- drop duplicates on `(source, sourceChannelId)`, last one wins;
- every `canonicalId` must exist in the registry, otherwise 400 naming the offending id;
- only rows with **no stored decision** are written, so a re-apply over an already-curated row is a no-op
  rather than a rewrite;
- one `decideAll(...)` and one `reMerge.rematerialize()` per apply; a dry run writes nothing and re-merges
  nothing.

`ReviewMatches` is unchanged: the row predicate is a property the row already carries (`curated`).

### Interfaces

- `POST /api/matches/accept` with body
  `{ dryRun: boolean, decisions: [{ source, sourceChannelId, canonicalId }] }`, replacing
  `POST /api/matches/bulk-accept?dryRun=`.
- Response shape unchanged: `{ count, rows, dryRun }`.
- An empty `decisions` returns `count: 0` and runs no re-merge.

### Frontend

`ReviewPanel.tsx`:

- a leading checkbox column, one per visible row, checked by default and reset to checked when the list
  reloads; the checkbox is **disabled for a row that is not writable**, so the number of checkboxes can never
  be confused with the number the preview would write;
- the button, wired to the existing preview → Apply/Cancel flow, sends the set
  `visible ∧ checked ∧ writable ∧ !curated`, mapped through `chosen(row)`;
- the writable rule lives in one helper (`writable(row)`), not inline, so the display fallback cannot leak
  into the write set.

`api.ts` gains `accept(decisions, dryRun)`; `bulkAccept(...)` is removed.

## Validation

`./gradlew test`, `./gradlew integrationTest`, `npx tsc --noEmit && npm run build` with `dist` copied into
`src/main/resources/static/`, a test that pins the scope rule, and one live pass over the real feed. The cases
and the live pass's recorded numbers are in `docs/plans/HOU-21-test-plan.md`.

## Follow-ups (already decided, not part of this change)

- **The measurement task (child card)** runs after HOU-21: one bulk apply and one hand-fixed save, timed phase
  by phase inside `refreshAll`.
- **HOU-22 stays open** with a known cause and an unknown cost distribution; its fix shape is chosen from the
  measurement's numbers, and the measurement is linked as a dependency of HOU-22.

## Risks

- The count preview is the only guard before a permanent write, so the checkboxes and the count must tell the
  same story — hence the disabled checkbox on unwritable rows.
- The visible scope is enforced client-side, so a bug there writes more than the user saw. That is exactly
  what the scope test pins.
- Deleting the exact-match button moves the freeze job to the All view. Accepted as a workflow change, not a
  silent loss.
- Recovery is per row, so a systematic mistake in one apply is felt row by row.
