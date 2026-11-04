# HOU-24 … HOU-27 — Review state, its filter, its rows, and the merge waterfall: implementation plan

Status: planned (grill complete, no code written yet).
Companion: `docs/plans/HOU-24-27-test-plan.md`.

## Goal

The review tab does not say what the app already knows, and the priority page does not show what the
order *did*. Four cards, one claim each, settled in one grill:

- **HOU-27** — the match review tab's state lives in the browser, so a reload never loses work.
- **HOU-24** — "Needs a decision" means a row with a value worth choosing between.
- **HOU-26** — a row the matcher could not attach stops looking like an empty cell.
- **HOU-25** — the merge is shown as its steps, per provider, on the priority page.

HOU-24 and HOU-26 are two faces of one missing piece: a review row has no representation for "no
registry channel — this is served under its own id". The filter is one view of it, the column is the
other.

## The finding the design rests on

`chosen()` in `ReviewPanel.tsx` returns `''` exactly when a row has **no candidates** and its
canonical id is **not a registry channel** — i.e. an `UNMATCHED` row, whose canonical id is a
pass-through id that `data.channels` (the registry) never contains. That single condition is HOU-24's
list membership and HOU-26's empty cell.

`ReviewMatches.Filter.NEEDS_DECISION` is `!curated && !isExact(confidence)`, so an `UNMATCHED` row
passes a filter that cannot act on it, and `MatchState.isCurated()` is false for `UNMATCHED`, so no
decision can ever move it out — only the filter can.

## Settled design

### HOU-27 — persistent UI state (first)

Everything on the match review tab persists in `localStorage`: the view controls (`filter`, `fuzzy`,
`debug`, `query`), the per-row picks and the unticked rows. `DEBUG_KEY` is the only thing persisted
today.

- **Key encoding.** One versioned blob under `houseepg.review.v1`, holding the controls plus a map
  keyed by the panel's existing row key `source|sourceChannelId`, with `{ pick?, excluded? }` per
  entry. Reuse the panel's key helper so the encoding has one definition.
- **A save is not a reset.** After a single save or a bulk apply, only the entries for the rows that
  were written are consumed; every other entry stays exactly as the user left it. New rows — never
  seen before — have no entry and start accepted. This replaces today's `useEffect` that clears the
  whole untick list on every `data` change.
- **Validation on load.** An entry is dropped when its row is absent from the payload, or when it
  carries a `pick` that is not a canonical id in the payload. An entry that only carries `excluded`
  survives as long as its row exists. This means no restored pick can name a channel the registry no
  longer has, so a dead pick cannot be re-applied.
- Legacy `houseepg.review.debug`: seed `debug` from it when the blob is absent, then stop writing it.
- The version field is checked, not migrated: an unknown version is ignored and the tab starts fresh.

### HOU-24 — the filter predicate (second)

`NEEDS_DECISION` = not curated **and** not an exact id/name match **and** at least one candidate. The
exact-match exclusion survives; the new third clause is what removes unmatched rows. Server-side, so a
refresh and the API agree.

`Filter.accepts` sees a `ChannelMatch` and `review()` ranks candidates only for rows that survive the
filter, so the third clause must not force ranking for rows the other filters drop. Pass the candidate
check as a lazily evaluated condition, so `ALL`, `CURATED` and `UNMATCHED` never pay for it.

### HOU-26 — the row display (third)

No API change: `ReviewRowDto` already carries `state`, `confidence`, `canonicalId` (the pass-through id
for an unmatched row) and `curated`.

- `chosen(row)` gains an explicit unmatched result instead of `''`; the select renders a first option
  reading `Unmatched — served as <its own id>` for it.
- The registry group stays below, so a human can still correct the row by hand, and `writable(row)` is
  unchanged: an unmatched row with no pick stays non-writable, so the box is disabled and the sentinel
  can never be written.
- The *Matched to* cell therefore never reads as empty: it names the pass-through channel and shows the
  id's placeholder logo where the picked channel's logo would be.

### HOU-25 — the merge waterfall (independent)

A new section on the **Priority page**: providers left to right, one channel from a dropdown, showing
what gets through and what does not. The Guide panel's finished timeline stays as it is.

- **Per provider column:** its programme entries for that channel, each marked won or dropped, with the
  reason for a drop; and, when a dropped entry carried metadata the winner lacks (description, icon,
  category, episode number), that is called out.
- **Every column states its own case** when it contributes nothing: does not carry this channel;
  carries a channel matched to a **different** canonical channel (naming it); carries it but has
  nothing in the current window; or lost every slot to an overlap (naming the winner).
