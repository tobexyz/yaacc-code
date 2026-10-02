# Review: Server-side sort support for yaacc's own UPnP ContentDirectory

## Cycle 1 — 2026-10-02
Reviewing: Group 1 tasks (commit `4ef1ac2` — "feat: add SortSupport and wire real SortCaps/validation (Group 1)")

Scope confirmed: Group 2 (per-browser wiring) is not yet implemented and is correctly out of scope for this cycle; not flagged as missing.

### Critical
None.

### Warning
None.

### Suggestion
- `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/SortSupport.java:62-63` — `SUPPORTED_PROPERTIES` is a public `static final` field holding a mutable `HashSet`, so any caller (including test code) could mutate the service-wide allow-list at runtime (e.g. `SortSupport.SUPPORTED_PROPERTIES.add(...)`). This matches `design.md`'s pseudocode verbatim, so it's not a deviation, but wrapping it in `Collections.unmodifiableSet(...)` would close off accidental mutation cheaply. Non-blocking — no code currently mutates it.
- `yaacc/src/test/java/de/yaacc/upnp/server/contentdirectory/SortSupportTest.java` — `toComparator` has ASC/DESC single-key tests but no multi-key chaining test (`toMediaStoreSortOrder` does have one: `toMediaStoreSortOrderBuildsMultiKeyOrder`). The design explicitly calls out multi-key `thenComparing` chaining as a feature; a parallel `toComparatorOrdersByMultipleKeys`-style test would close the gap. Not required by `tasks.md`'s literal Accept criteria, and the chaining logic (`SortSupport.java:160-167`) is simple/correct on inspection, so this is a nice-to-have rather than a missing acceptance case.
- `SortSupport.java:153-157` — direction is applied by reversing the per-key comparator (`comparator.reversed()`) before chaining with `thenComparing`, which is correct (each key keeps its own direction rather than the whole chain being reversed) and verified by inspection. One side effect worth a one-line doc comment: `Comparator.nullsFirst(...)` combined with a later `.reversed()` means nulls sort *last* on a descending key, not first — intentional but non-obvious to a future reader/caller (relevant once Group 2 wires `SafFolderBrowser`'s possibly-null dates through `toComparator`).

### Tests
- [x] All tests passing — `./gradlew :yaacc:testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL; full suite 142 tests / 0 failures / 0 errors across 33 test classes; `SortSupportTest` itself: 20/20 passing (`tests="20" failures="0" errors="0"` in `yaacc/build/test-results/testDebugUnitTest/TEST-de.yaacc.upnp.server.contentdirectory.SortSupportTest.xml`).
- [x] Coverage adequate for Group 1's acceptance criteria: null/empty `orderby` fallback (both `toMediaStoreSortOrder` and `toComparator`, plus `validateSupported` no-op for null/empty array), single mapped `dc:title`/`dc:date` ASC/DESC (`toMediaStoreSortOrderBuildsAscendingTitleColumn`/`...DescendingDateColumn`, `toComparatorOrders{Ascending,Descending}ByTitle`), multi-key `toMediaStoreSortOrder` (`...BuildsMultiKeyOrder`, `...SkipsUnmappedAndKeepsMappedInMultiKey`), unmapped-property fallback-to-default for both builder methods, `validateSupported` pass for `dc:title`/`dc:date` and throw (with correct `UNSUPPORTED_SORT_CRITERIA` error code) for a single unsupported property and for a mixed array with one unsupported entry, and `formatEpochMillisAsDate` for a known timestamp and epoch zero.

### Findings detail

1. **Design conformance** — `SortSupport.java` matches `design.md`'s "`SortSupport`" section exactly: `PROPERTY_TITLE`/`PROPERTY_DATE`/`SUPPORTED_PROPERTIES` constants, `validateSupported(SortCriterion[])` (no-op for null, throws `ContentDirectoryException(UNSUPPORTED_SORT_CRITERIA, ...)` for any unsupported property), `toMediaStoreSortOrder(SortCriterion[], Map<String,String>, String)` (returns `defaultSortOrder` unchanged for null/empty/all-unmapped, skips individually-unmapped criteria in a multi-key list), `toComparator(SortCriterion[], Map<String,Function<DIDLObject,String>>, Comparator<DIDLObject>)` (same fallback semantics, direction-aware per-key, stable multi-key chaining via `thenComparing`), and `formatEpochMillisAsDate(long)` (`yyyy-MM-dd` via `java.time`, UTC, matches test expectations). Method signatures match the design's code block verbatim.

2. **Zero `android.*` imports** — confirmed: `grep -n "^import android" SortSupport.java` returns nothing; the only imports are `org.fourthline.cling.*` (already Android-framework-free per `docs/tech.md`) and `java.time.*`/`java.util.*`/`java.util.function.Function`. Satisfies the hard testability requirement.

3. **`YaaccContentDirectory` behavior preserved** — read the full `browse(...)` method and constructor, not just the diff (`YaaccContentDirectory.java:108-119`, `302-337`). Only two additive changes:
   - Constructor: two `.add(...)` calls populate `sortCapabilities` after it's constructed as an empty `CSVString` — no change to `isUsingTestContent()`/`createTestContentDirectory()` ordering or any other constructor logic.
   - `browse(...)`: `SortSupport.validateSupported(orderByCriteria)` is inserted immediately after the existing `SortCriterion.valueOf(orderBy)` try/catch and *before* the second `try { return browse(...) } catch (ContentDirectoryException ex) { throw ex; } catch (Exception ex) { ... ACTION_FAILED ... }` block. This is the correct placement per design: the new call sits outside that second try, so a thrown `UNSUPPORTED_SORT_CRITERIA` from `validateSupported` propagates directly via `browse`'s own `throws ContentDirectoryException` rather than being caught and rewrapped as `ACTION_FAILED`. The existing malformed-syntax rejection path (`SortCriterion.valueOf` throwing `IllegalArgumentException` → `UNSUPPORTED_SORT_CRITERIA`) is untouched. Confirmed via `docs/tech.md`'s cited `SortCriterion.valueOf` source excerpt that empty/null `orderBy` yields an empty array (not null), so `validateSupported`'s null-check path, while present and unit-tested, is defensive rather than reachable from `browse()` today — not a bug, matches the documented public contract.
   - No other line in the file changed (`git show 4ef1ac2 -- .../YaaccContentDirectory.java` is a 2-line + 1-line diff only).

4. **Verification commands run**:
   - `./gradlew :yaacc:testDebugUnitTest --tests "de.yaacc.upnp.server.contentdirectory.SortSupportTest"` → BUILD SUCCESSFUL
   - `./gradlew :yaacc:testDebugUnitTest --rerun-tasks` (full suite, forced, not cached) → BUILD SUCCESSFUL, 142/142 tests passing project-wide
   - `grep -n "^import android" SortSupport.java` → no output (pass)

5. **Code quality** — naming is consistent with `design.md` and the rest of the codebase; no dead code; no off-by-one in the comparator-chaining loop (`combined = comparators.get(0)` then `thenComparing` from index 1, correct); error code used is `ContentDirectoryErrorCode.UNSUPPORTED_SORT_CRITERIA`, matching both `design.md` and the existing malformed-syntax path for a single consistent rejection code. See Suggestions above for the one mutable-static-field nit and the missing multi-key `toComparator` test.

### Verdict: PASS
