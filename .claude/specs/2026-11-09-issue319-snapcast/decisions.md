# Decisions: Snapcast support (#319)

## 2026-11-09 - Scope: target + controls first, client second, no linking, no server role
**Context**: Snapcast clients take their server from their own command line; the server has no method to move a client (`notes.md`, Discussion 3). The owner wants a maintained Android option (Snapdroid is stale) but not the dynamic linking.
**Decision**: Phase A = YAACC feeds a snapserver and controls it. Phase B = the phone as a client (plain Java protocol). YAACC is never a snapserver. Phase B starts only after Phase A is released and used (own gate).
**Rationale**: Phase A is small, useful alone and testable with the rig; Phase B carries the real risk (sync on Android) and gets kill criteria.

## 2026-11-09 - One receiver per Snapcast group
**Context**: UPnP receivers are devices; a Snapcast group is the unit that has one stream and a set of clients.
**Decision**: A group is the receiver row; clients are members with own volume. Group changes use `Group.SetClients` / `Group.SetStream`. A group that disappears (all clients gone) disappears from the list.
**Rationale**: owner's choice (recommended option).

## 2026-11-09 - Runtime stream creation
**Context**: The documentation says `Stream.AddStream` accepts only pipe, file, tcp, alsa, jack, meta (a security restriction), but no such check was found in the sources (`docs/tech.md`); until the rig shows otherwise assume the server accepts any scheme. YAACC only ever creates `tcp` sources and never takes a stream URI from server data.
**Decision**: YAACC creates a tcp stream per phone (name "YAACC <device name>") on demand and removes it when idle for a while; fallback = documented `snapserver.conf` line when the server answers an error.
**Rationale**: owner's choice; no manual server setup.

## 2026-11-09 - Neutral package name
**Context**: MusicCast rule: respect trademarks, nominative use only, neutral code names.
**Decision**: Package `de.yaacc.multiroom` (sub-packages `protocol`, `control`, `discovery`, `feed`, `client`); "Snapcast" appears only as the product name in strings, docs and a README notice. Open for veto: Snapcast is GPL software by its author, not a brand like Yamaha, so `de.yaacc.snapcast` is acceptable if the owner prefers.
**Rationale**: consistent with `de.yaacc.yxc`, cheap to change now, costly later.

## 2026-11-09 - Authentication out of scope
**Context**: Snapcast 0.35 can require authorization (Basic) for control and for the Hello message of clients.
**Decision**: Not supported in Phase A/B; a server that demands it gives a clear error row. Revisit when a user needs it.
**Rationale**: no credential storage design yet; keeps the first release small.
