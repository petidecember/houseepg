# HOU-12 — Match review UX: test plan / QA checklist

Companion: `docs/plans/HOU-12-implementation-plan.md`.

## Gate (every slice)

```shell
./gradlew test
./gradlew integrationTest
cd frontend && npx tsc --noEmit && npm run build
```

A slice is done when all three pass and the slice's own cases below pass.

## Slice 1 — Domain: `ChannelMatching.candidates(...)`

`ChannelMatchingCandidatesTest` (new, `src/test/java/com/anti/houseepg/domain/guide/policy/`):

| # | Case | Expected |
| --- | --- | --- |
| 1.1 | Source id equals a registry id | first candidate is that channel, `EXACT_ID` |
| 1.2 | Display name equals a registry name | first candidate is that channel, `EXACT_NAME` |
| 1.3 | Normalized id matches one channel | first candidate is that channel, `NORMALIZED_ID` |
| 1.4 | Only normalized name matches | first candidate is that channel, `NORMALIZED_NAME` |
| 1.5 | Key covers an HD and an SD channel, source advertises HD | HD channel first, then SD |
| 1.6 | Key covers two channels, neither advertises the tag | registry order decides, deterministically |
| 1.7 | Fuzzy on, no ladder hit, name one edit away | suggestion present, score above the threshold |
| 1.8 | Fuzzy off, no ladder hit | empty list (no new scoring rule runs) |
| 1.9 | Fuzzy on, many similar names | at most 3 candidates, no duplicates |
| 1.10 | Empty registry | empty list, no exception |
| 1.11 | Ladder hit plus fuzzy candidates | ladder hit first, fuzzy fills the remaining slots only |
| 1.12 | Existing matcher tests | unchanged and still green (the automatic answer did not move) |

## Slice 2 — DTO widening

| # | Case | Expected |
| --- | --- | --- |
| 2.1 | Channel with an icon | `ChannelDto.iconUrl` carries it |
| 2.2 | Channel without an icon | `iconUrl` is `null`, not `""` |
| 2.3 | Match row | `sourceChannelName` and `sourceIconUrl` describe the source channel, not the canonical one |
| 2.4 | `npx tsc --noEmit` | passes with the widened `types.ts` |

## Slice 3 — Review endpoint

`ReviewMatchesTest` (new, `src/test/java/com/anti/houseepg/application/guide/`):

| # | Case | Expected |
| --- | --- | --- |
| 3.1 | Default filter | curated rows absent |
| 3.2 | Default filter | `EXACT_ID` and `EXACT_NAME` rows absent |
| 3.3 | Default filter | `NORMALIZED_*` and `UNMATCHED` rows present |
| 3.4 | `filter=all` | every source channel present |
| 3.5 | `filter=curated` | only curated rows |
| 3.6 | `filter=unmatched` | only `UNMATCHED` rows |
| 3.7 | `fuzzy=false` | candidates are ladder hits only |
| 3.8 | `fuzzy=true` | unmatched rows carry up to 3 suggestions |
| 3.9 | Counter line | `M` and `K` match the rows returned in the same build |
| 3.10 | Empty registry | empty response, no exception |

## Slice 4 — Bulk accept

`BulkAcceptMatchesTest` (new, `src/test/java/com/anti/houseepg/application/guide/`):

| # | Case | Expected |
| --- | --- | --- |
| 4.1 | Dry run | returns the count and the rows, writes nothing |
| 4.2 | Apply | writes exactly the rows the dry run listed |
| 4.3 | Apply | exactly one re-merge (counting fake) |
| 4.4 | Apply twice | second call returns `count: 0` |
| 4.5 | Normalized and unmatched rows | never included |
| 4.6 | Already curated row | not included, not overwritten |
| 4.7 | Nothing to accept | `count: 0`, no re-merge |

## Slice 5 — Review UI (manual QA)

Run the app, open the review panel against the real feed.

| # | Step | Expected |
| --- | --- | --- |
| 5.1 | Open a row that is `UNMATCHED` | dropdown shows the best candidate, not the first channel |
| 5.2 | Press the action without touching the dropdown | decision saved, no 400 |
| 5.3 | Open the dropdown | `Best matches` group first (max 3), then `All channels` |
| 5.4 | Read an option label | name + quality tag, no channel number |
| 5.5 | Look at a row | source logo on the left, selected channel logo on the right |
| 5.6 | A channel with no icon | neutral placeholder, no broken image |
| 5.7 | Toggle fuzzy off and on | candidate list changes, no page reload needed |
| 5.8 | Press bulk accept | count shown first, then the list shrinks by that count |
| 5.9 | Debug toggle off | no confidence, score or reason text anywhere |
| 5.10 | Debug toggle on, reload the page | still on (localStorage), statistics visible |
| 5.11 | Curate a row, refresh the feed | the curated decision survives |

## Slice 6 — Measured live pass

This is the acceptance evidence for the fuzzy counter (decision 5), so it is recorded, not just observed.

| # | Step | Recorded |
| --- | --- | --- |
| 6.1 | Start Edge, attach Surf, open the review panel | — |
| 6.2 | Note the default list size | rows needing a decision |
| 6.3 | Bulk accept dry run, then apply | count, and the list size afterwards |
| 6.4 | Read the log line | `review: N rows, M would be auto-resolved by fuzzy, K ambiguous` |
| 6.5 | Curate the remaining rows by hand | rows left, and how many needed the dropdown |
| 6.6 | Put 6.2–6.5 in the commit message | verbatim numbers |

The pass is done when the commit message contains the before/after counts and the counter line, so the
tier-2 promotion question can be answered from evidence rather than from a guess.

## Regression coverage

- The automatic matcher's output must not change: the existing `ChannelMatching` tests stay untouched
  and green, and a live refresh produces the same channel ids as before the change.
- `GET /api/channels` and `PUT /api/matches` keep working for the existing UI paths until slice 5
  replaces them.
- The reseed preview and the YAML order store are untouched by this card; their tests must stay green.
