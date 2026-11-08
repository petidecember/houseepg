# HouseEPG — Design

**Status:** agreed design, not yet implemented
**Scope:** the whole system. Decisions marked *contested* have their own ADR in `docs/adr/`.

---

## 1. Purpose

HouseEPG acquires EPG (guide) data from several upstream sources, consolidates it into a single
guide under a user-defined priority system, and serves that guide as XMLTV over HTTP on the local
network.

**Success criterion:** the DVR client shows a correct merged guide that reflects the user's priority
rules.

**Primary consumer:** standard media/DVR software on the LAN — Jellyfin, TVHeadend, Threadfin/xTeVe
class — pulling an XMLTV URL.

## 2. Non-goals

- **No JSON API.** XMLTV over HTTP is the whole output contract.
- **No lineup or tuner emulation.** HouseEPG never serves M3U and never emulates an HDHomeRun, so
  the DVR keeps the tuner/playlist it already has and only its EPG URL points at HouseEPG.
  Direct Plex support falls out of this: Plex attaches custom guides only through a discoverable
  tuner, so Plex support routes through an existing Threadfin/xTeVe that consumes HouseEPG's XMLTV.
- **No authentication.** See ADR-0004.
- **No Docker.** See §12.
- **No per-field merging and no weighted scoring.** See §6.3.

## 3. Constraints and stack

Fixed by the existing scaffolding (`build.gradle`): Spring Boot 4.1.2, Java 25, Gradle 9.8.2,
Spring Data MongoDB, `restclient`, `webmvc`, actuator, Lombok, GraalVM native buildtools.
Frontend: React (TypeScript) under `frontend/`, built with `tsc`/Vite and copied into
`src/main/resources/static/`.

Delivery follows `AGENTS.md`: kanboard task → `start` → `create_goal` → work → verify → auditor →
`finish --comment "<evidence>"` → user closes. **One git branch per task**, named `feature/` or `bugfix/` + the task id + a short description (e.g. `feature/HOU-2-yaml-config-to-xmltv-guide`).

## 4. Architecture

```text
        ┌──────────────────────────────────────────────────────────────┐
        │  React UI  (frontend/ → src/main/resources/static/)          │
        │  sources · priority · matches · health · guide preview       │
        └───────────────▲──────────────────────────────────────────────┘
                        │ (open on the LAN, no auth)
┌───────────────────────┴──────────────────────────────────────────────┐
│ HouseEPG (Spring Boot, single jar)                                    │
│                                                                       │
│  SourceScheduler ──▶ SourceFetcher ──▶ XmltvParser ──▶ RawSnapshot    │
│        (per-source interval, manual trigger)          store           │
│                             │                                         │
│                             ▼                                         │
│                     ProgrammeNormalizer ──▶ NormalizedStore           │
│                             │                                         │
│                             ▼                                         │
│   RegistrySeeder ──▶ ChannelRegistry ◀── ChannelMatcher               │
│      (M3U / primary source)      │              (normalized names)    │
│                                  ▼                                    │
│                            MergeEngine ──▶ MergedGuideStore           │
│                                  │                                    │
│                                  ▼                                    │
│                        GET /xmltv  (XMLTV feed)                       │
└───────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
                       DVR client (Jellyfin / TVHeadend / …)
```

Components, in dependency order:

| Component | Responsibility |
| --- | --- |
| `SourceFetcher` | HTTP GET a source URL: plain `.xml` or `.xml.gz`, optional credentials in the URL or a header; timeout; size guard. |
| `XmltvParser` | Stream-parse XMLTV into `SourceChannel` + `Programme` records. |
| `RawSnapshotStore` | Persist the last good payload per source. |
| `ProgrammeNormalizer` | Map source programmes onto canonical channels via the registry; index them by channel + start. |
| `RegistrySeeder` | Seed the canonical channel registry from the M3U playlist, else from the primary (top-priority) XMLTV source. |
| `ChannelMatcher` | Attach each source channel to a canonical channel by normalized name; produce a confidence and a review list. |
| `MergeEngine` | Per-channel timeline claim over the priority-ordered sources; emits the merged guide. |
| `MergedGuideStore` | Materialize the merged guide for serving and for UI preview. |
| `SourceScheduler` | Per-source refresh interval (default 6–12 h), manual trigger, failure tracking, health. |
| `FeedController` | `GET /xmltv` — the served guide. |
| `AdminController` | UI-facing endpoints: sources, priority, matches, registry, health, refresh, preview. |
| React UI | Configuration and inspection surface (§10). |

### 4.1 Code organisation (DDD layers)

