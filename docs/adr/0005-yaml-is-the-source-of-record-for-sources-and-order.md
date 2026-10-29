# ADR-0005 — YAML is the source of record for sources and their order

**Status:** accepted
**Date:** 2026-10-29
**Deciders:** user + agent (grill session)
**Related:** [design §7](../design.md), [design §10](../design.md), ADR-0002, ADR-0004

## Context

The React configuration surface (HOU-6) asked for source CRUD, drag-to-reorder priority, enable/disable
toggles, refresh-interval editing and credential editing. That implies **mutable configuration**, and
the application currently has none: sources arrive from YAML at boot (`HouseEpgProperties`) and
nothing else can change them.

Two things pull against "let the UI own the configuration":

- **The database is not a system of record.** HOU-5 persisted the three guide layers, the channel
  registry, the channel matches and per-source health, but all of it is derived: it can be rebuilt
  from the sources. Making it authoritative for configuration would give it a second, unrelated job.
- **A DVR caches emitted channel ids.** ADR-0002 already froze them against routine refreshes. Any
  configuration surface that can silently change which source is primary can silently break every
  client's id→channel mapping.

What is genuinely user state? Only one thing: **which source wins a slot**. Everything else about a
source — where it lives, how to authenticate, whether it is on, how often to fetch — is a deployment
decision that belongs in a versioned file.

## Options considered

1. **Database is the system of record**: sources move into MongoDB, YAML only bootstraps.
2. **Hybrid**: YAML declares identity (id, name, URL); the database owns enabled, interval, priority
   and credentials.
3. **YAML stays the system of record, and the UI edits only the order**, which the server writes back
   into the same file.
4. **Defer the configuration surface entirely** and ship a read-only UI.

## Decision

**Option 3.**

- **YAML declares everything about a source**: id, name, URL, credentials, enabled flag and refresh
  interval. The UI **cannot insert, edit, disable or delete a source**, and can never read or write
  credentials; it may only report whether they are configured.
- **The only user-mutable state is the priority order.** A reorder applies to the running merge
  immediately, and is persisted by rewriting the **source list order** in the YAML file — atomically
  (temp file + rename) and with a **comment-preserving editor**, so hand-written comments survive
  while whitespace and quoting elsewhere may be normalised. No other field is touched.
- **A failed persist is logged, not returned as an error.** The response carries a **persist status**,
  so the UI can never present an applied-but-unpersisted order as saved.
- **Configuration is boot-only, with one explicit escape hatch**: a UI "reload config" button. Reload
  rebinds sources and order and re-merges, but **the registry stays frozen**, so emitted ids never
  move; a newly added source contributes as a pass-through until the explicit re-seed is run.
- **Reordering never re-seeds.** Only an explicit re-seed action — which previews the id changes
  first — may change an emitted id (ADR-0002 unchanged).
- **The database is an intermediate representation of the merge** and is disposable.
- **Debug dump**: the frontend may ask the backend to write the in-memory configuration, credentials
  included, to the **log stream** (never an HTTP response). No gate and no password prompt, consistent
  with ADR-0004's no-authentication posture.

## Consequences

### Positive

- One owner per concern: the deployment owns the sources in git, the user owns the order in the same
  file, and the database owns only what it derives.
- Emitted ids can change in exactly one place — the explicit re-seed — which is what the DVR needs.
- Dropping the database loses no user intent; the merge rebuilds from YAML.
- A source's URL, credentials and enabled flag are reviewable in a diff, not hidden in a database.

### Negative

- The server now writes a file people hand-maintain. A comment-preserving editor keeps comments but
  normalises whitespace and quoting elsewhere, so the first reorder produces a noisy diff.
- A reorder made before a reload can overwrite a hand edit that has not been picked up yet. The
  README-level rule is "edit YAML, then reload or restart".
- Best-effort persistence means a write failure is visible only through the persist status; the order
  then reverts to the file at the next restart.
- "Refresh interval" and "enabled" cannot be changed from the UI at all; a deployment change is a file
  edit plus a reload.

### Neutral

- The UI buys less than the original card asked for: it reorders, reviews matches, shows health,
  previews and renders the guide, but it cannot create or edit a source.
- The debug dump deliberately puts credentials into log storage, which typically outlives the LAN.

## Open consequences (explicitly deferred)

These were deferred at decision time, each with a defensible default, and must be settled before or
during implementation:

- **Convergence after a failed persist.** Memory and disk diverge until restart, then the order
  reverts. Default: accepted, log-only, with the persist status as the guard.
- **Invalid YAML at boot/reload.** Default: fail fast — the file now holds both sources and the order,
  so a half-read configuration is worse than no start.
- **Who triggers a re-seed.** Default: a UI action with the id-change preview, which gives "the UI may
  only reorder" one stated exception.
- **What "immediately" means.** Default: the next refresh cycle; a reorder never interrupts an
  in-flight fetch.
