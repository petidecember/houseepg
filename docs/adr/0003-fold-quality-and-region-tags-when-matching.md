# ADR-0003 — Fold quality and region tags when matching

**Status:** accepted
**Date:** 2026-10-29
**Deciders:** user + agent (grill session)
**Related:** [design §6.1, §6.2](../design.md), ADR-0002

## Context

Channels must be matched across sources before priority can resolve anything, and the real feeds
disagree about names. Inspecting `test_artifacts/`:

| Real channel | IPTV-EPG.org (`epg-hu.xml`) | open-epg.com (`hungary1.xml`) |
| --- | --- | --- |
| AMC | `AMC.hu` / `HU - AMC` | `AMC (HD).hu` |
| Animal Planet | `AnimalPlanet.hu` / `HU - Animal Planet` | `Animal Planet (HD).hu` |
| Apostol TV | `ApostolTV.hu` / `HU - Apostol TV` | `Apostol TV.hu` |

Two findings matter:

1. **Neither feed carries channel numbers or callsigns**, so the classic matching signals (number,
   callsign) do not exist here. An earlier assumption that they could be used is contradicted by the
   data.
2. The **same real channel is spelled differently per provider**: country prefix (`HU - `), country
   suffix (`.hu`), word spacing (`AnimalPlanet` vs `Animal Planet`), and quality tags (`(HD)`).

Strict id or exact-name matching would leave `AMC.hu` and `AMC (HD).hu` as two unrelated channels,
and the guide would not attach to the channel the DVR actually knows.

## Options considered

1. **Fold the noise when matching.** Strip country prefix, country suffix and quality tags before
   comparing, so `AMC.hu` and `AMC (HD).hu` normalise to the same key.
2. **Exact normalized names only.** `AMC` and `AMC (HD)` stay separate; the user fixes splits by hand.
3. **Fold tags but flag every fuzzy match as unconfirmed** until the user accepts it.
4. **Registry-driven best match with an ambiguity queue** for anything below a confidence threshold.

## Decision

**Option 1, plus the review step from option 3 and the pass-through guarantee.**

Matching normalises both the `display-name` and the source channel id by stripping the country prefix
(`<CC> - `), the country suffix (`.hu`), and quality tags (`(HD)`, `HD`, `(FHD)`, `FHD`, `(SD)`, `SD`,
`4K`, `UHD`), then lowercasing and collapsing punctuation and whitespace.

The **M3U registry decides** whether HD and SD are genuinely separate channels: if the playlist lists
them with distinct `tvg-id`s, they are separate canonical channels and folding must not merge them.
Every auto-match is recorded with a confidence and **shown in the UI to confirm or correct**, and a
source channel that matches nothing is kept as a **pass-through channel** rather than being dropped.

## Consequences

### Positive

- The same real channel from different providers lands on one canonical channel, which is what makes
  the priority system meaningful at all.
- Matching does not depend on channel numbers or callsigns, which the real feeds do not provide.
- Nothing is silently lost: unmatched channels still reach the DVR.

### Negative

- Folding can **over-merge**. If a provider genuinely lists `AMC` and `AMC (HD)` as different
  channels, the registry — not the feed — decides, and a wrong auto-match is only caught by the user
  reviewing the match list.
- The normalization rules are heuristic and will need maintenance as new providers appear.
- Word-spacing differences (`AnimalPlanet` vs `Animal Planet`) need a whitespace-insensitive
  comparison rather than a plain strip.

### Neutral

- Pass-through channels have no priority competition; they simply appear in the output with their own
  ids.