The code is organised in the four DDD layers, with the bounded context nested inside each layer:

| Layer | Contents |
| --- | --- |
| `domain/guide/model` | Entities and value objects: `ChannelId`, `SourceId`, `Slot`, `Channel`, `Programme`, the `Guide` aggregate, `SourceEpg`/`SourceChannel`/`SourceProgramme`, `SourceHealth`. |
| `domain/guide/policy` | Domain policies: `ChannelKey` (name normalisation), `ChannelMatching`, `PriorityMerge`, `SourceChannelRef`. |
| `application/guide` | Use cases `RefreshGuide` and `ServeGuide`, plus `GuideSettings` and `RefreshResult`. |
| `application/guide/port` | Ports and their collaborators: `EpgSourceGateway`, `ChannelAuthorityGateway`, `EpgSource`, `Credentials`, `SourceUnavailableException`. |
| `infrastructure/config` | Spring wiring and the bound properties (`HouseEpgProperties`, `HouseEpgConfiguration`). |
| `infrastructure/net` | Shared HTTP fetching. |
| `infrastructure/epg` | XMLTV reading into the domain model. |
| `infrastructure/playlist` | M3U channel-authority reading. |
| `infrastructure/xmltv` | The XMLTV timestamp format, shared by reading and writing. |
| `interfaces/web` | REST controller, XMLTV renderer and response view. Named `interfaces` because `interface` is a reserved word. |

Dependencies point inward:

- `domain` imports nothing but `java.*` and itself.
- `application` imports `domain` and `java.*` only; the single third-party dependency is the SLF4J logging
  facade, and no Spring type appears in it.
- `infrastructure` and `interfaces` implement and call the application's ports; `config` is the only
  place that knows about Spring beans.

## 5. Domain model

| Entity | Key fields |
| --- | --- |
| `Source` | id, name, url, credentials (URL-embedded or header), format (`XMLTV`), enabled, refresh interval, priority position, health (last success, last failure, last error), `lastGoodSnapshotId` |
| `RawSnapshot` | id, sourceId, fetchedAt, contentType, bytes (payload, possibly compressed), etag/last-modified |
| `CanonicalChannel` | id (**emitted XMLTV channel id**), displayName, icon, number, source-of-truth (`M3U` \| `PRIMARY_XMLTV`), frozen flag |
| `SourceChannel` | sourceId, sourceChannelId, displayName, icon |
| `ChannelMatch` | sourceChannelRef (`sourceId`, `sourceChannelId`), canonicalChannelId, matchConfidence (`EXACT_ID` \| `EXACT_NAME` \| `NORMALIZED_ID` \| `NORMALIZED_NAME` \| `NONE`), matchState (`AUTO` \| `CONFIRMED` \| `CORRECTED` \| `UNMATCHED`) |
| `Programme` | sourceId, canonicalChannelId, start, stop, title, desc, category, episodeNum, icon, raw fields |
| `MergedProgramme` | canonicalChannelId, start, stop, **winning sourceId**, payload (verbatim from the winning source) |
| `RefreshRun` | sourceId, startedAt, outcome, programme count, error |

`MergedProgramme` keeps `winningSourceId` so the UI can explain the merge and show which source won
each slot.

## 6. Algorithms

### 6.1 Name normalization

Used only for matching, never for output. Applied to `display-name` and to the source channel id:

1. Strip a **country prefix**: a two-letter country code, a dash, then the name (`HU - AMC` → `AMC`).
2. Strip a **country suffix** (`AMC.hu` → `AMC`).
3. Strip **quality tags**: `(HD)`, `HD`, `(FHD)`, `FHD`, `(SD)`, `SD`, `4K`, `UHD`.
4. Spell a literal `+` as `plus` (`RTL+` → `RTLplus`).
5. Lowercase, strip punctuation, collapse whitespace.

`AMC.hu` → `amc`; `AMC (HD).hu` → `amc`; `HU - Animal Planet` and `Animal Planet (HD).hu` → both
`animal planet`. See ADR-0003.

A `+` is a word, not punctuation. `RTL+` is RTL's streaming service, not `RTL`, and `M4 Sport+` is not
`M4 Sport`; dropping the sign folded a whole streaming catalogue into the linear channel's guide
(HOU-46). Spelling it out keeps the pair joined (`Canal+ Action` = `Canal+.Action`) without joining the
channel it extends (`RTLplus` ≠ `rtl`).

### 6.2 Channel matching

