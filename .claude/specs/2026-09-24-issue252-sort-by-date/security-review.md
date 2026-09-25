# Security Review: Sort by Date / Recently Added for UPnP folders (issue #252)

## Cycle 1 — 2026-09-25
Reviewing: Groups 1-2 plus the fix-cycle commit, full diff `bc11586..HEAD`
(commits `7145277`, `998d0e9`, `e1ea74c`, `bd9269d`, `9579390`). General
review (`review.md`) already passed Cycle 2 with zero Critical/Warning; this
gate re-examines the same diff exclusively for security concerns.

### Critical

None.

### Warning

None.

### Suggestion

- **`BrowseContentItemAdapter.parseDateMillis()` re-parses `dc:date` on
  every pairwise comparison instead of once per item
  (`yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java:259-309`).**
  Not a vulnerability — `java.time.LocalDate/OffsetDateTime/LocalDateTime.parse`
  use bounded, non-backtracking ISO-8601 parsers (no regex engine involved,
  so no ReDoS surface), each wrapped in try/catch, and a comparator-driven
  `List.sort` on a chunk-paginated, in-memory folder listing is O(n log n)
  string parses — not attacker-amplifiable into a meaningful CPU/memory DoS
  from a single crafted `dc:date` string or even a large folder (chunk size
  is server/user-configured, default 50, and sorting only happens after the
  whole folder is already fetched). A pre-parsed-and-cached
  `Map<DIDLObject, Long>` (or annotating a transient field) would be a minor
  efficiency win, not a security fix — logged here for completeness per the
  request to check for pathological-comparator concerns, not as a blocking
  finding.

### Findings by requested area

**1. Untrusted `dc:date` server input
(`BrowseContentItemAdapter.parseDateMillis` / `compareByDateDescending` /
`hasDate`)**
- No regex is used anywhere in the parse path — `LocalDate.parse`,
  `OffsetDateTime.parse`, `LocalDateTime.parse` are all JDK ISO-8601
  parsers with bounded, linear-time behavior; there is no ReDoS vector.
- Every parse attempt is wrapped in `try/catch (DateTimeParseException)`,
  cascading through three formats and returning `null` (never throwing) on
  total failure. Verified this by reading the method directly — matches
  `review.md`'s general-review finding, independently confirmed here.
- `value.trim()` is the only string operation performed on attacker-supplied
  content before parsing; unbounded in theory (a pathological server could
  send a very long `dc:date` string) but DIDL/SOAP responses already pass
  through Cling's XML parser and the existing 999-item/`chunkSize`-bounded
  `Browse` response handling before reaching this code — this diff does not
  introduce a new unbounded-input path, and `trim()`+three bounded parses on
  a single string is not a meaningful amplification vector.
- `compareByDateDescending`'s null-handling is a valid, contract-safe
  `Comparator` (transitive, consistent for nulls at either/both ends) —
  confirmed no `IllegalArgumentException`/"Comparison method violates its
  general contract" risk, matching `review.md`'s prior finding.
- **No injection risk**: `dc:date` values are used exclusively as
  `Comparator` inputs (`compareByDateDescending`) and a `boolean` presence
  check (`hasDate`). Traced every use of `parseDateMillis`'s return value
  and of the raw `getFirstPropertyValue(...)` string — neither is ever
  concatenated into a displayed string, a query, a shell command, a file
  path, or any other sink. `onBindViewHolder` (unchanged by this diff)
  renders `currentObject.getTitle()` via `TextView.setText()`, not `dc:date`
  and not via `Html.fromHtml` or a `WebView`, so even title strings from an
  untrusted server carry no HTML/script-injection risk on native Android
  widgets. Confirmed: no path from `dc:date` to any injection-relevant sink
  exists in this diff.
- **Large-item-count DoS**: sorting only runs once per `setAllItemsFetched(true)`
  transition (guarded by the `justCompleted` check) — not on every chunk —
  and pagination chunk size is bounded by the existing
  `settings_browse_chunk_size` preference. No new unbounded-fetch or
  unbounded-loop path was introduced.

