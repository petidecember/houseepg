# ADR-0004 — Open LAN without authentication

**Status:** accepted
**Date:** 2026-10-29
**Deciders:** user + agent (grill session)
**Related:** [design §2, §9, §12](../design.md), ADR-0002

## Context

HouseEPG serves two surfaces on the LAN:

- the **XMLTV feed**, consumed by a DVR client on a schedule, and
- the **React UI**, which can reconfigure sources, reorder priority (thereby changing emitted channel
  ids — see ADR-0002) and trigger fetches.

The question is what protects them. The deployment target is a single home LAN, never exposed to the
internet.

Two facts push toward simplicity:

- DVR clients handle credentials badly. Many accept only a plain URL for the EPG source, so an
  authenticated feed is often not consumable at all.
- The user is the only operator, on a network they control.

One fact pushes the other way: the UI is not read-only. Anyone who can reach it can break every DVR's
channel mapping.

## Options considered

1. **Fully open.** No authentication on the feed or the UI, no interface-binding configuration.
2. **Feed open, UI behind a shared password/token**, with the server bound to a configurable
   interface.
3. **Feed open to an IP allowlist**, UI password-protected.
4. **Auth on both**, accepting that the DVR must be able to send credentials.

## Decision

**Option 1.** Everything is open on the trusted home LAN: no authentication on the XMLTV feed or on
the UI, and no interface-binding configuration. Simplicity is the priority.

## Consequences

### Positive

- Any DVR client can consume the feed with nothing but a URL, which is the point of the project.
- No credential storage, no login flow, no session handling, no password reset path — a smaller
  surface and less code to get wrong.
- Setup is a single URL in the DVR and a single URL in a browser.

### Negative

- Anyone on the LAN — and anything that can reach the host — can read the guide, reconfigure sources,
  reorder priority (which can change emitted ids and break DVR mappings), and trigger fetches.
- **Actuator endpoints are exposed on the same port and are equally unauthenticated**, so health and
  configuration internals are readable too.
- There is no audit trail of who changed what.
- If the host is ever put on an untrusted network, or a port-forward is added, the UI becomes a
  remote takeover surface. Nothing in the application prevents that.

### Neutral

- The UI warning on priority changes (ADR-0002) remains the only guard against self-inflicted
  breakage, and it guards against mistakes, not against hostile access.