- The **registry** is the authority: it is seeded from the supplied M3U playlist, or — when there is
  no playlist — from the primary (top-priority) XMLTV source. It is seeded **once** and then kept
  across refreshes; a refresh never re-seeds it, and an authority that cannot be read keeps the
  previous registry rather than substituting another source's ids (§6.4, ADR-0002).
- Each source channel is matched to a registry channel by the strongest available evidence, in this
  order: the exact source channel id, the exact display name, the normalized id, the normalized
  display name. The M3U decides whether HD and SD are genuinely separate channels (distinct
  `tvg-id`s); because normalization strips exactly those quality tags, a key that covers several
  registry channels is resolved by preferring the candidate advertising the same quality, then by
  registry order, so the choice is deterministic.
- Every match is recorded as a `ChannelMatch` with its confidence and state, and is **shown in the UI
  to confirm or correct**. A `CONFIRMED` or `CORRECTED` decision is reused on later refreshes and an
  automatic pass never overwrites it; a decision whose canonical channel no longer exists is stale and
  matching falls back to name lookup.
- A source channel that matches nothing is kept as a **pass-through channel**: it is emitted with its
  own id, so nothing is silently dropped, and it is recorded as `UNMATCHED`.

### 6.3 Merge — per-channel timeline claim

Sources are an **ordered list**. For each canonical channel:

1. Walk sources in priority order.
2. A source **claims** the interval `[start, stop)` of each of its programmes for that channel.
3. **Any overlap is a conflict.** If a programme overlaps an already-claimed interval **at all**, it
   is **dropped entirely — never clipped**.
4. Uncovered stretches of the timeline are then filled by the next source down.

Fields are **never mixed within a single programme**: the winning programme is emitted verbatim from
the winning source, even if another source has richer metadata for the same slot.

**Accepted consequence:** a long lower-priority programme that overlaps a winner anywhere is lost
wholesale. A lower-priority movie 19:45–21:00 overlapping a higher-priority 20:00–20:30 programme
disappears completely, leaving 19:45–20:00 empty. The output is therefore **not a fully tiled
timeline**; holes are expected and are the DVR client's problem to render as "no data". See ADR-0001.

#### One source competing with itself

The walk compares **slots and source order** only. It has no notion of "the same source", "the same
spelling" or "the same title", so the rule applies inside a single source exactly as it does between two:

| | between providers | within one source |
| --- | --- | --- |
| what is compared | slot overlap | slot overlap |
| order used | source priority, then start | source priority, then start |
| tie-break available | the higher-priority provider wins | **none — the earlier entry wins** |
| what the loser keeps | nothing, dropped whole | nothing, dropped whole |
| what the UI says | the merge preview (§6.6) | nothing; the entry is simply absent |

**That missing tie-break is the point.** Across providers, "first in the order wins" is a decision an
operator made. Inside one source there is no such decision: the order is the file's declaration order.
The walk cannot tell a real contest from a provider that shipped the same programme twice, and resolves
both the same way.

