# Security Review: Server-side sort support for yaacc's own UPnP ContentDirectory

## Cycle 1 — 2026-10-02
Reviewing: Group 1 (`SortSupport.java` new, `YaaccContentDirectory.java` diff at commit `4ef1ac2`)

Scope: pure-JDK `SortSupport` utility plus the two-line wiring into
`YaaccContentDirectory` (`sortCapabilities` population +
`SortSupport.validateSupported(orderByCriteria)` call). Per the commit
message and `design.md`, no browser yet calls
`toMediaStoreSortOrder`/`toComparator` with real MediaStore
column/accessor maps — that is Group 2. This review evaluates whether
Group 1's API shape structurally prevents the SQL-injection-adjacent
risk once Group 2 wires it up, plus the other requested angles.

### Critical
None.

### Warning
None.

### Suggestion

- **`SortSupport.java:110-129` (`toMediaStoreSortOrder`) — SQLi-adjacent risk assessment: structurally sound, but make the constraint explicit for Group 2's reviewer gate.**
  `toMediaStoreSortOrder` only ever appends two kinds of strings to the
  returned `sortOrder`: (a) `propertyToColumn.get(criterion.getPropertyName())`
  — a `Map.get()` lookup where the client's raw property string is used
  strictly as a *key*, never concatenated into the output — and (b) the
  literal constants `" ASC"`/`" DESC"` chosen from
  `criterion.isAscending()` (a boolean, not client-controlled text).
  The client-supplied `SortCriteria` string never reaches the
  `sortOrder` output itself; only server-chosen map *values* do. This
  is the right shape to prevent SQL injection into the
  `ContentResolver.query(..., sortOrder)` argument, confirming the
  concern this review was asked to check. Same reasoning applies to
  `toComparator` (`Map<String, Function<DIDLObject,String>>` —
  property name is a lookup key only, never evaluated/executed).
  Residual risk is entirely in Group 2: the design calls for each
  browser's `propertyToColumn`/`propertyToAccessor` map to be a fixed,
  compile-time literal (`Map.of(PROPERTY_TITLE, MediaStore.X.TITLE,
  ...)`), per `design.md`'s per-browser table. If a future change ever
  builds that map dynamically from request data (it does not today),
  the safety property breaks. Recommend the Group 2 reviewer/security
  gate explicitly re-verify, for every browser touched, that the
  `propertyToColumn`/`propertyToAccessor` map passed to `SortSupport`
  is a fixed literal and that no raw `orderBy`/`SortCriteria` string or
  `criterion.getPropertyName()` value is ever concatenated directly
  into a `sortOrder` string, `selection` string, or raw SQL anywhere in
  the new browser code — not just routed through `SortSupport`. This is
  a verification item for the next cycle, not a defect in Group 1.

