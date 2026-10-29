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
4. Lowercase, strip punctuation, collapse whitespace.

`AMC.hu` → `amc`; `AMC (HD).hu` → `amc`; `HU - Animal Planet` and `Animal Planet (HD).hu` → both
`animal planet`. See ADR-0003.

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

### 6.4 Emitted channel id and its stability

- The emitted XMLTV channel id is the **M3U `tvg-id`** when the playlist is the channel authority,
  otherwise the **primary XMLTV source's channel id**. HouseEPG **never invents ids**.
- Ids are **frozen against implicit drift**: a routine source refresh never changes an id, so the
  DVR's cached id→channel mapping survives. Concretely, the registry is seeded once and kept; a failed
  or empty playlist read reuses it instead of falling back to another source's ids (ADR-0002).
- Ids **can** change on a **deliberate re-seed** — changing which source sits at priority #1 —
  because "primary" is positional. The UI must warn that DVR mappings may need redoing. See ADR-0002.

## 7. Persistence

Three layers in MongoDB:

1. **Raw snapshot per source** — the last good payload. A re-merge never needs a re-fetch.
2. **Normalized programmes per source** — indexed by `(canonicalChannelId, start)` and by
   `(sourceId, canonicalChannelId)`.
3. **Materialized merged guide** — what `GET /xmltv` serves and what the UI previews.

Indexes: `programmes(canonicalChannelId, start)`, `programmes(sourceId, canonicalChannelId)`,
`programmes(stop)` for retention, `mergedProgrammes(canonicalChannelId, start)`,
`sourceChannels(sourceId, sourceChannelId)` unique, `canonicalChannels(id)` unique.

**Retention:** programmes that ended more than ~7 days ago are pruned. One raw snapshot per source is
kept (the last good one).

## 8. Refresh and failure policy

- Each source has its own **scheduled refresh interval** (default 6–12 h, configurable) plus a
  **manual "refresh now"**.
- On success: new raw snapshot → re-normalize → re-merge → re-materialize.
- On **failure, timeout, or empty/unparseable response**: the source keeps its **last good data**,
  is marked **unhealthy** in the UI, and the **served guide never degrades because of a network
  error**. Stale-but-rich beats fresh-but-missing here, deliberately.

## 9. HTTP surface

| Endpoint | Purpose |
| --- | --- |
| `GET /xmltv` | The merged XMLTV feed the DVR consumes. |
| `GET /api/sources`, `POST /api/sources`, `PUT /api/sources/{id}`, `DELETE /api/sources/{id}` | Source CRUD (used by the UI, not a public API product). |
| `PUT /api/priority` | Reorder sources (triggers re-seed + re-merge). |
| `GET /api/channels`, `PUT /api/matches/{id}` | Registry view; confirm/correct a match. |
| `GET /api/sources/status` | Per-source health, last refresh, last error. |
| `POST /api/sources/{id}/refresh` | Manual refresh. |
| `GET /api/preview/{canonicalChannelId}` | Merged timeline with the winning source per slot. |

These endpoints exist to serve the UI. They are not a supported JSON API.

## 10. Web UI (React)

Source CRUD (URL, credentials, refresh interval, enable/disable) · drag-to-reorder priority ·
channel auto-match review (confirm/correct) · registry view · per-source health and last refresh ·
manual refresh now · merged-guide preview per channel **showing the winning source per slot** ·
guide viewer.

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
