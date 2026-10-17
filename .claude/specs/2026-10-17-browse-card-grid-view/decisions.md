# Decisions: Browse card-grid view

## 2026-10-17 — IconDownloadTask leak found during Group 1 research

**Context**: `tasks.md`'s original Group 2 "ViewMode support" task called
for `onBindViewHolder` to request a per-mode thumbnail size via
`IconDownloadTask`'s existing `(ImageView, int, int)` constructor instead
of the fixed-48px `(ImageView, BrowseContentItemAdapter)` one. Group 1's
doc-research task found that the `(ImageView, int, int)` constructor
unconditionally sets `browseContentItemAdapter = null`
(`IconDownloadTask.java:65-72`), so `onPostExecute`'s
`browseContentItemAdapter.removeTask(this)` cleanup never fires for tasks
created that way — `asyncTasks` would grow by one un-removed entry per
bound row for the adapter's lifetime. Not a pre-existing bug; this spec
would have introduced it.

**Decision**: add a new `IconDownloadTask` constructor overload —
`(ImageView, BrowseContentItemAdapter, int, int)` — that sets both the
adapter reference (preserving `removeTask` cleanup) and the custom
width/height, and have `onBindViewHolder` call that one instead. Updated
`tasks.md`'s Group 2 adapter task accordingly before delegating Group 2.

**Rationale**: smallest change that preserves existing cleanup behavior
exactly while still allowing a non-48px size; no existing constructor's
behavior changes, so no risk to the unrelated `BrowseReceiverDeviceAdapter`
call site already using the 3-arg constructor for 512×512 album art.

## 2026-10-17 — No view-mode toggle in the landscape layout (known gap, not fixed)

**Context**: the toggle button was added to `fragment_content_list.xml`
(portrait) only. `yaacc/src/main/res/layout-land/fragment_content_list.xml`
also has its own `contentListSortToggle` row but was out of this task's
file scope, so it has no `contentListViewModeToggle` button.
`initViewModeToggle` null-checks and returns early there (same defensive
pattern `initSortToggle` already uses for the same reason), so nothing
crashes — landscape just has no way to switch view mode via that
button.

**Decision**: accept this gap for v1 rather than silently expanding
scope. A mode chosen in portrait still applies correctly on rotation to
landscape (the persisted preference + `initViewModeToggle`'s "apply
LayoutManager for persisted mode" step both run regardless of whether
the button itself exists) — so landscape *respects* whatever was chosen,
it just can't *change* it. Documented as a known follow-up in Group 3.

## 2026-10-17 — Security review Cycle 1 FAIL: httpcore5 CVE regression, fixed

**Context**: this branch (`feat/issue287`) was branched off `feat/issue252`,
which includes an earlier, separate fix
(`fix: revert httpclient5/httpcore5 to 5.2.1/5.2 to fix Android crash`)
that reverted both `httpclient5` (5.6.5→5.2.1) and `httpcore5`
(5.4.4→5.2) together to fix a real Android ART crash. The
security-reviewer correctly flagged that `httpcore5:5.2` falls inside
the vulnerable range for two scored CVEs that `5.4.4` was already
patched against: CVE-2026-54399 (HTTP/1.1 header-parsing
memory-exhaustion DoS, fixed 5.4.3) and CVE-2026-54428 (HTTP/2 HPACK
decoder memory-exhaustion DoS, fixed 5.4.3, in the `httpcore5-h2`
artifact specifically). Both independently verified via web search
against third-party CVE trackers, not just taken on the subagent's word.
Real exposure: `httpcore5` is yaacc's actual HTTP transport for talking
to other UPnP devices on the LAN (`HttpRequestSender`), so a malicious or
compromised network peer is a credible attacker for the header-parsing
DoS.

**Decision**: the Android-crashing bytecode (confirmed earlier via
direct `javap` decompilation) lives entirely in `httpclient5`'s own
`DefaultHttpClientConnectionOperator` class, not in `httpcore5`. So
`httpclient5` stays pinned at `5.2.1` (avoids the crash), while
`httpcore5` is bumped back to `5.4.4` (avoids CVE-2026-54399) — this
combination builds and passes all tests. Additionally had to explicitly
pin the transitive `httpcore5-h2` module (pulled in by `httpclient5`,
contains the HPACK decoder CVE-2026-54428 targets) to `5.4.4` as well:
without an explicit declaration, Gradle only overrode the plain
`httpcore5` artifact's version, leaving `httpcore5-h2` resolved at the
vulnerable `5.2` since nothing told Gradle to override that specific
module. Verified via `./gradlew dependencies` that both
`httpcore5`/`httpcore5-h2` now resolve to `5.4.4` everywhere in the
dependency graph, and `httpclient5:5.2.1`'s resolved jar still has zero
`jdk/net` references.

**Rationale**: the Android crash and the httpcore5 DoS CVEs are
independent problems in two separate artifacts that don't need to be
traded off against each other — pinning each artifact to its own safe
version resolves both simultaneously.
