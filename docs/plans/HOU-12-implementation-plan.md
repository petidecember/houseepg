# HOU-12 — Match review UX: implementation plan

Status: planned (grill complete, no code written yet).
Companion: `docs/plans/HOU-12-test-plan.md`.

## Goal

Make the match review usable at 100+ rows: one decision action per row, a dropdown that opens on the
best candidate and is grouped "best matches" then "all channels", a row-level logo comparison, and a
bulk accept that clears the rows where the answer is not in doubt.

## Scope boundary

In: ranked candidates, DTO widening, review endpoint, bulk accept, review UI, debug toggle, measurement.

Out (own cards): registry authority UI (rename/merge/split/pin/hide), duplicate canonical channel
detection, tier-2 fuzzy promotion, guide timetable (HOU-13).

## Slices

Each slice is independently verifiable and leaves the build green. Order matters: 1 → 2 → 3 → 4 → 5 → 6.

### Slice 1 — Domain: ranked candidates

**Files**
- `src/main/java/com/anti/houseepg/domain/guide/policy/ChannelMatching.java`
- `src/main/java/com/anti/houseepg/domain/guide/policy/ChannelKey.java` (only if the scorer needs a
  token view; the existing normalized key is reused as-is)
- `src/test/java/com/anti/houseepg/domain/guide/policy/ChannelMatchingCandidatesTest.java` (new)

**Change**
- Add `List<Candidate> candidates(SourceChannel sourceChannel, boolean fuzzy)` to `ChannelMatching`,
  where `Candidate(Channel channel, MatchConfidence confidence, double score)`.
- Ladder hits first, in `MatchConfidence` order (`EXACT_ID` 1.0 → `EXACT_NAME` 0.9 → `NORMALIZED_ID`
  0.8 → `NORMALIZED_NAME` 0.6). A key can cover several channels, so each rung contributes a list;
  within a rung order by quality-tag agreement (reuse the `pick(...)` preference), then registry order.
- With `fuzzy == true`, fill the remaining slots up to 3 from registry channels not already listed,
  scored by a documented name-similarity function over `ChannelKey`-normalized names (token overlap
  first, then normalized edit distance), keeping only scores at or above a named constant.
- With `fuzzy == false` the method returns exactly the ladder hits, capped at 3. No new scoring rule
  runs, so the disabled path is the old behaviour.
- `resolve(...)` keeps its current signature and behaviour; it is not reimplemented in terms of
  `candidates(...)` in this slice (that would change the automatic matcher, which is out of scope).

**Acceptance**
- `candidates(...)` returns the ladder hit first for every confidence level.
- Fuzzy on: an unmatched channel gets up to 3 scored suggestions; fuzzy off: it gets none.
- The list never exceeds 3 and never contains a duplicate channel.
- The automatic matcher's output is byte-identical before and after (existing tests unchanged).

### Slice 2 — DTO widening

**Files**
- `src/main/java/com/anti/houseepg/interfaces/web/InspectionController.java`
- `src/main/java/com/anti/houseepg/application/guide/InspectGuide.java`
- `frontend/src/types.ts`

**Change**
- `ChannelDto` gains `iconUrl` (from `Channel.iconUrl()`).
- `MatchDto` gains `sourceChannelName` and `sourceIconUrl`, resolved from the source EPG channel that
  produced the match. `InspectGuide.Registry` grows a source-channel view so the controller does not
  reach into the store.
- `frontend/src/types.ts` mirrors both shapes, so a mismatch is a compile error.

**Acceptance**
- `GET /api/channels` carries an icon for channels that have one and `null` for those that do not.
- A match row carries the source channel's display name and icon.
- `npx tsc --noEmit` passes.

### Slice 3 — Review endpoint

**Files**
- `src/main/java/com/anti/houseepg/application/guide/ReviewMatches.java` (new)
- `src/main/java/com/anti/houseepg/interfaces/web/InspectionController.java`
- `src/test/java/com/anti/houseepg/application/guide/ReviewMatchesTest.java` (new)

**Change**
- `GET /api/review?fuzzy=true|false&filter=needs-decision|all|curated|unmatched`, default
  `fuzzy=false`, `filter=needs-decision`.
