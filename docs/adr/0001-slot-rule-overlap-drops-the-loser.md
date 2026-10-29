# ADR-0001 — Slot rule: overlap drops the loser

**Status:** accepted
**Date:** 2026-10-29
**Deciders:** user + agent (grill session)
**Related:** [design §6.3](../design.md), ADR-0003

## Context

Several sources publish programmes for the same real channel, and they disagree: one may run
20:00–20:30 where another runs 20:00–20:35, and a third may have a 19:45–21:00 film that straddles
both. The merge has to decide what "the same slot" means before priority can resolve anything.

The two real samples in `test_artifacts/` show the problem: IPTV-EPG.org and open-epg.com both carry
Hungarian channels with their own start/stop times and their own naming, so exact agreement between
sources cannot be assumed.

Whatever rule is chosen also decides what the DVR client sees. XMLTV has no notion of a conflict; the
client renders whatever the file says. Duplicate or overlapping entries for one channel show up as a
broken guide.

## Options considered

1. **Exact start-time match.** A lower-priority programme is suppressed only if a higher-priority
   programme starts at the same second. Cheapest to implement and explain, but any provider whose
   times differ by a minute produces duplicate overlapping entries in the output.
2. **Overlap is a conflict; the winner takes its whole programme.** The highest-priority source
   covering that time wins its programme verbatim; a lower-priority programme that overlaps an
   already-claimed interval **at all** is dropped entirely. Uncovered stretches are filled by the
   next source down.
3. **Clip the loser to the uncovered interval.** The timeline is always fully tiled, but HouseEPG
   then emits programmes whose start/stop times it invented, plus short fragments.
4. **Whole-channel exclusivity.** If a higher-priority source has any data for a channel, that channel
   is served entirely from it, with no per-slot gap filling.

## Decision

**Option 2.** Per canonical channel, walk the sources in priority order. Each source claims the
interval `[start, stop)` of its programmes. Any overlap with an already-claimed interval causes the
programme to be **dropped entirely — never clipped**. Uncovered stretches are then filled by the next
source down. Fields are never mixed within a winning programme: it is emitted verbatim from the
winning source.

## Consequences

### Positive

- HouseEPG never invents a start or stop time. Every emitted programme's times come from a real
  source.
- No duplicate or overlapping entries for a channel, so the DVR never has to arbitrate.
- The rule is explainable in one sentence, and the UI can show exactly which source won each slot and
  which programmes it beat.

### Negative

- A long lower-priority programme that overlaps a winner anywhere is lost wholesale. A lower-priority
  film 19:45–21:00 overlapping a higher-priority 20:00–20:30 programme disappears completely, leaving
  19:45–20:00 empty even though no higher-priority source had anything there.
- The output is **not a fully tiled timeline**. Holes are expected and are the DVR client's problem to
  render as "no data".
- Because a rich source can be beaten by a poor one depending on priority order, the user must
  consciously order sources by trust, not just availability.

### Neutral

- The same rule applies to pass-through (unmatched) channels, which have no priority competition at
  all.