- **The draft order.** The waterfall is computed for the order shown in the panel and updates as
  sources move, labelled "not applied yet"; `Apply order` still changes the live feed. `PriorityMerge`
  is a pure function of (channels, sources, canonicalIds, priority), so a candidate order is a
  parameter — but the slot-claim walk must be **shared** with `PriorityMerge`, not reimplemented, or
  the waterfall can disagree with the feed it explains.
- Nothing new is persisted: the per-source normalized documents (each programme already carrying its
  canonical channel id), `sourceChannels()`, `matches()` and the priority list are enough.

## Scope boundary

In: the four items above — the panel's persisted state and its tests, the filter predicate, the
unmatched row's display, and the waterfall with the backend that feeds it.

Out (own cards or deferred): a persisted "confirmed no match" state (`ChannelMatching.curated()`
discards a decision whose canonical id is not a registry channel, so it needs a second decision kind);
a general merge dry-run endpoint beyond this breakdown; clearing state in the Unmatched view;
component-level DOM tests beyond the three stated areas; persisting the waterfall's own view state
(HOU-27's scope is the match review tab).

## Shape of the work

Four cards, four claims, each one branch with one squashed commit:

1. **HOU-27** — branch `feature/HOU-27-review-state` (first; HOU-24 depends on it).
2. **HOU-24** — branch `feature/HOU-24-needs-decision`.
3. **HOU-26** — branch `feature/HOU-26-unmatched-row`.
4. **HOU-25** — branch `feature/HOU-25-merge-waterfall`, independent of the other three.

The four go first; HOU-23 (measurement) and HOU-22 keep their place behind them with their
dependencies and priorities unchanged. Both plan documents ride the HOU-27 branch.

## Change

### Frontend

- New `frontend/src/reviewState.ts` (HOU-27): the versioned blob, its load/validate/save, and the
  per-row entry helpers. Pure, no React, so it is directly unit-testable.
- `ReviewPanel.tsx`: state initialised from the module; write-through on change; save consumes only the
  rows it wrote; the `excluded` reset effect is removed; `chosen()` gains the unmatched case and the
  select its sentinel option.
- `PriorityPanel.tsx` (HOU-25): the channel dropdown, the waterfall columns and their cases, refetch on
  draft-order change with in-flight requests cancelled, and the "not applied yet" label while dirty;
  `api.ts` gains the breakdown call.

### Application and domain

- `ReviewMatches.Filter` (HOU-24): the third clause, with the candidate check passed lazily.
- New merge-breakdown use case (HOU-25) in `application/guide`, built on the slot-claim walk extracted
  from `PriorityMerge` so the merge and the breakdown cannot drift.

### Interfaces

- `GET /api/merge/{channelId}?order=id1,id2` (HOU-25): the per-provider entries with their fate, the
  metadata-loss callouts, and each column's case. Absent `order` means the applied order, so the
  endpoint is useful without the panel's draft.
- `/api/review`, `/api/matches` and `/api/channels` are unchanged.

### Tooling

- `frontend/package.json` gains `vitest`, `jsdom`, `@testing-library/react` and a `test` script: the
  repo's first frontend tests, landed with HOU-27 and its persistence specs.

## Validation

`./gradlew test`, `./gradlew integrationTest`, `cd frontend && npx tsc --noEmit && npm run build` with
`frontend/dist/*` copied into `src/main/resources/static/`, the new `npx vitest run`, and a live pass
per card. The cases and the live-pass steps are in `docs/plans/HOU-24-27-test-plan.md`; the commands
and what they printed go into each card's `finish` comment.

## Follow-ups (decided, not part of these changes)

- **HOU-23 → HOU-22** keep their place after these four, unchanged.
- **HOU-11** (declared-but-disabled sources) and **HOU-13** (guide timetable) stay in the backlog.

## Risks

- **A restored pick is one click from a bulk apply** (accepted in the grill). The dry-run preview must
  stay in front of every bulk write, and it is the only thing standing between a day-old pick and a
  stored decision.
- **Two tabs share one blob**, last write wins. Not solved here; named so a surprise is not a mystery.
- **HOU-24 changes the default list's size**, so rows appear to vanish from "Needs a decision". The
  Unmatched filter is where they went, and the panel's counts should make that legible.
- **The waterfall must not become a second merge.** If its slot-claim walk is not shared with
  `PriorityMerge`, the view can disagree with the feed it exists to explain.
- **Live draft recomputation** fires a request per source move; cancel in-flight requests and keep the
  payload to one channel.
