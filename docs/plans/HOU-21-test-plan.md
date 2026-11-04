# HOU-21 — One "Accept matches…" button: test plan

Companion: `docs/plans/HOU-21-implementation-plan.md`.

## Acceptance criteria

1. One "Accept matches…" button exists; `POST /api/matches/bulk-accept`, `api.bulkAccept` and the old button
   are gone.
2. The set written is exactly `visible ∧ checked ∧ writable ∧ not-yet-stored`.
3. Writable means a user pick or `candidates[0]`; the pass-through display fallback never makes a row
   writable.
4. Checkboxes default to checked; unchecking a row excludes it from the apply.
5. The preview count is shown before anything is written; nothing is written until Apply.
6. One batch write and exactly one re-merge per apply.
7. A second apply over the same rows accepts 0 and does not re-merge.
8. Exact rows are written when they are visible (the All view), so the freeze job survives the deletion of the
   old button.
9. The Curated filter still lists every stored decision and each row can be re-pointed by hand — the recovery
   path for a wrong apply.

## Automated cases

### Application — `AcceptMatchesTest` (replacing `BulkAcceptMatchesTest`)

- Writes exactly the rows it is handed: given 5 wanted rows and 10 stored matches, exactly those 5 become
  curated.
- The server never widens the set: it writes only the handed `(source, sourceChannelId)` pairs, never
  `matchStore.decisions()`.
- Duplicate `(source, sourceChannelId)` pairs collapse, last one wins.
- A `canonicalId` that is not in the registry is rejected, naming it.
- Rows whose decision is already stored are skipped: no rewrite, no count.
- A dry run reports the same count, writes nothing and re-merges nothing.
- Exactly one re-merge per apply, verified with a counting fake.
- An empty request: 0 rows, no write, no re-merge.
- A curated row survives a later automatic pass.

### Web — `InspectionControllerTest`

- `POST /api/matches/accept` returns the rows and count it was handed.
- A missing or blank `canonicalId` is a 400.
- `dryRun=true` leaves the store unchanged.

### Frontend

The repo has no frontend test runner, so the client-side rule (checkbox default, writable rule, visible scope)
is covered by `npx tsc --noEmit`, the build, and the live pass below rather than by a component test.

## Gates

- `./gradlew test`
- `./gradlew integrationTest`
- `cd frontend && npx tsc --noEmit && npm run build`, then `frontend/dist/*` copied into
  `src/main/resources/static/`

## Live pass

Required before the commit; the numbers go into the commit message.

1. Boot the branch against the real feed; record the default filter's row count and the registry channel
   count.
2. Apply once: **N rows accepted, with exactly one re-merge line in the log.**
3. Click again: **0 accepted, and no re-merge line.**
4. "Accept exact matches…" is absent from the panel.
5. **Recovery**: uncheck one row on purpose, apply, confirm that row still needs a decision; then re-point it
   by hand and save it.
6. `GET /xmltv` before and after: the channel id list is identical, so no emitted id moved.

## Deliberately not covered

Save latency and the refetch after a write. That is HOU-22's business, deferred behind the measurement task.
