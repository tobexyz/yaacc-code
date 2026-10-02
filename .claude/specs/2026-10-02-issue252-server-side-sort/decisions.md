# Decisions: Server-side sort support for yaacc's own UPnP ContentDirectory

## 2026-10-02 — New spec folder instead of resuming `2026-09-24-issue252-sort-by-date`

**Context**: this server-side sort gap was discovered while investigating
issue #252 (which was about yaacc as a UPnP *client*). The original spec
folder's tasks are all `[x]` except one `[!]` manual-verification task
explicitly deferred to the user — functionally complete.

**Decision**: started a new dated spec folder
(`2026-10-02-issue252-server-side-sort`) rather than adding groups to the
existing one, and pointed `currentspec.md` at it.

**Rationale**: client-side and server-side are genuinely separate code
paths, components, and risk surfaces (see `requirements.md` Non-Goals);
conflating them in one spec folder would make `decisions.md`/`review.md`
history harder to follow for either. Both specs stay linked by name
(`issue252-*`) and both live on the same `feat/issue252` branch.