**2. `SortCriterion` / `UpnpClient.browseSync` orderBy threading**
- The only production call site constructing a `SortCriterion` is
  `BrowseItemLoadTask.doInBackground`
  (`yaacc/src/main/java/de/yaacc/browser/BrowseItemLoadTask.java:51-54`):
  `new SortCriterion(false, "dc:date")` — `"dc:date"` is a fixed string
  literal, and the ternary's only alternative is `new SortCriterion[0]`
  (empty). **No external or user-controlled data reaches the
  `propertyName` constructor argument anywhere in this diff.** The value is
  gated only by `itemAdapter.getSortMode()`, an internal enum with exactly
  two values (`NAME`/`DATE`), never a free-form string.
- Traced `orderBy`'s full path: `BrowseItemLoadTask` →
  `UpnpClient.browseSync(Position, Long, Long, SortCriterion...)`
  (`yaacc/src/main/java/de/yaacc/upnp/UpnpClient.java:581-593`, this diff)
  → the pre-existing, unchanged `browseSync(device, objectId, flag, filter,
  firstResult, maxResult, orderBy)` overload → `Browse`'s constructor
  (`yaacc/src/main/java/de/yaacc/upnp/callback/contentdirectory/Browse.java:74`):
  `getActionInvocation().setInput("SortCriteria", SortCriterion.toString(orderBy))`.
  `SortCriterion.toString()` (vendored Cling,
  `yaacc/src/main/java/org/fourthline/cling/support/model/SortCriterion.java:68-74`,
  untouched by this diff) simply emits `"-dc:date"` or `""` — no string
  concatenation of untrusted input, no way to inject additional
  `SortCriterion` entries or SOAP-field-breaking characters, since the only
  two possible outputs are the fixed literal and the empty string.
  `ActionInvocation.setInput(String, Object)` then routes the value through
  Cling's standard action-argument/XML-serialization path (pre-existing,
  out of this diff's scope, and not attacker-reachable here since the value
  itself is a fixed literal).
- **Conclusion**: the new `orderBy` varargs parameter is exercised in
  production with exactly one fixed literal and one empty array; there is
  no dynamic/user-influenced use of this API anywhere in this diff, so no
  UPnP action-parameter/SortCriteria injection risk exists today. Flagging
  for future awareness (not a current finding): if a later change ever
  threads a *user-supplied* sort field into `new SortCriterion(...)`
  without validating it against a fixed enum/allowlist, that would need a
  fresh review — the current design's safety rests on the literal being
  hardcoded, not on any sanitization in `SortCriterion` itself (its
  `toString()` performs no escaping of `propertyName`).

**3. SharedPreferences `settings_sort_order_key`**
- Stores only `SortMode.name()` — i.e. exactly the ASCII string `"NAME"` or
  `"DATE"` — written from `ContentListFragment.onSortModeSelected` and
  `BrowseContentItemAdapter.setSortMode` call sites. No PII, tokens,
  credentials, or server-derived content is ever written to this key.
  Confirmed via `grep` that the key is used identically (read-only pairing
  with the same default) in exactly three places, all internal to the app.
- `BrowseContentItemAdapter.readPersistedSortMode()`
  (`BrowseContentItemAdapter.java:111-118`): `SortMode.valueOf(persisted)`
  wrapped in `catch (IllegalArgumentException | NullPointerException e)`,
  falling back to `SortMode.NAME`. This **fails closed** correctly:
  - `Enum.valueOf(Class, String)` performs a bounded map lookup keyed by
    the enum's declared constant names; an unexpected/arbitrary/tampered
    string of any length throws `IllegalArgumentException` (caught) rather
    than any unbounded computation, and the lookup itself does not run
    user-supplied code or reflection on attacker-controlled class/method
    names — it's a safe, framework-internal `HashMap` read.
  - `NullPointerException` is also caught, covering the (here practically
    unreachable, since `getString` is given a non-null default) case of a
    null read.
  - An arbitrarily long string (e.g. a `SharedPreferences` file hand-edited
    on a rooted device, or corrupted) causes no more than the `valueOf`
    lookup's normal linear scan over ~2 constant names plus an exception
    allocation — no unbounded memory/CPU use, no crash. Verified there is
    no other consumer of this preference value that skips the
    try/catch (`ContentListFragment.initSortToggle` independently performs
    the identical `valueOf` + `catch (IllegalArgumentException)` fallback
    pattern, also failing closed to `NAME`).
  - **Conclusion**: the catch clause is sufficient; no crash or
    worse-than-"caught exception" outcome is reachable via a malicious or
    corrupted preference value, confirming the specific concern raised in
    the task brief.