- Each row: source channel (id, name, icon), current match (canonical id, state, curated, confidence),
  and `candidates` (the ranked list from slice 1, capped at 3) plus the full channel list for the
  "all channels" group.
- `needs-decision` = not curated **and** confidence is not `EXACT_ID`/`EXACT_NAME`.
- Log one line per build: `review: N rows, M would be auto-resolved by fuzzy, K ambiguous` — the
  measurement decision 5 depends on. `M` counts rows where the top fuzzy candidate is unique and above
  the threshold; `K` counts rows where two candidates are within the margin.

**Acceptance**
- Default filter hides curated rows and exact id/name matches.
- `fuzzy=true` adds suggestions; `fuzzy=false` returns ladder hits only.
- The counter line appears once per review build and its numbers match the rows returned.

### Slice 4 — Bulk accept

**Files**
- `src/main/java/com/anti/houseepg/application/guide/BulkAcceptMatches.java` (new)
- `src/main/java/com/anti/houseepg/domain/guide/policy/MatchStore.java` (add a batch write)
- `src/main/java/com/anti/houseepg/interfaces/web/InspectionController.java`
- `src/test/java/com/anti/houseepg/application/guide/BulkAcceptMatchesTest.java` (new)

**Change**
- `POST /api/matches/bulk-accept?dryRun=true|false` → `{count, rows:[{source, sourceChannelId,
  canonicalId}]}`.
- The service computes the exact-match set itself (confidence `EXACT_ID`/`EXACT_NAME`, not curated),
  so the definition of "exact" stays in the domain and the preview is the same computation as the apply.
- Apply writes every decision through one batch store call and re-merges **once**.
- A second apply returns `count: 0` (idempotent).

**Acceptance**
- Dry-run count equals the number of rows the apply writes.
- Exactly one re-merge per apply, verified with a counting fake.
- Curated rows survive a later automatic pass.

### Slice 5 — Review UI

**Files**
- `frontend/src/panels/ReviewPanel.tsx`
- `frontend/src/api.ts`, `frontend/src/types.ts`, `frontend/src/styles.css`

**Change**
- **One action per row.** The button reads "Save decision"; the request no longer carries
  `decision: "corrected"`. The server derives the state: target equals the automatic resolution →
  `CONFIRMED`, otherwise `CORRECTED`.
- **Dropdown** opens on the best candidate (never on a pass-through id), grouped with two
  `<optgroup>`s: `Best matches` (top 3) and `All channels`. Option label = name + quality tag, no
  channel number.
- **Row layout**: source channel logo + name on the left, dropdown in the middle, the selected
  channel's logo on the right, so the comparison is visible without opening the list. A missing icon
  renders a neutral placeholder, not a broken image.
- **Fuzzy toggle** in the panel header, sent as `?fuzzy=`.
- **Bulk accept** button: dry run first, shows "would curate N rows", then applies and refreshes.
- **Debug toggle** persisted in `localStorage`, off by default; when on, rows show confidence, score
  and the reason string. Nothing else in the UI shows statistics.

**Acceptance**
- No row can send a value that is not a registry channel (the 400-on-default bug is gone).
- The dropdown's first option is the best candidate for every row.
- Bulk accept shows a count before it writes, and the list shrinks afterwards.
- With the debug toggle off, no confidence or score text is rendered anywhere.

### Slice 6 — Measured live pass

**Files**: none (evidence goes into the commit message and the board card).

**Change**
- Start Edge, attach Surf, open the review panel against the real feed.
- Record: rows before, rows after bulk accept, rows left needing a decision, and the fuzzy counter
  line from the log.

**Acceptance**
- The numbers are in the commit message, and the fuzzy counter is quoted verbatim.

## Risks

- The fuzzy scorer is a new rule; it must stay inside `candidates(...)` and never reach `resolve(...)`.
- Bulk accept writes many curated rows at once; the dry run must be the same code path as the apply.
- `Channel.iconUrl` is often null, so the placeholder is part of the acceptance, not a detail.
- The debug toggle must not become a second way to change matching behaviour: it only changes what is
  rendered.