Re-measured on a live store with four enabled sources. Counting only overlaps **inside one source
channel id** (the walk as it resolves one source's own contest), against what is actually served:

```
source          raw      lost   share   exact dup   ids affected
live-hutv      7970       260    3.3 %         14             24
provider-a    32765         0    0.0 %          0              0
provider-b    13196         0    0.0 %          0              0
provider-c    23986         0    0.0 %          0              0
```

`live-hutv` is the only one of the four that competes with itself at all, losing 260 of 7970 entries
(3.3 %) across 24 channel ids: `nicktoons.hu` 82, `nickjr.hu` 68, `Canal+ Action.hu` 18,
`Filmbox+ Emotion HD.hu` 15. The provider ships a normal-length entry and a shorter one nested inside its
tail, then repeats:

```
         18:10       18:30 18:35        18:45       19:05 19:10        19:30
          |-----=====|      |-------------|          |-----=======|
                 |=X==|                                  |=X===|
   won   18:10-18:30        won   18:30-18:45        won   18:45-19:05
   won   18:30-18:45        won   18:45-19:05        won   19:05-19:30
   LOST  18:35-18:45        ...                      LOST  19:10-19:30
```

The surviving schedule stays coherent, which is why this is only visible as *"my feed has fewer
programmes than the XML file"*. The other three sources publish fully tiled timelines with no internal
overlap, so this is a property of one provider, not of the rule.

**Where a source's own overlaps are actually resolved, though, is usually not per channel id.** Matches
from every source land in one canonical channel's walk, and the live store has 350 channels fed where the
registry holds 133 emitted ids — the rest are pass-through channels no provider declared, and **18
normalised keys cover two emitted ids each** (`atv` → `atv.hu` and `ATV (HD).hu`; same for `tv2`,
`supertv2`, `dunatv`, `m5`, `m4sport`, `sport1`, `sport2`, `cool`, `tv4`, `axn`, `animalplanet`,
`viasat3`, `moziverzum`, `amc`, `rtlgold`, `izauratv`, `prime`). So two registry channels that are
plainly the same channel feed one walk each, and one of them is the loser. That, not the 3.3 %, is where
the row loss a real operator sees comes from, and it is a matching problem (HOU-43) rather than
something this rule should decide.

**What is not lost** is coverage: every source channel's programmes stay inside that channel's own span,
and the served guide is the raw span minus these overlaps. Nothing is corrupted — every served programme
belongs to a declared channel and the DVR sees a coherent schedule. What is lost is entries, silently:
no log line, no counter and no UI surface. Recorded as a known consequence rather than a defect: a
source that publishes overlapping entries is a low-quality source, and an operator who dislikes what it
publishes should choose a better XMLTV. Making the loss **visible** — a per-source drop count next to
`programmeCount` — is tracked separately (HOU-37), and dropping exact duplicate slots before the walk is
the one cheap rule change that needs no judgement about which entry to keep (HOU-35).

**Decisions taken here, and what was parked.** *Taken:* the rule stays as it is and this document is the
answer (**A**) — a source that competes with itself is a low-quality source, and its twin case loses only
one of two title styles for a slot it still fills. *Taken, later:* counting the drops and showing them next
to `programmeCount` (**D**, landed as HOU-37 — see the fold below), because an invisible loss cannot be
judged by an operator at all. *Parked as their own cards:* dropping exact duplicate slots before the walk
(**B**, HOU-35 — the one case where doing nothing needs no judgement), a per-source tie-break preferring
the longer or richer entry (**C**, HOU-36 — invasive, it adds a second rule and picks a winner the
operator never chose), and reusing the exclusion list to silence one bad channel id (**E**, HOU-38).
*Rejected:* nothing here; C only if asked for.

**Every loss is counted, because nothing else records it.** A dropped entry is emitted nowhere: no XMLTV
element, no log line, no row. So the same walk that decides the losses also folds them per source and
splits them three ways, and `/api/sources/status` reports the result (HOU-37). The classes are the
operator's lever, because each points at a different action:

| Class | What it means | What the operator can do |
| --- | --- | --- |
| `duplicateSlots` | the same slot was claimed byte-for-byte by an earlier entry of the same source | the provider is shipping an entry twice; nothing to decide |
| `partialOverlaps` | an earlier entry of the same source overlapped it | the provider is publishing a nested or overlapping pair; this is the class with no tie-break and the place a per-source rule would help (HOU-36) |
| `lostToHigherPriority` | another source's entry claimed an overlapping slot first | the slot rule working as intended; reorder if the wrong source is winning |

The fold lives inside the walk rather than in a controller for one reason: the merge preview (§6.6)
reports the same losses for one window, and two independent counters would eventually disagree. A source
that lost nothing reports zero, not an absent row, so "this source is being eaten" is answerable at a
glance without opening the preview. An entry whose channel matched nothing never reaches the rule and is
therefore not counted — the loss classes describe the rule's decisions, not publisher quality.

Losses are counted **one canonical channel at a time and then summed**, never by flattening every
channel's walk into one list. An outcome's `conflictingEntry` is an index into the walk that produced it,
so a flat list would make every channel after the first resolve its conflict against whichever entry sits
at that offset in a different channel: the total would still add up, and the reason class would be wrong
— a duplicate slot reported as a partial overlap. The totals agreeing is what makes this failure quiet,
which is why the summing rule is written down here rather than left to the code.

### 6.4 Emitted channel id and its stability

- The emitted XMLTV channel id is the **M3U `tvg-id`** when the playlist is the channel authority,
  otherwise the **primary XMLTV source's channel id**. HouseEPG **never invents ids**.
- Ids are **frozen against implicit drift**: a routine source refresh never changes an id, so the
  DVR's cached id→channel mapping survives. Concretely, the registry is seeded once and kept; a failed
  or empty playlist read reuses it instead of falling back to another source's ids (ADR-0002).
- Ids **can** change on a **deliberate re-seed** — changing which source sits at priority #1 —
  because "primary" is positional. The UI must warn that DVR mappings may need redoing. See ADR-0002.

### 6.5 One refresh, in pictures

§6.1–§6.4 carry the rules; this section is only their shape. Everything below happens in one
`RefreshGuide.refreshAll` pass.

**Stages.**

```text
per source: fetch, or reuse the last good stored data ──▶ SourceHealth + SourceEpg
                            │
                            ▼  drop programmes that ended before the cutoff (§8)
      registry(documents) ──▶ List<Channel>   playlist  >  stored  >  seed from source #1
                            │                 (never a second source, ADR-0002)
                            ▼
      ChannelMatching.match(registry, documents, decisions)
                            │ ──▶ canonicalIds: (source, sourceChannel) → ChannelId
                            │ ──▶ List<ChannelMatch>, state AUTO or UNMATCHED
                            ▼
      saveAuto(matches)                       a curated row is never rewritten
                            │
                            ▼
      PriorityMerge.merge(channels, documents, canonicalIds, priority)
                            ▼
      saveMergedGuide(guide) ──▶ GET /xmltv
```

**The ladder, per source channel.** Strongest evidence first, first hit decides.

```text
  ┌─ a recorded decision ──────────────────────────────────────────┐
  │ CONFIRMED or CORRECTED, and its canonical channel still exists │──▶ reused
  └────────────────────────────────────────────────────────────────┘
        │ no decision, or a stale one: the canonical channel is gone
        ▼
  byId[channel.id]            hit ──▶ EXACT_ID          1.0
  byDisplayName[channel.name] hit ──▶ EXACT_NAME        0.9
  byKey[key(channel.id)]      hit ──▶ NORMALIZED_ID     0.8
  byKey[key(channel.name)]    hit ──▶ NORMALIZED_NAME   0.6
  nothing                      ───▶ UNMATCHED           0.0
                                     + a PASS_THROUGH channel under the source's own id
```

`byKey` indexes every registry channel **twice** — under the key of its id and under the key of its
display name — so a source channel id can land on a registry *name*, and that is still
`NORMALIZED_ID`.

**One channel, three spellings.** The playlist is the authority, so it decides whether HD and SD are
separate channels; `AnimalPlanet.HD`, `AnimalPlanet.SD` and `AnimalPlanet.hu` all normalize to
`animalplanet`, which is the collision `QualityTag` exists to break.

```text
source: id "animalplanet.hd", display name "HU - Animal Planet HD"
  1. recorded decision ........... none
  2. byId["animalplanet.hd"] ..... miss   (the registry id is "AnimalPlanet.HD": case differs)
  3. byDisplayName[...] .......... miss   (not character for character)
  4. byKey["animalplanet"] ....... HIT    key of the id, tag stripped
                                          3 candidates, wanted tag "hd"
                                          → AnimalPlanet.HD
                                          ──▶ NORMALIZED_ID 0.8

source: id "50505", display name "HU - Animal Planet SD"
  4. byKey["50505"] .............. miss
  5. byKey["animalplanet"] ....... HIT    key of the display name, prefix and tag stripped
                                          wanted tag "sd" → AnimalPlanet.SD
                                          ──▶ NORMALIZED_NAME 0.6
```

The preferred candidate is the one advertising the source channel's own `QualityTag`, read from its
id and then from its display name; registry order decides the rest. The review list orders candidates
the same way, so the automatic answer and the suggestion shown beside it cannot disagree.

**Suggestions are a separate door.** `ChannelMatching.candidates` — the best three, fuzzy only when
asked for — is an entry point `match` never calls. A suggestion becomes a match only through an
explicit decision, which is what keeps emitted ids still (ADR-0002).

**Then the slots.** Matching decides which canonical channel a programme belongs to; §6.3 decides
which programme survives.

```text
canonical channel ─────────────── time ───────────────▶
  source #1   [ A ][ B ][ C ]      claims A, B and C
  source #2   [ a ][ b ][  D  ]    a and b overlap ──▶ dropped whole
  source #3   [       E ]          E overlaps B    ──▶ dropped whole
  emitted     [ A ][ B ][ C ]
```

**Nothing above is visible while it happens.** §6.3 is why a source can sit at priority #1 and still
contribute nothing, and the ordering is what a person changes. §6.6 is the shape that makes the
consequence visible *before* it is applied.

### 6.6 The merge preview, in pictures

Two surfaces show merged data, and they answer different questions.

| | Guide panel | Merge preview (HOU-25) |
| --- | --- | --- |
| Scope | every channel | **one channel**, chosen from a dropdown |
| Order | the applied priority | the **draft** order in the panel, labelled *not applied yet* |
| Content | the finished merge | **per provider**: each entry and its fate |
| Answers | "what will my DVR see" | "what does reordering cost me" |
| Changes the feed | it *is* the feed | **never** — Apply order is still the only thing that does |

**Layout.** Providers left to right in rank order, then the merge result on the right.

```text
  provider order    #1 Alpha TV   #2 Beta Télé   #3 Gamma Sat   #4 Delta Media
  ─────────────────────────────────────────────────────────────────────────────
  channel      Alpha TV HD        day 05/11/2026   from 20:00   span [5 h]  ☑ now line
                                                                    20:00 ──▶ 01:00
  ┌───────────────┬───────────────┬───────────────┬───────────────┬───────────────┐
  │ 1 Alpha TV    │ 2 Beta Télé   │ 3 Gamma Sat   │ 4 Delta Media │ Merge result  │
  ├───────────────┼───────────────┼───────────────┼───────────────┼───────────────┤
  │▒20:00 Wild Afr│▒20:00 Wild Afr│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒20:00 Wild Afr│
  │▒      ████████│▒  ✗ α claimed │▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒  ✓  Alpha TV │
  │▒21:00 Deep Ocn│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒21:00 Deep Ocn│
  │▒      ████████│▒22:30 Ciel Noc│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒  ✓  Alpha TV │
  │▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒      ████████│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒  ⚠ no descript│
  │▒▒▒▒▒  now ▒▒▒▒│▒▒▒▒▒▒▒  now ▒▒│▒▒▒▒▒▒▒  now ▒▒│▒▒▒▒▒▒▒  now ▒▒│▒22:30 Ciel Noc │
  │▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒│▒  ✓  Beta Télé │
  └───────────────┴───────────────┴───────────────┴───────────────┴───────────────┘
       won whole    lost 20:00–21:00     nothing in       a different        the feed
                    to Alpha TV         this window      canonical channel
```

- **The clock is vertical and shared.** Time runs down the left gutter; every column is the same
  window laid out top to bottom, so a programme's **height is its duration** and the `now` marker
  lines up across all five. The span is chosen in **hours**, not in a count of programmes.
- **Stripes mean "nothing here".** One striped surface runs behind all columns; programme blocks are
  drawn on top of it, full-bleed. So the stripes appear exactly where no entry covers the time —
  Alpha TV after 22:00, Gamma Sat and Delta Media entirely, and every column past the end of the
  published data. There is **no text inside them**: "no programme" and "no data published" are
  deliberately drawn identically, and the coverage span and gap count are what tell them apart.
- **Colour is the only verdict.** Green: the entry won (or the result carries it). Red: it lost an
  overlap, with the reason inline (`lost 20:00–21:00 — Alpha TV claimed it first`). No `carried` or
  `dropped` labels, no legend — the design language carries it.
- **Every provider column states its own case**, so a column is never blank and never silent:
  1. it won entries; 2. it lost them to a named overlap; 3. it does not carry this channel;
  4. it carries a channel matched to a *different* canonical channel (named) and therefore cannot
  take part, however the order is arranged; 5. it carries this channel but has nothing in the window.
- **The merge result column is the channel's whole feed**, served from the same state the live merge
  would serve, and the window only decides how much of it is drawn: a programme that starts before the
  window still belongs to the feed and its span is clipped to the window when it is drawn. What the
  window does decide is the footer's arithmetic — the covered span and the gaps are measured inside it.
  Each carried programme is tagged with the
  provider that won it, and an amber callout when the winner is poorer than a loser for the same
  fields (`weaker: description — Beta Télé had one`). Its footer carries the covered span, the
  programme and gap counts, and the reminder that nothing changes until **Apply order**.
- **Defaults and pickers.** The preview opens on the **first channel in the list**, **today**, at the
  **current time**, with a default span. The day and start pickers are bounded by the channel's
  **published span across the whole guide**, not by the window on screen: the window's own entries can
  only ever cover the days already in view, so bounding on them made every day but today unselectable.
  The backend reports that span alongside the breakdown (`available`), unclipped by the window.
- **A block has a floor.** A dense hour would squeeze a 15-minute programme into a few pixels and its
  title would be unreadable, so the track is **as tall as its shortest entry needs** (a minimum block
  height, capped) and the page scrolls instead of squashing. Every column shares that one height, so a
  programme still starts at the same pixel in all of them. Flush blocks carry a **hairline at their
  foot**, because without it a run of back-to-back programmes reads as one undivided band.
- **The `now` line is the thing the checkbox hides.** It marks the current time on the axis; nothing
  else — stripes or hour rules — is switched by it.

**One walk, two callers.** The preview must not be a second merge engine: the slot-claim loop of
`PriorityMerge.merge` (§6.3) is extracted into a **pure function that takes the order as a
parameter**, and both the live merge and the preview call it. A preview that disagreed with the feed
would be worse than no preview. The preview needs new *data access* — per-source normalized
documents and their canonical ids — but nothing new persisted, and the Guide panel is unchanged.



## 7. Persistence

Three layers in MongoDB. The database is an **intermediate representation of the merge**: everything
in it is derived from the sources and can be rebuilt, which is why it may be dropped at any time
without losing user intent (ADR-0005). The one piece of user state — the **source priority order** —
lives in the YAML configuration file, not here.

Three layers:

1. **Raw snapshot per source** — the last good payload. A re-merge never needs a re-fetch.
2. **Normalized programmes per source** — indexed by `(canonicalChannelId, start)` and by
   `(sourceId, canonicalChannelId)`.
3. **Materialized merged guide** — what `GET /xmltv` serves and what the UI previews.

Indexes: `programmes(canonicalChannelId, start)`, `programmes(sourceId, canonicalChannelId)`,
`programmes(stop)` for retention, `mergedProgrammes(canonicalChannelId, start)`,
`sourceChannels(sourceId, sourceChannelId)` unique, `canonicalChannels(id)` unique.

**Retention:** programmes that ended more than ~7 days ago are pruned, and they are dropped before the
merge, so the materialized guide never carries them either. One raw snapshot per source is kept (the
last good one); a failed fetch never writes one.

**Reads never re-merge.** `GET /xmltv` and the UI read layer 3; the refresh is the only writer. The
database name is `houseepg.mongodb.database` (default `houseepg`) so tests can point the same
application at a dedicated database.

## 8. Refresh and failure policy

- Each source has its own **scheduled refresh interval** (default 8 h, configurable per source) plus a
  **manual "refresh now"** (`POST /api/sources/{id}/refresh`). A scheduler tick (default 60 s) decides
  what is due; refreshes are serialized, so a tick and a manual refresh never write the layers at the
  same time.
- On success: new raw snapshot → re-normalize → re-merge → re-materialize.
- On **failure, timeout, or empty/unparseable response**: the source keeps its **last good data**,
  is marked **unhealthy** in the UI, and the **served guide never degrades because of a network
  error**. Stale-but-rich beats fresh-but-missing here, deliberately.

## 9. HTTP surface

| Endpoint | Purpose |
| --- | --- |
| `GET /xmltv` | The merged XMLTV feed the DVR consumes. |
| `GET /api/priority`, `PUT /api/priority` | Read or change the source order. A write applies to the running merge immediately, rewrites the source list order in the YAML file atomically, and returns a persist status. It never re-seeds the registry. |
| `POST /api/config/reload` | Re-read the YAML configuration. Rebinds sources and order and re-merges, but leaves the registry frozen, so emitted ids never move. |
| `POST /api/config/dump` | Write the in-memory configuration, credentials included, to the log stream. Never returns it over HTTP. |
| `POST /api/registry/reseed` | Explicit re-seed: preview the emitted id changes, then apply them. The only way an id may change (ADR-0002). |
| `GET /api/channels`, `PUT /api/matches/{id}` | Registry view; confirm/correct a match. |
| `GET /api/sources` | The configured sources with their health. **Read-only**: sources are declared in YAML, never through the API (ADR-0005). |
| `GET /api/sources/status` | Per-source health, last refresh, last error, and what the slot rule dropped for that source in the last refresh (`dropped` split into `duplicateSlots`, `partialOverlaps`, `lostToHigherPriority`, §6.3). |
| `POST /api/sources/{id}/refresh` | Manual refresh. |
| `GET /api/preview/{canonicalChannelId}` | Merged timeline with the winning source per slot. |
| `GET /api/merge/{canonicalChannelId}?day=&from=&hours=&order=` | Per-provider merge breakdown for one channel over one window (§6.6). `order` overrides the applied priority, which is how the draft order is previewed; omitting it uses the applied order. A channel id no provider serves — not even as a pass-through channel — is a 404, because there is no breakdown to explain. |

These endpoints exist to serve the UI. They are not a supported JSON API.

## 10. Web UI (React)

Drag-to-reorder priority · channel auto-match review (confirm/correct) · registry view ·
per-source health, last refresh and what the slot rule dropped (§6.3) · manual refresh now · "reload config" · "dump config to log" ·
merged-guide preview per channel **showing the winning source per slot** · guide viewer ·
**merge preview** on the Priority page — one channel, providers in draft order, each entry's fate
(§6.6).

The merge preview is the second half of the priority panel: it is what makes a reorder's cost visible
before Apply order commits it. It is explicitly **not** a second merge engine — it calls the same
slot-claim walk as the live merge, and it changes nothing until the user applies.

There is **no source CRUD**: sources are declared in YAML and the UI may only change their order
(ADR-0005). Reordering warns that it changes which source is primary and may therefore change emitted
channel ids on a later re-seed, which can break DVR mappings (ADR-0002). The UI reports whether a
source has credentials, never the credentials themselves.

## 11. Testing and verification

- Fixtures in `src/test/resources`: the two real samples (`epg-hu.xml` from IPTV-EPG.org,
  `hungary1.xml` from open-epg.com) plus a **hand-made overlap fixture**.
- **Golden-file unit tests** on the merge engine: assert the exact expected XMLTV output. Brittle by
  design — the merge rules are the risky part and their exact output is the contract.
- An **`integrationTest` Gradle task** (required by `AGENTS.md`) that boots the real application on a
  random port, points its sources at a **stub HTTP server** serving the fixtures, and asserts the feed
  it serves against the same golden file. MongoDB-backed cases arrive with persistence (slice 4).
- Verification gate before any completion claim: `./gradlew test`, `./gradlew integrationTest`,
  and for UI changes `cd frontend && npx tsc --noEmit && npm run build` with `frontend/dist/*`
  copied into `src/main/resources/static/`.

## 12. Deployment and operations

No Docker anywhere. MongoDB runs as a **locally installed service**; HouseEPG runs as a **plain
jar**; the `integrationTest` suite runs against that local MongoDB and **must target a dedicated test
database** so it never touches real guide data.

## 13. Risks and accepted tradeoffs

| Risk / tradeoff | Note |
| --- | --- |
| Overlap drops the loser wholesale | Never invents start/stop times, never emits duplicate overlapping entries; holes are expected. ADR-0001. |
| Reordering priority can break DVR mappings | Accepted in exchange for "primary = top of the list". ADR-0002. |
| Folding quality tags can over-merge | If a provider genuinely lists `AMC` and `AMC (HD)` separately, the registry decides and the user's match review is the guard. ADR-0003. |
| No authentication anywhere | Anyone on the LAN can read the guide, reconfigure sources, reorder priority (changing emitted ids), and trigger fetches. Actuator is exposed on the same port too. ADR-0004. |
| Stale data preferred over missing data | A source down for days keeps contributing its last good guide at its priority. |
| Three storage layers cost space | Bought re-merge freedom without re-fetching. |
| Rich UI is a build cost | Accepted because the merge rules are otherwise invisible. |
| Golden-file tests are brittle | Accepted; the goldens are the contract. |
| `integrationTest` needs a local MongoDB | Environment-dependent; dedicated test database required. |
| Large public feeds | The one-country sample runs to thousands of lines; full re-fetch/re-parse may be wasteful and streaming parsing may be needed. |
| Sparse upstream metadata | One provider's sample carries only title and description, so a rich feed can lose to a poor one depending on priority order. |

## 14. Rollout

1. **Walking skeleton** — sources and priority configured in **YAML** (one M3U + two real XMLTV
   URLs): fetch → normalize → match → merge → serve the merged XMLTV at one endpoint. No UI, no
   MongoDB. Goal: the DVR shows a merged guide.
2. **Test harness** — fixtures, golden-XMLTV merge tests, the `integrationTest` Gradle task.
3. **Identity and matching** — canonical registry, normalization, auto-match, persisted match review.
4. **Persistence and refresh** — the three MongoDB layers, the scheduler, last-good/failure policy,
   retention.
5. **React UI** — the configuration and inspection surface in §10.

Each slice is one kanboard task and **one git branch**, named `feature/<ID>-<short-description>` (or `bugfix/<ID>-<short-description>`).

## 15. Deferred

- Streaming parsing for large feeds.
- Whether an M3U/lineup endpoint is ever needed.
- Whether a priority change should be guarded more strongly than a UI warning.
- `README.md` (setup and usage) — not yet written.

## 16. ADRs

- [ADR-0001 — Slot rule: overlap drops the loser](adr/0001-slot-rule-overlap-drops-the-loser.md)
- [ADR-0002 — Emitted channel id stability and re-seed](adr/0002-emitted-channel-id-stability-and-reseed.md)
- [ADR-0003 — Fold quality and region tags when matching](adr/0003-fold-quality-and-region-tags-when-matching.md)
- [ADR-0004 — Open LAN without authentication](adr/0004-open-lan-without-authentication.md)