**4. Test-file reflection workaround
(`BrowseContentItemAdapterSortTest.fixUpAdapterDataObservable`)**
- File path confirmed: `yaacc/src/test/java/de/yaacc/browser/BrowseContentItemAdapterSortTest.java`
  — under `src/test/` (plain-JVM unit tests, `unitTests.returnDefaultValues
  = true`), not `src/main/` and not `src/androidTest/` (instrumented tests
  that would ship test APK code alongside a release build in some CI
  configs). `src/test/` sources are compiled only by the `testDebugUnitTest`
  /`testReleaseUnitTest` Gradle tasks and are never packaged into an APK or
  AAB — standard Android Gradle Plugin behavior, and this project's
  `build.gradle` sets no non-standard `sourceSets` mapping that would change
  that.
- `grep -rn "BrowseContentItemAdapterSortTest\|fixUpAdapterDataObservable"`
  across `yaacc/src/main` and `yaacc/src/androidTest` returns zero matches
  — nothing in production or instrumented-test code references this class
  or method. It is unreachable from any build artifact a user or a remote
  server could ever interact with.
- The reflection itself only ever touches this process's own in-memory
  `RecyclerView.Adapter`/`Observable` instance fields (`mObservable`,
  `mObservers`) to work around a mockable-`android.jar` stub limitation; it
  does not touch any security-relevant API (no credential store, no crypto,
  no IPC), and even if it behaved unexpectedly the blast radius is limited
  to a test run failing loudly (`IllegalStateException`), never a
  production code path.
- **Conclusion**: zero production/release-build reachability confirmed;
  this is not a security concern.

**5. General OWASP sweep of the full diff**
- **Hardcoded secrets**: none. `git diff bc11586..HEAD` introduces no API
  keys, tokens, passwords, or credentials; the only new "constant" values
  are the internal preference key `sort_order_key`, the literal
  `"dc:date"`, and UI strings/drawables.
- **Unsafe deserialization**: none introduced. No new `ObjectInputStream`,
  `Serializable` read, XML/YAML deserializer, or reflection-based object
  construction from untrusted input anywhere in the diff (the only
  reflection is the test-only workaround in Finding 4, which reads/writes
  known field names on a locally-constructed object, not attacker data).
- **Improper trust of remote content**: the diff's one new remote-data
  consumer (`dc:date`) is handled defensively (Finding 1) and never trusted
  for anything beyond sort ordering — it does not gate any security
  decision, permission check, or trust boundary.
- **New permissions/exported components/manifest changes**: none — no
  `AndroidManifest.xml` changes in this diff.
- **New network calls/endpoints**: none — `orderBy` rides the existing
  `Browse` SOAP action; no new UPnP action, HTTP endpoint, or protocol
  surface is introduced.
- **New dependencies**: none — `git diff --stat` shows no `build.gradle`
  changes; no new third-party library, so no new supply-chain surface and
  nothing to pin/quarantine-check under `dependency-versions.md`.
- **Logging**: `YaaccLogger.d`/`.v` calls in the touched files (e.g.
  `BrowseContentItemAdapter.addAll`, `BrowseItemLoadTask`) log object
  counts and titles already logged pre-diff; no new logging of `dc:date`
  values or preference contents that would warrant redaction — nothing
  sensitive is logged either way (`dc:date` is not PII/secret data, and no
  credentials flow through this feature).

### Verification

- `./gradlew :yaacc:lintDebug` — ran fresh in this cycle: BUILD SUCCESSFUL,
  no new lint findings beyond what Cycle 2 of the general review already
  characterized (translations resolved; the 71 pre-existing baseline
  errors are all unrelated categories, confirmed against `review.md`
  Cycle 2's own lint sweep — no security-relevant lint categories
  triggered by this diff, e.g. no `TrustAllX509TrustManager`,
  `SetJavaScriptEnabled`, `HardcodedDebugMode`, `WorldWriteableFiles`, or
  similar).
