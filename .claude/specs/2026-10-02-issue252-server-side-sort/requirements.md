# Server-side sort support for yaacc's own UPnP ContentDirectory (issue #252 follow-up)

## Problem Statement

Issue #252's original scope made yaacc, acting as a UPnP **client**, able
to sort folders it browses on other DLNA servers (by name or date,
ascending/descending). That work is complete and merged on this branch.

Investigating the same codebase revealed that yaacc's own **server**
(`YaaccContentDirectory`, used when yaacc shares local media to other
DLNA renderers/control points) has the identical gap on the other side
of the protocol: it declares the `Browse` action's `SortCriteria`
input, parses and validates it, but **silently ignores it** everywhere —
every one of its 20 browser implementations hardcodes its own fixed
order (e.g. always `DISPLAY_NAME ASC` for music titles) regardless of
what the requesting client asked for. It also advertises an **empty**
`SortCaps` list via `GetSortCapabilities`, telling clients it supports no
sort criteria at all, which is the accurate (if unhelpful) current state.

Other UPnP control points/renderers that send a `SortCriteria` — exactly
the same kind of client-side sort UI issue #252 just added to yaacc
itself — currently get back results in yaacc's fixed order no matter
what they asked for, without any error indicating the request wasn't
honored.

## Goals

- `YaaccContentDirectory.getSortCapabilities()` advertises the sort
  properties the service actually honors (at minimum `dc:title` and
  `dc:date`) instead of an empty list.
- A `Browse` request's `SortCriteria` (when present and valid) actually
  determines the order of returned containers/items for `dc:title` and
  `dc:date`, ascending or descending, consistent with how issue #252's
  client-side feature already treats these two properties.
- Content types that don't yet expose a date value as `dc:date` get one
  wired up where the underlying data is already available cheaply
  (images: `DATE_TAKEN` is already queried but never attached to the
  DIDL item — just unwire the gap; video: add `DATE_ADDED` to the
  existing MediaStore projection; SAF files: use
  `DocumentFile.lastModified()`). Music already exposes `dc:date` via
  `YEAR` — no change needed there beyond honoring it as a sort key.
- Existing default/fixed order is unchanged when `SortCriteria` is
  absent or empty — this is purely additive behavior gated on the client
  actually requesting a sort.
- Existing `UNSUPPORTED_SORT_CRITERIA` rejection behavior for
  syntactically invalid `SortCriteria` strings is unchanged.
- A `SortCriteria` request for a property this service does not support
  (anything other than `dc:title`/`dc:date`) is rejected with
  `UNSUPPORTED_SORT_CRITERIA`, matching what an empty-then-populated
  `SortCaps` list implies to a well-behaved client, instead of being
  silently accepted and ignored as today.

## Non-Goals

- No change to issue #252's client-side feature
  (`BrowseContentItemAdapter`/`BrowseItemLoadTask`/`ContentListFragment`)
  — that work is already complete and out of scope here.
- No UI changes — this is a protocol-level/server-side change only;
  yaacc's own app has no UI for configuring or observing how its local
  server responds to other clients' sort requests, and none is being
  added.
- No support for sort properties beyond `dc:title`/`dc:date` (e.g.
  `upnp:genre`, `upnp:artist`) — matches the scope of what issue #252's
  client side already supports conceptually (name/date only).
- No change to pagination (`StartingIndex`/`RequestedCount`) semantics
  or to the `isUsingTestContent()` synthetic-content code path's
  behavior beyond what's needed to keep it compiling (test content is a
  small fixed in-memory set used only for manual/dev testing, not a
  real-world browsing path).
- Does not add new MediaStore permissions or new data sources — only
  reads columns already reachable from each browser's existing
  query/listing call.

## Success Metrics

- A third-party DLNA control point (or yaacc's own client code, pointed
  at yaacc's own server in a loopback test) that sends
  `SortCriteria="-dc:date"` or `"+dc:title"` receives results in that
  order for music, images, video, and SAF-backed folders.
- `GetSortCapabilities` returns a non-empty `dc:title,dc:date` (or
  equivalent) list.
- A `SortCriteria` for an unsupported property is rejected with
  `UNSUPPORTED_SORT_CRITERIA`, not silently accepted.
- All existing unit tests continue to pass; new unit tests cover the
  sort-comparator/sort-order-building logic directly (Robolectric/full
  MediaStore cursor integration is out of reach for plain-JVM unit tests
  in this project, per the existing testing precedent in
  `2026-09-24-issue252-sort-by-date/decisions.md` — the MediaStore query
  construction itself is covered by code review and, where practical,
  by isolating the "build a sortOrder string from SortCriterion[]"
  logic into a small testable helper rather than testing the live
  `ContentResolver.query` call).

## Constraints

- Must build on the existing `ContentBrowser` abstract class and its 20
  concrete subclasses without a disruptive redesign — add a shared
  helper (e.g. a static utility to turn `SortCriterion[]` into a
  MediaStore `sortOrder` string, and a `Comparator<DIDLObject>` for the
  non-cursor-backed paths) rather than duplicating sort logic in each
  subclass.
- MediaStore-cursor-backed browsers (music/image/video) should push the
  sort into the existing `ContentResolver.query(...)` call's `sortOrder`
  argument (cheapest, avoids reading the whole cursor into memory just
  to re-sort it) — see `docs/tech.md` confirmation that this is already
  the mechanism each browser uses for its current hardcoded order.
- SAF-backed and synthetic/folder-container browsers have no cursor to
  push a `sortOrder` into — these need an in-memory
  `Comparator`-based sort after listing.
- Reuse the already-verified `SortCriterion.valueOf`/`.isAscending()`/
  `.getPropertyName()` API (see `docs/tech.md` server-side section) —
  do not reinvent parsing.
- Keep `YaaccContentDirectory.browse()`'s existing
  `SortCriterion.valueOf(orderBy)` try/catch →
  `UNSUPPORTED_SORT_CRITERIA` behavior for malformed syntax; add the
  new "requested property not in SortCaps" rejection alongside it, not
  instead of it.
- Non-functional: this runs on every `Browse` request the local server
  receives, including from yaacc's own client when Browse Server is
  used locally — avoid introducing N+1 query patterns or materializing
  entire large collections in memory where the existing code already
  streams/pages via cursor windowing.
