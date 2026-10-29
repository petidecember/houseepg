# ADR-0002 — Emitted channel id stability and re-seed

**Status:** accepted
**Date:** 2026-10-29
**Deciders:** user + agent (grill session)
**Related:** [design §6.4](../design.md), ADR-0003

## Context

A DVR client caches the mapping from the XMLTV channel id to its own tuner channel. For an
M3U/IPTV-style setup that join key is normally the playlist's `tvg-id`. If the emitted id changes,
the guide silently detaches from the channel and the client shows "no guide data" until the user
re-maps it by hand.

Two forces pull against each other:

- **Upstream churn.** Sources rename channels and change ids. If HouseEPG simply followed the
  upstream id, a provider's rename would silently break every DVR.
- **"Primary" is positional.** The canonical channel registry is seeded from the M3U playlist when
  there is one, otherwise from the top-priority XMLTV source. Because the primary source is simply
  whoever sits at priority #1, reordering the priority list changes which source is primary — and
  therefore which source would seed the registry.

## Options considered

1. **Reuse the authority's id verbatim, never invent one, and freeze it.** The emitted id is the M3U
   `tvg-id` when a playlist is the authority, else the primary XMLTV source's channel id. A refresh
   never changes an id.
2. **HouseEPG generates its own ids** (slug or UUID) and exposes a mapping the user must apply to
   their DVR. Stable by construction, but it forces manual work on every client and breaks the
   `tvg-id` join that M3U-based setups rely on.
3. **The id follows whichever source won the merge for that channel**, recomputed per refresh.
   Freshest, but ids can change under the DVR without warning.
4. **Emit several `<channel>` elements per canonical channel**, one id per source, so any id the DVR
   already knows keeps resolving. Robust for the client, but produces duplicate channels in some
   clients.

## Decision

**Option 1, with an explicit escape hatch.**

- The emitted id is inherited verbatim: M3U `tvg-id` when the playlist is the channel authority,
  otherwise the primary XMLTV source's channel id. HouseEPG **never invents ids**.
- Ids are **frozen against implicit drift**: a routine source refresh never changes an id.
- Changing which source sits at priority #1 **re-seeds the registry from the new primary**, so ids
  **may** change. That is a deliberate, user-initiated operation, and the UI must **warn that DVR
  mappings may need redoing** before it is applied.

## Consequences

### Positive

- The common case — routine refreshes — never disturbs the DVR's cached mapping.
- For M3U-based setups the emitted id matches the `tvg-id` the DVR already joins on, so no client-side
  configuration is needed.
- There is exactly one way to change an id (a re-seed), and it is warned and user-initiated.

### Negative

- Reordering the priority list is not a harmless tweak: it can break DVR mappings. The warning is the
  only guard.
- An upstream rename is not propagated automatically; it becomes a deliberate registry edit in
  HouseEPG, which the UI must make possible and visible.
- "Primary" being positional means the concept of a primary source cannot be separated from list
  order.

### Neutral

- Frozen ids mean the registry accumulates channels over time; a channel that disappears upstream
  keeps its id until the user re-seeds or edits it.