- Manually traced every production data flow named in the task brief
  (`dc:date` → comparator; `SortMode` enum → `SortCriterion("dc:date")` →
  `Browse`'s `SortCriteria` SOAP field; `SortMode` enum ↔
  `settings_sort_order_key` SharedPreferences) end to end against the
  actual diff content, not just the design doc's description of intended
  behavior.

### Verdict: PASS

Zero Critical, zero Warning findings. `dc:date` (untrusted, server-supplied)
is parsed defensively (bounded, non-regex ISO-8601 parsers, all exceptions
caught, `null`-safe comparator) and used exclusively as sort-comparator
input — never concatenated into any query, command, or view, confirming the
task brief's specific concern is unfounded. The new `SortCriterion`/
`orderBy` API is exercised only with a fixed `"dc:date"` literal or an empty
array; no user- or server-controlled data reaches the `SortCriteria` SOAP
field, so there is no UPnP action-parameter injection path in this diff.
`settings_sort_order_key` stores only an internal enum name and
`SortMode.valueOf(...)` fails closed to `NAME` on any unexpected value,
including arbitrary-length/corrupted strings, without risk of crash beyond
the caught exception. The test-only reflection workaround lives exclusively
under `src/test/`, is unreferenced from any production or instrumented-test
source, and never ships in a release build. No secrets, no unsafe
deserialization, no new manifest/permission/network surface, and no new
dependencies were introduced. Ready to proceed past the security-review
gate.

## Cycle 2 — 2026-09-25

Reviewing: commit `f9efa83` only (`git diff 21dad94..f9efa83`) — Fix Group 2
(retry-without-`orderBy` on server sort rejection) and Group 4 (independent
ascending/descending direction toggle per sort mode). General review passed
Cycle 3 (`review.md`) for this same commit; this is a fresh security-only
pass, not a re-review of Cycle 1's already-cleared surface (untouched by this
commit).

### Critical

None.

### Warning

None.

### Suggestion

- **Retry-without-`orderBy` fires on *any* UPnP action failure of the sorted
  attempt, not specifically an "unsupported `SortCriteria`" failure**
  (`yaacc/src/main/java/de/yaacc/browser/BrowseItemLoadTask.java:56-70`).
  `if (sortedResult != null && sortedResult.getUpnpFailure() == null) { return sortedResult; }`
  treats every non-`null` `getUpnpFailure()` identically — a device that
  genuinely rejects `-dc:date`/`dc:date` in `SortCriteria`, a transient
  network blip, an auth/permission error on that specific action, or a
  malformed SOAP response from the device all take the same path: log, call
  `markServerSortRejected()`, retry once without `orderBy`. This is a
  **correctness/availability** observation, not a vulnerability, and it
  matches the design's own explicit scope (`requirements.md` item 8: "if a
  server-side sorted Browse request fails (UPnP action failure...)"), so it
  is not a deviation introduced by this diff — logging it because the task
  brief specifically asked whether this could mask a real problem:
  - **No failure is silently swallowed or misrepresented.** If the cause is
    transient/unrelated to sorting and the unsorted retry also fails, that
    second `ContentDirectoryBrowseResult` (with its own `getUpnpFailure()`)
    is returned as-is from `doInBackground` with no further catching, and
    `BrowseItemLoadTask.onPostExecute` (unchanged in this diff,
    lines 87-100) still logs it via `YaaccLogger.e("ResolveError", ...)`
    with the failure detail and calls `itemAdapter.clear()` — the user sees
    exactly the same "folder failed to load" outcome they would have without
    this fix, just after one extra doomed sorted-Browse round trip. Nothing
    is hidden as a false "just retry unsorted" success.
  - **No unbounded retry.** `markServerSortRejected()`
    (`BrowseContentItemAdapter.java:193-195`) is reached unconditionally on
    every fallback path *before* the unsorted call is made, and
    `attemptServerSort` (`BrowseItemLoadTask.java:54-55`) is gated on
    `!itemAdapter.isServerSortRejected()`. Verified: at most 2
    `browseSync(...)` calls occur for the chunk request that first observes
    the failure (1 sorted + 1 unsorted), and exactly 1 call for every
    subsequent chunk request of the same folder load — confirmed both by
    reading the control flow and by the new
    `BrowseItemLoadTaskTest.serverRejectionOfSortedBrowseFallsBackToUnsortedContentInsteadOfClearing`
    test, which asserts `verifySortedAttemptCount(1)` after two chunk
    requests. `serverSortRejected` resets in `clear()` (folder
    change/mode/direction change), so it never accumulates across folders or
    leaks into a different server's session — no cross-folder or cross-device
    state confusion.
  - **Residual (non-blocking) UX note, not a security finding:** because the
    rejection flag is sticky for the rest of that folder load regardless of
    cause, a transient (non-sort-related) failure on the *first* chunk
    permanently disables the server-side sort attempt for the *remaining*
    chunks of that same folder browse, even though the server might have
    handled `SortCriteria` fine on a retry. This only affects sort
    *placement* (client-side fallback sort and `isDateSortAvailable()`
    disabling still function correctly on the unsorted results per the
    design), never data exposure, access control, or integrity — no action
    needed to pass this gate.