- **`SortSupport.java:81-92` (`validateSupported`) — no upper bound on number of sort criteria.**
  `SortCriterion.valueOf` (pre-existing, vendored Cling code,
  `org.fourthline.cling.support.model.SortCriterion.java:48-56`) splits
  the client's raw `SortCriteria` string on `,` with no limit on count
  or string length, and `validateSupported` then does a full `O(n)`
  pass over every parsed criterion (each a `O(1)` `HashSet.contains`)
  without rejecting a pathologically long list — e.g.
  `"+dc:title,+dc:title,...` repeated tens of thousands of times would
  still pass validation (every entry is individually supported, just
  redundant) and is in no way short-circuited. This isn't exploitable
  yet in Group 1 (nothing downstream currently consumes the resulting
  `SortCriterion[]` for real work — no `toMediaStoreSortOrder`/
  `toComparator` call sites exist outside unit tests), so it's not a
  live vulnerability today. It will become one once Group 2 wires
  `toMediaStoreSortOrder` (bloated `ORDER BY` string handed to
  `ContentResolver.query`) and `toComparator` (an `O(n)`-deep
  `thenComparing` chain evaluated per comparison during a sort of
  potentially large in-memory lists) to real browsers. Recommend adding
  a small cap (e.g. reject with `UNSUPPORTED_SORT_CRITERIA` beyond
  ~8–10 criteria — there are only 2 supported properties, so anything
  beyond a handful is necessarily redundant) either in
  `validateSupported` now or as an explicit Group 2 task before the
  real wiring lands. Low severity: this is a self-inflicted
  CPU/string-size cost on a single request, not amplification,
  disclosure, or state corruption, and is bounded by the underlying
  HTTP/SOAP request-size limits of the embedded server (out of this
  diff's scope).

- **`YaaccContentDirectory.java:319-323` (pre-existing, unchanged by this diff) — `ex.toString()` echoed into the SOAP fault for malformed `SortCriteria`.**
  Not introduced by Group 1 (confirmed via `git show 4ef1ac2` — this
  catch block predates the diff), but it sits directly adjacent to the
  new `SortSupport.validateSupported` call or so flagging for
  completeness per the review scope. `SortCriterion.valueOf`'s
  `IllegalArgumentException` message is just `"Missing sort prefix
  +/- on criterion: " + criterion` (the client's own malformed input
  echoed back) and `ex.toString()` prepends only the exception's
  fully-qualified class name — no stack trace, no file path, no
  internal server state. This is standard, low-risk "here's what was
  wrong with your own request" feedback, not an information-disclosure
  issue. No action required; noted only because it's on the same code
  path this cycle was asked to check.

- **`SortSupport.java:87-90` (new `validateSupported` exception message) — confirmed safe.**
  `"Unsupported sort property: " + criterion.getPropertyName()` only
  ever echoes back a property name the requesting client itself
  supplied in its own `SortCriteria` string. No internal state, column
  name, file path, or stack trace is included. Matches requirement #2
  of this review — no leak.

- **`SortSupportTest.java` — no masking of adversarial-input behavior.**
  Reviewed for whether any test silently swallows an exception in a
  way that would hide a real vulnerability. All exception-path tests
  (`validateSupportedThrowsForUnsupportedProperty`,
  `validateSupportedThrowsIfAnyCriterionUnsupported`) use
  `fail("expected ContentDirectoryException")` in the success path and
  only catch the specific expected exception type, asserting its error
  code — a bug that caused `validateSupported` to stop throwing (e.g.
  fail open) would fail these tests, not be masked by them. No
  `catch (Exception e) {}`/empty-catch patterns anywhere in the test
  file. The allowlist (`SUPPORTED_PROPERTIES`) check is exact-string,
  case-sensitive, so property names with SQL-meta characters, mixed
  case, or empty string (e.g. a bare `"+"` criterion, which parses to
  `propertyName == ""`) all fail closed into
  `UNSUPPORTED_SORT_CRITERIA` by construction of the `Set.contains`
  check, even though there's no dedicated test case for the
  empty-property-name edge case. Recommend adding one explicit test
  (`validateSupportedThrowsForEmptyPropertyName`) in a future cycle for
  documentation/regression value — not a defect, just a coverage gap
  worth closing before Group 2 builds on top of this allowlist.

### Verdict: PASS

## Cycle 1 — 2026-10-02
Reviewing: Groups 1-N (Group 2 — commits `c14950a`, `e40d279` — plus Fix Group 1 —
commits `ac9d92b`, `0115105` — all on top of Group 1, already PASSed above).
This is Group 2's first security-review cycle; the general reviewer's gate
(`review.md`) already reached PASS at its Cycle 2, after a Cycle 1 FAIL for a
`HashMap`-ordering defect and two Warnings (SAF full-enumeration cost,
missing `DATE_TAKEN` wiring) that Fix Group 1 resolved. This cycle re-verifies
Group 1's own Cycle 1 Suggestions now that live wiring exists, and covers the
six angles requested: SQL injection, SAF path/access scoping, resource
exhaustion, the SAF duplicate comparator, information disclosure, and the
accepted pagination trade-off from a DoS angle.

### Critical
None.

### Warning

- **`SortSupport.java:81-92` (`validateSupported`, unchanged since Group 1) /
  `SafFolderBrowser.java:116-144` (`buildAccessorComparator`) /
  `SortSupport.java:140-168` (`toComparator`) /
  `SortSupport.java:110-129` (`toMediaStoreSortOrder`) — the uncapped
  `SortCriteria` count flagged as a non-blocking Suggestion in Group 1's
  Cycle 1 is now live, reachable, and genuinely exploitable — elevating it
  to Warning as that review explicitly asked this cycle to reassess.**
  `org.fourthline.cling.support.model.SortCriterion.valueOf` (vendored,
  `yaacc/src/main/java/org/fourthline/cling/support/model/SortCriterion.java:48-56`)
  splits the client's raw `SortCriteria` string on `,` with no cap on count
  or length, and `validateSupported` never rejects duplicates — only
  property name, so `"+dc:title,+dc:date,+dc:title,+dc:date,..."` repeated
  many thousands of times is 100% valid (every entry is individually one of
  the two supported properties) and passes straight through. Grepped the
  embedded UPnP HTTP/SOAP handling
  (`YaaccUpnpServerContentHttpHandler.java`, `YaaccUpnpServerProtocolRequestHandler.java`)
  for any request-body size cap — found none, so nothing in this codebase
  bounds how large that comma-separated string can be before it even reaches
  `SortCriterion.valueOf`.
  - **Live cost, traced end-to-end for both consumers now wired up by this
    group**:
    - `toComparator`/`buildAccessorComparator` build one `Comparator` per
      criterion (duplicates included — nothing de-dupes) and chain them with
      `thenComparing`. For an `N`-criterion `SortCriteria` string, the
      resulting chain is `N` deep, and `thenComparing` evaluates lazily
      left-to-right per *comparison*, so every pairwise comparison during the
      sort costs `O(N)` instead of `O(1)`. `SafFolderBrowser` is the
      consumer most exposed to this: its lightweight `candidates`/
      `allFolders` lists are **not** the "small fixed list" case (unlike
      `RootFolderBrowser`/`LiveStreamFolderBrowser`/`MusicFolderBrowser`/
      `ImagesFolderBrowser`, whose `toComparator` calls sort a handful of
      synthetic entries) — a real SAF-mounted folder can hold thousands of
      files, and Fix Group 1's own lazy-pagination fix (this group) is
      explicitly built around the premise that "you cannot sort a slice,"
      so the *entire* folder is enumerated and sorted on every single
      `Browse` call, before any page slicing happens. A folder of `m` files
      sorted with an attacker-chosen `N`-deep comparator chain costs
      `O(m·log(m)·N)` comparisons — attacker effort to construct the
      `SortCriteria` string scales only linearly with `N` (a few hundred KB
      of repeated `"+dc:title,"`/`"-dc:date,"` text), while the resulting
      CPU cost on what is typically a mobile/resource-constrained Android
      device scales with `N` **and** the uncontrolled `m`. This is a
      real, disproportionate-cost DoS vector, not a theoretical one, and
      it is reachable by **any unauthenticated device on the LAN** per this
      spec's own stated threat model.
    - `toMediaStoreSortOrder` (consumed by all the MediaStore-backed
      browsers — music/image/video) builds a `sortOrder` string by
      appending one `"<column> ASC/DESC"` clause per criterion (again, no
      de-dup), so the same `N`-criterion attack produces a `sortOrder`
      string of a few hundred KB handed straight to
      `ContentResolver.query(..., sortOrder)` → Android's SQLite layer,
      which must parse and plan an `ORDER BY` clause with `N` (duplicate)
      terms on every request. Lower severity than the `SafFolderBrowser`
      case (no `O(m·N)` compounding — this is "merely" a large string
      parse/compile cost per query) but still an attacker-controlled,
      effectively unbounded cost with no server-side cap.
  - **Why this was correctly just a Suggestion in Group 1 and should not
    stay one here**: Group 1's Cycle 1 review explicitly reasoned "nothing
    downstream currently consumes the resulting `SortCriterion[]` for real
    work — no `toMediaStoreSortOrder`/`toComparator` call sites exist
    outside unit tests" and flagged this exact re-assessment as the
    trigger condition for escalation. That condition is now met: Group 2 +
    Fix Group 1 wired both methods into all 20 browsers, including one
    (`SafFolderBrowser`) whose per-request cost is attacker-amplifiable via
    `m` (the folder size), not just `N`.
  - **Fix** (low cost, matches Group 1's own recommendation): reject
    `SortCriteria` beyond a small cap (e.g. 8-10 criteria is generous given
    there are only 2 supported properties — anything beyond a handful is
    necessarily redundant) in `SortSupport.validateSupported`, returning
    `UNSUPPORTED_SORT_CRITERIA` the same way an unsupported property name
    is rejected today. This is a single, centrally-located check (one
    `orderby.length > CAP` guard) that closes the gap for every one of the
    20 browsers at once, since all of them funnel through `validateSupported`
    before any browser-level sort call runs.

### Suggestion

- **SQL injection / query-string construction (requested angle #1) —
  verified clean across all 20 browsers.** Grepped and read every
  `columnMap`/`accessorMap` passed to `SortSupport.toMediaStoreSortOrder`/
  `toComparator` (`ImagesAllFolderBrowser`, `ImagesByBucketNameFolderBrowser`,
  `ImagesByBucketNamesFolderBrowser`, `ImagesFolderBrowser`,
  `MusicAlbumFolderBrowser`, `MusicAlbumsFolderBrowser`,
  `MusicAllTitlesFolderBrowser`, `MusicArtistFolderBrowser`,
  `MusicArtistsFolderBrowser`, `MusicFolderBrowser`, `MusicGenreFolderBrowser`,
  `MusicGenresFolderBrowser`, `VideosFolderBrowser`, `LiveStreamFolderBrowser`,
  `RootFolderBrowser`, plus `SafFolderBrowser`'s `CONTAINER_ACCESSOR_MAP`/
  `ENTRY_ACCESSOR_MAP`). Every one is built from compile-time-literal
  `SortSupport.PROPERTY_TITLE`/`PROPERTY_DATE` keys mapped to compile-time
  `MediaStore.*` column constants or fixed method references
  (`DIDLObject::getTitle`, `entry -> entry.file.getName()`, etc.) — never
  built from, or including any part of, the client-supplied `SortCriteria`
  string. The item-only browsers (`ImageAllItemBrowser`,
  `ImageByBucketNameItemBrowser`, `VideoItemBrowser`,
  `MusicAlbumItemBrowser`/`MusicArtistItemBrowser`/`MusicGenreItemBrowser`/
  `MusicAllTitleItemBrowser`) never call either `SortSupport` method at all
  (single-row `_ID=?` lookups have nothing to sort), confirmed by grep.
  Separately grepped every `String selection = ...` assignment across the
  package: all are fixed column-name + `"=?"`/`"and (...)"` templates with
  values bound via `selectionArgs` (parameterized), none reference `orderby`,
  `SortCriteria`, or `criterion.getPropertyName()`. The client's raw
  `SortCriteria` string never reaches a `sortOrder` string, `selection`
  string, or raw SQL anywhere in Group 2/Fix Group 1's code, only ever a
  `Map.get()` lookup key (confirmed in `SortSupport.java:119`,
  `SortSupport.java:149`, and `SafFolderBrowser.java:125` identically). No
  finding — closes out the verification item Group 1's Cycle 1 asked for.

- **SAF file access / path traversal (requested angle #2) — scoping
  unchanged by the Fix Group 1 restructuring.** Read `SafFolderBrowser.java`
  in full against the pre-Group-2 baseline (`git show c14950a^:...`). Root
  listing (`browseContainer`/`browseItem` for `ContentDirectoryIDs.SAF_FOLDER`)
  still sources its candidate set exclusively from
  `getSelectedSafPathes()` — the same user-granted SAF tree URI set used
  before this spec, untouched by either the lightweight-pass split or the
  lazy-pagination change; the new `SafFileEntry`/lightweight pass only adds
  a cheap `DocumentFile.fromSingleUri`/name/`lastModified()` read per
  already-selected path, it does not discover or enumerate any path outside
  that set. Subfolder listing still resolves its root exclusively via
  `SAFCacheManager.getInstance(getContext()).getUriForShortId(shortId)` —
  the same short-ID-to-URI cache populated only when a folder was
  previously listed from an already-permitted parent
  (`getOrCreateShortId(...)` calls at `SafFolderBrowser.java:223`/`282`,
  both inside the existing, unchanged "only reachable from an already-
  enumerated parent" control flow) — then calls `root.listFiles()`, exactly
  as the pre-Group-2 code did; the lightweight pass changes *when*
  `createItem`'s expensive pipeline runs (deferred to the post-sort page
  slice) but not *what* is enumerated or under what access check. The one
  explicit permission gate in this file (`!entry.file.canRead()` /
  `!testAccess.canRead()` → `restricted`/`"[X] "` prefix, confirmed still
  applied identically at `SafFolderBrowser.java:284-288` and passed through
  to `createItem(..., restricted)` at `:352`/`:408`) is unchanged by the
  restructuring — it is computed per-entry at the same point in the flow as
  before, just now potentially on a sorted-then-sliced list rather than a
  raw-sliced one, which does not change which entries are checked, only
  their order. No widening of enumerated/accessible content found.

- **SAF lightweight-pass duplicate comparator logic (requested angle #4) —
  confirmed it cannot diverge from `SortSupport`'s allowlist.**
  `SafFolderBrowser.browseContainer`/`browseItem` only ever call
  `buildAccessorComparator`/`SortSupport.toComparator` with the `orderby`
  array that already passed `SortSupport.validateSupported(...)` inside
  `YaaccContentDirectory.browse(...)` *before* any browser is invoked — a
  `SortCriterion` whose property isn't `dc:title`/`dc:date` never reaches
  this file at all; it is rejected with `UNSUPPORTED_SORT_CRITERIA` at the
  top level. On top of that defense-in-depth, both `ENTRY_ACCESSOR_MAP`
  (`SafFolderBrowser.java:94-99`) and `CONTAINER_ACCESSOR_MAP`
  (`SafFolderBrowser.java:59-60`) are fixed two/one-entry `Map.of(...)`
  literals keyed by the same `SortSupport.PROPERTY_TITLE`/`PROPERTY_DATE`
  constants, and `buildAccessorComparator`'s `propertyToAccessor.get(...)`
  lookup silently `continue`s (skips, same fail-safe behavior as
  `SortSupport.toComparator`) for anything not in that map — there is no
  code path by which client input selects an accessor function or reaches
  unvalidated. Line-by-line comparison against `SortSupport.toComparator`
  (same null/empty short-circuit, same per-criterion skip-if-unmapped, same
  `nullsFirst(naturalOrder())` + `.reversed()` direction handling, same
  `thenComparing` chaining) also confirms no behavioral drift beyond the
  stated reason (generic `<T>` vs. hard-typed `DIDLObject`, needed because
  this pass runs before `createItem` produces a real `DIDLObject`). No
  finding.

- **Information disclosure (requested angle #5) — no new client-facing
  leak found; one pre-existing, unchanged path re-confirmed, plus a
  local-only logging note.** Re-confirmed the `ex.toString()` echo at
  `YaaccContentDirectory.java:321`/`:335` is byte-for-byte unchanged from
  Group 1 (still the only place an exception message reaches the SOAP
  fault) — Group 2/Fix Group 1 added no new `catch`-and-echo-to-client
  pattern anywhere in the 20 touched browsers; every new `catch (Exception
  e)` in this group's diff (e.g. `SafFolderBrowser.java:294-296`,
  `createPhoto`'s album-art-URI catch at `ContentBrowser.java:355-357`)
  either logs locally via `YaaccLogger` or silently no-ops, and does not
  rethrow in a way that reaches `browse()`'s catch-all. The new
  `createPhoto(..., Long dateTaken)` overload (`ContentBrowser.java:369-377`)
  only ever formats a `Long` the caller already queried from MediaStore
  through the existing `SortSupport.formatEpochMillisAsDate` helper — no
  new string surface. Separately, this group (particularly Fix Group 1's
  `SafFolderBrowser` restructuring) added a large volume of `YaaccLogger.d`
  debug-level logging that includes full SAF content/tree URIs and resolved
  file paths (e.g. `SafFolderBrowser.java:250`, `:469`) — these go to the
  local Android log (`adb logcat`)/app logs only, never into any SOAP
  response or network-facing payload, so this is **not** disclosure to the
  unauthenticated network client this review's threat model is about; flagging
  only as a low-severity hardening note (full SAF URIs in verbose local
  debug logs are more detail than most apps log, worth trimming to IDs/short-IDs
  in a future pass if these logs are ever bundled into a shared bug report) —
  not blocking.

- **Pagination trade-off DoS angle (requested angle #6) — no new
  amplification or resource-exhaustion vector found.** The accepted
  trade-off (`review.md`'s Cycle 2 Suggestion: a page can return fewer than
  `maxResults` items when `createItem` fails on a page-boundary file) was
  already traced by the general reviewer to produce, at worst, a
  **duplicate** item on the next page (`start` never skips past unprocessed
  candidates), not data loss or an infinite/growing-cost loop — re-verified
  that reasoning holds from a DoS angle specifically: a client re-requesting
  the same page or paginating through a whole folder does the same bounded
  per-page work each time (lightweight sort of the already-materialized,
  already-sorted `candidates` list is `O(m log m)` once, reused in-memory
  only within a single `Browse` call — there is no caching across calls, so
  no call does *more* work than today's full-enumeration-then-slice model
  already does; it's the same cost shape as the Warning above, not an
  additional one). A malicious client issuing very many `Browse` requests
  each re-triggers the same `O(m log m · N)` cost already captured by the
  Warning above — this trade-off itself adds no new multiplier, amplification
  of response size, or distinct exhaustion mechanism on top of that. No
  finding beyond the existing Warning.

### Verdict: FAIL

## Cycle 2 — 2026-10-02
Reviewing: Group 2 (commits `c14950a`, `e40d279`) + Fix Group 1 (`ac9d92b`,
`0115105`) + Fix Group 2 (`cc76074`), all already PASSed/reviewed above except
`cc76074`, which is this cycle's subject — the fix for Cycle 1's sole Warning
(unbounded `SortCriteria` count enabling resource exhaustion via
`SafFolderBrowser`'s whole-folder comparator chain).

### Critical
None.

### Warning
None.

### Suggestion

- **Fix verified: `MAX_SORT_CRITERIA = 8` cap enforced before any
  downstream processing — `SortSupport.java:76,96-113` closes the Cycle 1
  Warning.** Read `validateSupported` in full
  (`SortSupport.java:96-113`): the `orderby.length > MAX_SORT_CRITERIA`
  check is the very first statement after the `orderby == null` no-op
  guard, and it throws `ContentDirectoryException(UNSUPPORTED_SORT_CRITERIA,
  ...)` before the `for` loop that does per-criterion property-name
  validation even begins — so an oversized array is rejected in `O(1)`
  (an array-length read) without ever touching
  `SUPPORTED_PROPERTIES.contains(...)`, let alone reaching
  `toComparator`/`toMediaStoreSortOrder`. Traced the call site:
  `YaaccContentDirectory.java:307-337` (the sole live `@UpnpAction`-annotated
  `browse()` — see "second entry point" check below) parses `orderBy` into
  `orderByCriteria` (line 318), then calls
  `SortSupport.validateSupported(orderByCriteria)` at line 324, **before**
  the dispatch to the per-browser `browse(objectId, browseFlag, filter,
  firstResult, maxResults, orderByCriteria)` overload at line 327-329 that
  eventually reaches `SafFolderBrowser.buildAccessorComparator`/
  `SortSupport.toComparator`/`toMediaStoreSortOrder` in any of the 20
  browsers. There is no code path from the network-facing `SortCriteria`
  string to any browser's sort logic that does not first pass through this
  single `validateSupported` call — confirmed by the existing Cycle 1
  Suggestion finding (re-verified unchanged) that all 20 browsers are
  dispatched from this one `browse()` method. The whole-folder comparator
  chain this Warning was about can therefore never be built from an
  oversized `SortCriterion[]`; the request is rejected before
  `SafFolderBrowser` (or any other browser) is ever invoked.

- **Cap value (8) confirmed generous, not a functional regression.**
  Cross-checked against this spec's own `requirements.md`/`design.md`:
  `SUPPORTED_PROPERTIES` is exactly `{dc:title, dc:date}` (`SortSupport.java:62-63`,
  unchanged) — the entire legitimate multi-key sort space is at most 2
  distinct, non-redundant criteria (e.g. `"+dc:title,-dc:date"` as a
  tiebreak order). `design.md:36-40`'s own design-alternatives section
  explicitly reasons about supporting `SortCriterion[]` "in array order,
  first wins, matching normal multi-key sort semantics" — it never
  contemplates more than the 2 supported properties, and nothing in
  `requirements.md`'s acceptance criteria (`requirements.md:78-83`) or any
  UPnP control point behavior documented in this spec needs more than a
  2-key sort. A cap of 8 is 4x the realistic maximum and cannot reject any
  legitimate request a real DLNA control point would send.

- **No other unbounded multiplier left; `m` (folder size) is correctly
  out of scope, as a separate pre-existing cost floor.** With `N` (criteria
  count) now capped at 8, the `SafFolderBrowser` cost is
  `O(m·log(m)·8)` — a constant factor, not an attacker-controlled one.
  `m` (the number of files in a SAF-mounted folder) remains unbounded, but
  this is correctly out of this Warning's scope: Fix Group 1's own
  lazy-pagination design (`review.md`'s Cycle 2 discussion, reconfirmed by
  reading `SafFolderBrowser.java` again this cycle) already requires a
  full `O(m·log(m))` sort of the lightweight candidate list on every
  `Browse` call **even with zero `SortCriteria`** (any `Browse` of a SAF
  folder needs a deterministic, stable order for pagination to be
  coherent across successive `start`/`count` calls) — that single-pass
  sort cost is the pre-spec/pre-fix floor for a plain `Browse` with no
  sort criteria at all, not something this spec introduced or something
  the criteria-count cap could or should reduce further. Grepped for any
  other place a client-controlled count/length feeds into a comparator
  chain depth, `ORDER BY` clause length, or loop bound — none found beyond
  the two consumers (`toComparator`, `toMediaStoreSortOrder`) already
  covered by the single `validateSupported` choke point. No residual
  amplification vector from the `SortCriteria` angle.

- **`SortSupportTest.java`'s new cases are correct and exercise the real
  method — no mocking.** `validateSupportedPassesAtMaxCriteria`
  (`SortSupportTest.java:93-96`) and
  `validateSupportedThrowsAboveMaxCriteria` (`:98-107`) both call
  `SortSupport.validateSupported(...)` directly — a `public static` method
  on a concrete final class, not an interface or mockable seam — with real
  `SortCriterion` instances built from `criteria(int count)` (`:114-121`),
  which constructs `new SortCriterion(true, PROPERTY_TITLE/PROPERTY_DATE)`
  alternately so every entry is independently valid against the property
  allowlist, isolating the length cap as the only thing under test (no
  conflation with the unsupported-property path). `MAX_SORT_CRITERIA` is
  `static` (package-private, not `private`), so the test — in the same
  `de.yaacc.upnp.server.contentdirectory` package — reads the real
  constant rather than hardcoding `8`, meaning the test stays correct if
  the constant is ever retuned. No `Mockito`/test-double usage anywhere in
  this file (confirmed by import list: `org.junit.Test` only, no mocking
  framework imports). Full suite run this cycle:
  `./gradlew :yaacc:testDebugUnitTest --rerun-tasks` → `BUILD SUCCESSFUL`,
  24 tasks executed; `SortSupportTest`'s own JUnit XML report
  (`yaacc/build/test-results/testDebugUnitTest/TEST-de.yaacc.upnp.server.contentdirectory.SortSupportTest.xml`)
  confirms `tests="22" skipped="0" failures="0" errors="0"` — the two new
  cases plus all prior ones green, consistent with real execution (not a
  stale/cached report, since `--rerun-tasks` forces re-execution).

- **Full-spec final sweep (Group 1 + Group 2 + Fix Group 1 + Fix Group
  2) — one dead-code entry point checked and ruled out; nothing else
  found beyond what Cycle 1 already covered.** Grepped every
  `SortCriterion.valueOf`/`SortCriterion[]` usage across `yaacc/src/main`
  looking for a second, un-capped path into a browser's sort logic. Found
  one candidate worth tracing: the vendored
  `org.fourthline.cling.support.contentdirectory.AbstractContentDirectoryService`
  (`AbstractContentDirectoryService.java:169-213`) defines its own
  `@UpnpAction`-annotated `browse(...)` *and* `search(...)` methods that
  each independently parse `SortCriterion.valueOf(orderBy)` with no cap —
  structurally the same shape this Warning was about. Confirmed via grep
  that **nothing in the codebase extends or instantiates
  `AbstractContentDirectoryService`** — `YaaccContentDirectory` is a plain
  class (`public class YaaccContentDirectory` at
  `YaaccContentDirectory.java:91`, no `extends`) that defines its own
  `@UpnpAction browse()` independently; `AbstractContentDirectoryService`
  is dead vendored code with no live call path, so its unguarded
  `SortCriterion.valueOf` calls are not reachable from the network and are
  out of scope (pre-existing, unchanged, unused). Also confirmed
  `YaaccContentDirectory` exposes no `search` UPnP action at all (grep for
  `search` in the file returns nothing), so the `Search` action — which
  would be a second `SortCriteria`-bearing entry point per the UPnP
  ContentDirectory:1 spec — simply isn't implemented/exposed by this
  service; only `Browse` is, and it's the one `validateSupported` guards.
  Re-confirmed (unchanged since Cycle 1) the SQL-injection shape, SAF
  path-scoping, the duplicate-comparator-logic equivalence, and the
  information-disclosure angle all still hold — `cc76074` touches only
  `SortSupport.java`, its test, and `tasks.md`; no browser file changed,
  so none of those Cycle-1-verified properties could have regressed. No
  new finding.

### Verdict: PASS
