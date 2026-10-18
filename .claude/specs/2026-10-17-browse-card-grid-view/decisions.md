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

## 2026-10-18 — Correction: the Cycle 1/2 fix above was incomplete; httpcore5 itself also has the crash

**Context**: the fix above (pairing `httpclient5:5.2.1` with
`httpcore5:5.4.4`/`httpcore5-h2:5.4.4`) was wrong — verified only that
the crashing `jdk.net` probe lived in `httpclient5`'s own class, but
never checked whether `httpcore5` independently reimplements the same
probe in its own code. It does: `httpcore5`'s `ReflectionUtils` class
was rewritten in the 5.4 line to add the identical unconditional
`jdk.net.Sockets`/`ExtendedSocketOptions` probe (confirmed via direct
`javap` decompilation of the resolved jar), used by
`SingleCoreIOReactor.prepareSocket` — part of yaacc's embedded UPnP HTTP
server. This is what actually crashed on the user's device after Cycle
2 passed and the branch was tested on-device (`FATAL EXCEPTION` in
`org.apache.hc.core5.util.ReflectionUtils.<clinit>`, not
`DefaultHttpClientConnectionOperator` this time).

**Investigation**: confirmed the exact boundary for both libraries by
decompiling multiple versions directly:
- `httpcore5`: 5.3.6 and earlier have the old, generic `ReflectionUtils`
  (zero `jdk/net` references); `5.4` (GA, not just later patches) is
  where the keepalive-probing rewrite landed, present through at least
  `5.4.4`.
- `httpclient5`: `5.3.1` and earlier have a clean
  `DefaultHttpClientConnectionOperator` (zero `jdk/net` references);
  `5.4`+ has the probe (previously confirmed with `5.6.5`).
- **No upstream httpcore5 release satisfies both constraints**: versions
  <5.4 avoid the Android crash but are confirmed still vulnerable to
  both CVE-2026-54399 and CVE-2026-54428 (verified via a third-party
  bug report that `httpcore5:5.3.6` specifically is still listed as
  affected — no 5.3.x security backport exists); versions ≥5.4.3 fix
  the CVEs but reintroduce the crash. The two problems were introduced
  in the same 5.4 rewrite and can't be separated by version choice
  alone.

**Decision**: per the user's explicit instruction, pin to the **newest**
Android-safe version of each artifact: `httpclient5:5.3.1`,
`httpcore5:5.3.6`, `httpcore5-h2:5.3.6` (all newer than the original
pre-Dependabot 5.2.1/5.2 pins, all confirmed clean). This knowingly
leaves the two httpcore5 CVEs unpatched — documented as an accepted
residual risk, not a resolved one. Full build (compile + lint + unit
tests) passes with this combination.

**Follow-up options for actually closing the CVE gap** (not done now,
tracked here for later): (a) ship a local shim class
`org.apache.hc.core5.util.ReflectionUtils` in yaacc's own source tree
that wraps the `jdk.net` probe in a try/catch, overriding the one
bundled in the jar — requires a custom Gradle step to exclude the
class from the dependency's jar at build time (AGP/D8 will otherwise
reject two classes with the same fully-qualified name as a duplicate-
class build error) and re-verification on every httpcore5 version bump;
or (b) restructure yaacc's embedded UPnP HTTP server to avoid
httpcore5's async `IOReactor` stack (`SingleCoreIOReactor`) entirely, if
the classic/blocking server implementation doesn't reach the same
`ReflectionUtils.<clinit>` path — unconfirmed, would need its own
investigation. Both are real engineering efforts, not quick fixes.