### Findings by requested area

**1. UPnP action-failure handling / retry-without-`orderBy`** — see Suggestion
above. No masking of genuine failures; no infinite-retry path; bounded to at
most one extra request per folder load per the `markServerSortRejected()`
placement confirmed unconditional-before-fallback.

**2. New SharedPreferences keys (`settings_sort_name_ascending_key`,
`settings_sort_date_ascending_key`)**
(`yaacc/src/main/res/values/setting_strings.xml:45-46`,
`BrowseContentItemAdapter.java:115-116,155-166`)
- Store only `boolean` values via `SharedPreferences.Editor.putBoolean(...)`
  — no strings, no serialized objects, nothing PII/credential-shaped.
- Read via `sharedPreferences.getBoolean(key, default)` with a literal
  Java `boolean` default (`true` for name, `false` for date) — no custom
  parsing, no `Boolean.parseBoolean(String)` on a stored string, no
  reflection, nothing that could throw or misbehave on a corrupted/tampered
  preferences file beyond Android's own `getBoolean` contract (returns the
  provided default for a missing key; per platform behavior a value of the
  wrong underlying type throws `ClassCastException`, a pre-existing
  framework contract this diff does not touch or make worse — no other key
  in this file shares the same preference name, so no cross-key type
  collision is possible). Fails closed to the documented defaults.
- Storage location is the same `PreferenceManager.getDefaultSharedPreferences(context)`
  (app-private, `MODE_PRIVATE` by default, unchanged call site) already used
  for `settings_sort_order_key` and cleared in Cycle 1 — confirmed via
  `grep` that no new `getSharedPreferences(..., MODE_WORLD_*)` or similar
  was introduced.

**3. `SortCriterion` direction plumbing**
(`BrowseItemLoadTask.java:57`: `new SortCriterion(itemAdapter.isDateAscending(), "dc:date")`)
- Confirmed the only change versus the literal Cycle 1 already cleared
  (`new SortCriterion(false, "dc:date")`) is that the first argument is now
  `itemAdapter.isDateAscending()` — a `boolean` sourced from the adapter's
  own internal field, itself only ever set from `!dateAscending` (a flip) or
  a `SharedPreferences.getBoolean` read (Finding 2). The second argument
  remains the fixed string literal `"dc:date"`, unchanged. No new
  string/object ever reaches the `propertyName` constructor parameter; the
  vendored `SortCriterion.toString()` (untouched) can now only ever emit
  `""`, `"dc:date"`, or `"-dc:date"` — still no attacker-influenced content
  and no way to inject additional `SortCriteria` entries or SOAP-breaking
  characters. Cycle 1's injection analysis of this API holds unchanged.

**4. New vector drawables**
(`ic_baseline_date_range_asc_32.xml`, `ic_baseline_sort_by_alpha_desc_32.xml`)
- Both are static `<vector>` XML resources: a single `<group>` with a fixed
  180-degree `rotation`/`pivotX`/`pivotY` wrapping one `<path>` with a
  hardcoded `pathData` string and `fillColor="@android:color/white"`, same
  `android:tint="#FFFFFF"` pattern as the pre-existing icons. No
  `<bitmap>`/remote `src`, no `app:layout_*` data binding, no
  `clip-path`/`animated-vector` scripting hooks, no external references of
  any kind. Android `VectorDrawable` XML has no code-execution surface.
  Nothing to flag.

