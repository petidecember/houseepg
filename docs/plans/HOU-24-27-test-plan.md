# HOU-24 … HOU-27 — Review state, its filter, its rows, and the merge waterfall: test plan

Companion: `docs/plans/HOU-24-27-implementation-plan.md`.

## Acceptance criteria

### HOU-27 — persistent UI state

1. The view controls (`filter`, `fuzzy`, `debug`, `query`) survive a page reload.
2. Per-row picks and unticked rows survive a page reload.
3. A save consumes only the rows it wrote: every untouched row stays exactly as the user left it.
4. Rows never seen before start accepted.
5. An entry whose row is absent from the payload is dropped on load; so is an entry whose `pick` names
   a channel the payload does not have. A dropped entry cannot be re-applied.
6. Every bulk write is still preceded by the dry-run preview.
7. The persistence module and the key encoding have unit tests, and the vitest setup lands here.

### HOU-24 — the filter predicate

1. `GET /api/review?filter=needs-decision` returns no row with zero candidates.
2. An exact id or name match is still excluded from that filter.
3. A non-curated row with at least one candidate is still included.
4. An unmatched row appears only under `filter=unmatched`.
5. `ReviewMatchesTest` covers all three clauses of the predicate.

### HOU-26 — the row display

1. Under the Unmatched filter, a row the matcher could not attach shows
   `Unmatched — served as <its own id>` in the *Matched to* column — never an empty cell and never a
   bare "Choose a channel…".
2. Picking a registry channel and saving still works and moves the row out of Unmatched.
3. Such a row is not writable until a channel is picked, so the sentinel can never be stored by a bulk
   apply.
4. The row rules (`chosen`, `writable`, the accepted set) have unit tests.

### HOU-25 — the merge waterfall

1. The Priority page renders one column per provider for the chosen channel.
2. Each provider's entries are marked won or dropped; a dropped entry that carried metadata the
   winner lacks is called out.
3. A column that contributes nothing states its own case: not carried; matched to a different
   canonical channel (named); nothing in the window; lost every overlap (winner named).
4. Moving a source updates the waterfall immediately and the view is labelled "not applied yet".
5. The live feed changes only on `Apply order`.
6. The Guide panel is unchanged.
7. The breakdown use case has a JUnit test, including one that pins it against `PriorityMerge`.

## Automated cases

### Application — `ReviewMatchesTest` (HOU-24)

- A non-curated `UNMATCHED` row with no candidates is **not** in `needs-decision`.
- A non-curated row with candidates is in `needs-decision`, whatever its state.
- An exact id match and an exact name match stay out.
- A curated row stays out.
- `unmatched` lists the unmatch; `all` lists it too.
- The ranking work is not done for rows a filter drops (a counting ranker, or a spy on the candidate
  call).

### Application — merge breakdown (HOU-25)

- For a fixed pair of sources with an overlap, the earlier source wins and the later entry is reported
  dropped, naming the winner.
- The same fixture reported for the reversed order swaps the winner — the candidate order is what the
  breakdown follows.
- A dropped entry with a description or an icon the winner lacks is flagged; an entry without extra
  metadata is not.
- The four column cases: not carried; matched to another canonical channel (named); carries it with
  nothing in the window; lost every slot.
- **Anti-drift:** for the same channel and order, the breakdown's winners and slots equal
  `PriorityMerge.merge(...)`'s output for that channel.

### Web — `InspectionControllerTest` (HOU-25)

- `GET /api/merge/{channelId}` returns the columns; an unknown channel is a 404.
- No `order` means the applied order.
- An `order` naming an unknown source is a 400 naming it.

### Frontend — vitest (new; HOU-27 lands the setup, HOU-26 adds the row rules)

`reviewState.test.ts`:

- Empty storage yields the defaults (filter `needs-decision`, `fuzzy` off, `debug` off, empty query, no
  entries).
- A save/load round trip returns the same controls and entries.
- An entry whose row is absent from the payload is dropped.
- An entry whose `pick` is not in the payload's channel list is dropped.
- An entry carrying only `excluded` survives while its row exists.
- An unknown version is ignored: the tab starts fresh rather than reading a foreign blob.
- Legacy `houseepg.review.debug` seeds `debug` only when the blob is absent.
- The key encoding is stable for a row, and the module never parses the key back apart.

`panelRules.test.ts` (HOU-26): the row rules are extracted from the panel so they can be tested without
rendering:

- `chosen`: a pick wins, then `candidates[0]`, then the canonical id when the payload has it, and an
  unmatched row resolves to the unmatched sentinel rather than `''`.
- `writable`: true with a pick or a candidate; false for an unmatched row with neither.
- The accepted set is `visible ∧ writable ∧ !curated ∧ !excluded`.

## Gates

- `./gradlew test`
- `./gradlew integrationTest`
- `cd frontend && npx tsc --noEmit && npm run build`, then `frontend/dist/*` copied into
  `src/main/resources/static/`
- `cd frontend && npx vitest run` (new in HOU-27)

## Live pass

Required per card before its commit; the numbers go into the card's `finish` comment.

**HOU-27**

1. Set a filter, a search string, `Suggest similar names` and `Debug`; reload: all four are still set.
2. Pick a channel for one row and untick two others; reload: the pick and both unticked rows return.
3. Save the picked row and confirm one more row: both unticked rows are still unticked.
4. Hand-edit the stored blob to add an entry for a channel id that does not exist; reload: that entry
   is gone and nothing dead is applied.
5. `Accept matches…` still previews a count before anything is written.

**HOU-24**

1. Record the row count for `needs-decision` and for `all`, and the number of rows with no candidates
   (the log line already reports the row count).
2. `needs-decision` contains no zero-candidate row; every unmatched row appears under `unmatched`.
3. A row that was in `needs-decision` before is still there after an automatic refresh.

**HOU-26**

1. Filter to Unmatched: every row names `Unmatched — served as <its own id>`.
2. Pick a registry channel on one of them, save, and confirm it leaves Unmatched for Curated.
3. Tick a second row without picking: the box is disabled and the dry run's count does not include it.

**HOU-25**

1. On the Priority page choose a channel that overlaps across providers; check that each column's
   entries match what the review tab says about those source channels.
2. Move a source and confirm the waterfall changes **before** Apply, labelled "not applied yet".
3. Press Apply and confirm `GET /xmltv` for that channel matches the waterfall's winners.
4. Check the four column cases against real providers, including one whose channel is matched
   elsewhere.

## Deliberately not covered

- Component-level DOM tests: the vitest setup covers the extracted pure rules, not rendering (D14).
- The waterfall's visual layout and column ordering under many providers: a live look only.
- Two tabs writing the same blob (last write wins) and a `localStorage` quota failure.
- Save latency and the refetch after a write: that is HOU-23's measurement, which runs after these
  four cards.
