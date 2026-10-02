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