**5. Test-only code**
(`yaacc/src/test/java/de/yaacc/browser/BrowseItemLoadTaskTest.java`,
additions to `BrowseContentItemAdapterSortTest.java`)
- Both files confirmed under `yaacc/src/test/java/...` (plain-JVM unit
  tests), not `src/main` or `src/androidTest`.
- `grep -rn "BrowseItemLoadTaskTest\|fixUpAdapterDataObservable" yaacc/src/main yaacc/src/androidTest`
  returns zero matches in this cycle — unreachable from any production or
  instrumented-test build artifact, matching Cycle 1's conclusion for the
  sibling file.
- The new `BrowseContentItemAdapterSortTest.newAdapter(...)` static overload
  (exposed for reuse by `BrowseItemLoadTaskTest`) only widens visibility
  within the test source set (`static` package-visible helper); it is not
  reachable from `src/main` either.

**6. General OWASP sweep**
- **Hardcoded secrets**: none — only new constants are UI preference key
  names, the pre-existing `"dc:date"` literal, and drawable path data.
- **Unsafe deserialization**: none introduced.
- **New permissions/manifest changes**: none —
  `git diff 21dad94..f9efa83 -- '**/AndroidManifest.xml'` is empty.
- **New network/UPnP action surface**: none — this commit adds a second
  *call* to the pre-existing `browseSync(...)` overloads (with and without
  `orderBy`), already reviewed in Cycle 1; no new UPnP action or SOAP
  endpoint.
- **New dependencies**: none — no `build.gradle`/`build.gradle.kts` changes
  in the diff.
- **Logging**: the one new log line
  (`YaaccLogger.d(getClass().getName(), "Server rejected sorted (dc:date) Browse request; ...")`,
  `BrowseItemLoadTask.java:68`) logs only a static message — no `dc:date`
  value, no preference content, no server response body. Nothing sensitive.

### Verification

- `./gradlew :yaacc:lintDebug` — ran fresh in this cycle: `BUILD SUCCESSFUL`,
  no new findings (see command output; task graph shows all lint tasks
  `UP-TO-DATE`/executed cleanly, 28 actionable tasks, 0 failures).
- Manually traced: `attemptServerSort` gating and `markServerSortRejected()`
  placement in `BrowseItemLoadTask.doInBackground` (retry-loop-bound claim);
  every read/write site of the two new SharedPreferences keys; the full
  `orderBy`/`SortCriterion` construction call site; every path referencing
  `BrowseItemLoadTaskTest`/`fixUpAdapterDataObservable` repo-wide; the diff's
  file list against `AndroidManifest.xml` and `build.gradle*` (both absent).

### Verdict: PASS

Zero Critical, zero Warning findings. The retry-without-`orderBy` fallback
cannot loop unboundedly (`markServerSortRejected()` is unconditionally
reached before the unsorted call on every fallback path, verified both by
code reading and by `BrowseItemLoadTaskTest`) and never masks a genuine
failure from the user — if the underlying cause persists through the
unsorted retry too, the existing `onPostExecute` failure path (logging +
`clear()`) still runs exactly as before this diff. Retrying on any UPnP
failure rather than narrowly on an unsupported-`SortCriteria` failure is a
documented design choice (`requirements.md` item 8), logged above as a
non-blocking Suggestion for awareness, not a security gap. The two new
SharedPreferences keys store only booleans, use the same app-private
preferences store as the already-cleared `settings_sort_order_key`, and add
no custom parsing that could behave unexpectedly on a corrupted value. The
`SortCriterion` direction argument is the only new variable; the
`propertyName` literal `"dc:date"` is unchanged, so Cycle 1's
no-injection-path conclusion still holds. The two new vector drawables are
fully static XML with no dynamic or remote content. Both new/changed test
files live exclusively under `src/test/` and are unreferenced from any
production or instrumented-test source. No secrets, no unsafe
deserialization, no manifest/permission/network/dependency changes. Ready to
proceed past the security-review gate.
