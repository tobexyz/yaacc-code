# Tasks: open work (Phase 4 and the owner's open items)

Plan: `requirements.md`, `design.md` (Phase 4, SI-1 ... SI-19), `decisions.md`, `known-risks.md`. As-built facts: `docs/tech.md`.

**Done: Groups 1-17 (see git log).** Phase 1 (Groups 1-4) device sheet, Phase 2 (Groups 5-11) classic and new layout, Phase 3
(Groups 12-16) sharing files and links and the per-action receiver picker, Group 17 (Phase 4 start) rule removal,
characterization tests, package move and trivial classes. The review and security-review files of the finished groups were removed
when this folder was compacted; the reviewers recreate `review.md` / `security-review.md` for the open groups, and the old cycles
are in git (also on the origin branch `backup/pre-squash-2026-10-26`).

## Open gate: Group 17
Group 17 is committed (`e0068f93`) and built green; its general review passed. **Its security review has NOT run.**
- [ ] Security review of Group 17 (`security-reviewer`) | the Group 17 diff `ecb26c59..HEAD -- yaacc/src/main`
  - **Accept**: PASS against SI-1, SI-2, SI-14, SI-16, SI-17 for the pieces Group 17 touched: `ShareRegistry#registerOrEvict`,
    `HeldFileSource.clampLength`, `ShareService` (`proxyUrl` via `java.net.URI`, `hasAddress`, `failureTextRes`, `prepare`),
    `ReceiverBarController.label`, the receiver-id copy in `UpnpClient`; no log line gained an ip, key or exception text.
  - **Verify**: the gate (G) below holds on the group's HEAD. Group 17 facts to check against: N = 28 characterization tests
    (17 `ShareServicePrepareTest`, 11 `UpnpClientLinkShareTest`), total `1501 0 0 0` = 1472 + 28 + 1 (zone-id case); lint 189 (72
    Error / 117 Warning) with the same issue ids per file as BASE; manifest diff 7 added / 0 removed; build files unchanged;
    public/protected signatures of `UpnpClient` and `ShareIntentHandler` identical; 17 mutants before and after, 16 killed both
    times, the survivor (clampLength offset at the end) is equivalent; test-name multiset differs from BASE by exactly 29 added
    names and the rename `noKeyOrPathMeansNoUrl` -> `noKeyMeansNoUrl`.
  - **Constraints**: `ShareService.proxyUrl` returns null for an address with an inner blank (the old builder put the blank into
    the URL); not reachable today, left as is (no drive-by fix). `"0.0.0.0"` still builds a URL (offline behaviour preserved).

## Phase 4 rules (every group)
Behaviour-preserving consolidation, BASE = `ecb26c59`. Per group: build -> `reviewer` PASS -> `security-reviewer` PASS
(sequential, max 3 cycles). Test-first where code is new; otherwise tests move with the code. The orchestrator keeps `PROGRESS.md`.
- **No new packages, no dependency change, no manifest change, no new string, no new preference key.** `ShareLimits` values, the
  public methods of `UpnpClient` and `ShareIntentHandler`, and every `@Test` assertion stay as they are, except where the ledger says so.
- **Moves, not rewrites**: renames and package moves are their own commits (`git mv`, similarity 100%); merges copy the absorbed
  code unchanged (same statements, same order); no drive-by fixes; known-risks items stay open. Commit messages carry
  `phase4-group-N`. No history rewriting, no force-push.
- **The Phase 4 gate (G)**, run in a clean checkout of the group's HEAD; all of it must hold:
  1. `ANDROID_HOME=/root/android-sdk ./gradlew :yaacc:cleanTestDebugUnitTest :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug` -> BUILD SUCCESSFUL.
  2. Tests: `cat yaacc/build/test-results/testDebugUnitTest/TEST-*.xml | sed -n 's/.*<testsuite [^>]* tests="\([0-9]*\)" skipped="\([0-9]*\)" failures="\([0-9]*\)" errors="\([0-9]*\)".*/\1 \2 \3 \4/p' | awk '{t+=$1;s+=$2;f+=$3;e+=$4} END{print t,s,f,e}'` -> `1501 0 0 0`.
  3. Lint: `grep -cE '^\s*<issue(\s|$)' yaacc/build/reports/lint-results-debug.xml` -> 189, of which `grep -c 'severity="Error"'` -> 72 and `grep -c 'severity="Warning"'` -> 117; no issue in a file that had none at BASE.
  4. Manifest: `git diff 071fc9d2 -- yaacc/src/main/AndroidManifest.xml | grep -E '^[+-][^+-]'` prints exactly the one `<intent-filter>` of `LauncherActivity` (SEND, DEFAULT, `audio/*`, `video/*`, `image/*`; 7 added, 0 removed). Build files: `git diff --quiet ecb26c59 -- build.gradle yaacc/build.gradle settings.gradle gradle.properties gradle yaacc/lint.xml && echo unchanged`.
  5. Public API: for `yaacc/src/main/java/de/yaacc/upnp/UpnpClient.java` and `yaacc/src/main/java/de/yaacc/browser/ShareIntentHandler.java`: `diff <(git show ecb26c59:$f | grep -E '^\s+(public|protected) ' | sed -E 's/\s*\{.*//' | sort) <(grep -E '^\s+(public|protected) ' $f | sed -E 's/\s*\{.*//' | sort)` prints nothing.
  6. Ledger: the multiset of test method names (`sed -n 's/.*<testcase name="\([^"]*\)".*/\1/p'` over the XML, sorted) of a throw-away worktree of BASE (scratchpad, never in the repo) diffed against HEAD shows exactly the ledger entries of the groups done so far (Group 17: +29 names, one rename), nothing else.
  7. `git diff -M --color-moved=dimmed-zebra ecb26c59..HEAD -- yaacc/src/main` read by the reviewer: every changed main line outside a moved block is a qualifier, visibility, javadoc or documented call-site change (listed in `review.md`).
  8. Mutation checks (below): at least 6 mutants of the classes the group touched, always including every security-critical class it moved, run before (group parent) and after (group HEAD) on throw-away worktrees; same mutants killed both times, by at least as many tests; table in `review.md` / `security-review.md`. A mutant that survives after but was killed before FAILs the group.
- The security reviewer checks the group against SI-1 ... SI-19 (`design.md`) and reads the moved code, not the summary.

### Mutation checks (candidates; the reviewer adapts them to the code in front of him)
| Class (after) | Mutants |
|---|---|
| `ShareRegistry` | key from 8 bytes; constant random; `lookup` returns an expired entry; `register` ignores `maxActive`; `evictOldestReleased` evicts a running entry; entry closed twice / never; `isWellFormedKey` accepts 21 chars; `registerOrEvict` retries without evicting |
| `ProxyAccessPolicy` | empty allowed set permits while restricting; missing remote address permits; no mapped-IPv4 normalization; zone id not stripped; `restrict=false` branch inverted; `allowedAddresses` adds loopback although the local device was not chosen |
| `ByteRangeResolver` | two ranges answered 206; invalid header answered 416; suffix longer than the file not clipped; digit overflow wraps |
| `PositionalSource` / `HeldFileSource` | `close()` blocks until reads end; real close runs twice; reads allowed after close; `clampLength` unclamped |
| `ShareConcurrencyLimiter` / `ShareProducers` | cap 4 -> 5; release not idempotent; `tryReacquire` always true; producer ignores `shareLive` |
| `ProxyShareResponder` | IP check skipped for HEAD or when a `Range` header is present; 404 body contains the key |
| `ServerSideSeeking` | invalid renderer id accepted; state map unbounded; estimate beyond 85% of the file |
| `SharedContentPolicy` | `file:` accepted; own authority accepted; wildcard MIME accepted; `validateStream` ignores `hasData` or the read flag or accepts 2 clip items; a throwing own-provider check returns "not ours" |
| `LauncherForwardPolicy` | SDK >= 35 with a denied caller still forwards; a throwing caller check forwards; text over the cap truncated instead of dropped |
| `LaunchNonces` | token reusable; TTL ignored; more than 8 outstanding |
| `PendingShare` | a second offer replaces; `take` twice returns data; wrong token accepted; timeout `>` instead of `>=` |
| `ProviderCalls` | gate permit never released; two calls per provider; gate gets an expiry; `isRefusedBusy` true for a timeout |
| `SharedFileOpener` | an abandoned open result not closed; probe runs without the type-string check on the name column |
| `WatchedCopy` / `BudgetedCopier` | budget checked only before the first chunk; free space not re-read; input not closed at the deadline; partial file kept on failure; more than 3 abandoned workers |
| `IncomingCache` | sweep deletes a non-matching name or follows a link; copy deleted before the delegate is closed; random name from a fixed seed |
| `DisplayName` | Cf characters kept; no length cap |
| `ShareLink` | `127.1` accepted; authority taken after the FIRST `@`; redirect hop not re-checked; `http:///x` accepted |
| `UnclaimedShares` | exactly-at-window counts as due; an early `claimed` forgotten; overflow key lost |
| `PendingPick` | a second `open` replaces the open pick; a stale id resolves; `abandon` does not cancel; confirm without receiver closes the pick |
| `ActionFlight` | a second `tryBegin` succeeds; watchdog disabled; a stale ticket ends the next flight |
| `ReceiverPickController` | after-start hook clears in classic / restores in the new layout |
| `ShareIntentHandler` (incl. gate) | payload admitted in a non-effective layout; intent consumed after validation instead of before; second pending share accepted |
| `ShareLogRedactor` | `%2f` alternative removed; case-insensitivity removed |
| `ShareService` | source not closed when the registry is full; registered although no address; failure text always "open failed" |

### Test ledger (Groups 18-21; each group fills "Actual")
| Id | Group | BASE test class (cases) | Moves to | Change in form |
|---|---|---|---|---|
| L8 | 18 | `RendererIdPolicyTest` (4), `RendererStateMapTest` (11), `SeekPositionCalculatorTest` (9) | `ServerSideSeekingTest` (10 -> 34) | class qualifiers |
| L9 | 18 | `ShareAddressesTest` (9) | `ProxyAccessPolicyTest` (73 -> 82) | `ProxyAccessPolicy.allowedAddresses` |
| L10 | 18 | `LinkTargetPolicyTest` (38), `HttpLinkTargetTest` (12), `LinkContentTypeTest` (14) | `ShareLink*Test` (topic-named files allowed) | `ShareLink` qualifiers; `HttpLinkTarget.of` is `ShareLink.of` |
| L11 | 18 | `RangeStreamerTest` (24) | same | `ChunkSink` is `RangeStreamer.Sink` |
| L12 | 18 | `ShareEntityProducerTest` (18), `GuardedEntityProducerTest` (13), `LengthOnlyEntityProducerTest` (4) | `ShareProducersTest` (+ `ProducerTestSupport`) | nested class qualifiers |
| L13 | 19 | `BoundedCallTest` (8+6), `BoundedCallGateTest` (13), `ProviderCallGateTest` (11), `ProviderKeysTest` (7) | `ProviderCallsTest` | `ProviderCalls` qualifiers |
| L14 | 19 | `ProviderProbeTest` (7) | `SharedFileOpenerTest` (new file) | `SharedFileOpener.probe` |
| L15 | 19 | `AbandonableCallTest` (11) | `WatchedCopyTest` (14 -> 25) | qualifiers |
| L16 | 19 | `CopyBudgetTest` (16) | `BudgetedCopierTest` (12 -> 28) | qualifiers |
| L17 | 19 | `IncomingFileNamesTest` (8), `DeleteOnCloseSourceTest` (7) | `IncomingCacheTest` (13 -> 28) | qualifiers |
| L18 | 20 | `SharedContentPolicyTest` (60), `SharedContentPolicyAuthorityTest` (2), `OwnProvidersTest` (6), `StreamIntakeTest` (11), `ShareUriPolicyTest` (10) | `SharedContentPolicy*Test` | `validateStream` / `isOwnAuthority` / `isAllowedText` on `SharedContentPolicy` |
| L19 | 20 | `SenderGrantCheckTest` (5) | `LauncherForwardPolicyTest` | `LauncherForwardPolicy.mayForward` |
| L20 | 20 | `SharedItemFactoryTest` (7) | `ShareServiceTest` | `ShareService.createItem` |
| L21 | 20 | `ShareIntakeGateTest` (13) | `ShareIntentHandlerGateTest` (new file, topic) | `ShareIntentHandler.admits` / `admitOrDiscard` |
| L22 | 21 | `ReceiverPickResultTest` (19), `PickSheetResultTest` (12) | `PendingPickTest` (24 -> 55) | `PendingPick.Decision`, `PendingPick.SheetGate` |
| L23 | 21 | `ActionFlightTest` (15, includes the guard cases) | same | `ActionFlight.guard` |
| L24 | 21 | `AfterStartSelectionTest` (10) | `ReceiverPickControllerAfterStartHookTest` (5 -> 15) | `ReceiverPickController` static plan |
| L25 | 21 | `PickTitleTest` (8) | `DeviceSheetFragmentTest` (new file) | `DeviceSheetFragment.nameOrFallback` / `subtitle` |

Expected total after Group 21: **1501** (1472 + 28 + 1). Anything else in the diff is a defect.

## Group 18: Serving side (registry neighbours, policies, link, producers)
- [ ] `ServerSideSeeking` absorbs `RendererIdPolicy`, `RendererStateMap`, `SeekPositionCalculator` | `ServerSideSeeking.java`, callers `ProxyShareResponder`, `YaaccUpnpServerContentHttpHandler`, `AVTransportPlayer`, `HttpLinkTarget`, tests
  - **Accept**: `ServerSideSeeking.isValidRendererId(String)` (charset `[A-Za-z0-9:._-]`, 1-200), a nested `ServerSideSeeking.RendererStates` (the BASE `RendererStateMap`, same bound and LRU, created only by `create`) and the position estimate are the BASE bodies; all callers use the new names; ledger L8 (34 cases in `ServerSideSeekingTest`)
  - **Verify**: gate; `grep -rn 'RendererIdPolicy\|RendererStateMap\|SeekPositionCalculator' yaacc/src` -> nothing
  - **Constraints**: SI-3 (renderer id checked after key and IP); per-share state bounded at 8 renderers; `AVTransportPlayer` still validates the id before building a URL or writing a preference
- [ ] `ProxyAccessPolicy` absorbs `ShareAddresses` | `ProxyAccessPolicy.java`, `ShareAddressResolver.java`, tests
  - **Accept**: `ProxyAccessPolicy.allowedAddresses(Collection<String> receiverHosts, boolean localDeviceChosen, Collection<String> localAddresses)` returns the BASE result of `ShareAddresses.build` (unmodifiable, normalized, loopback + own addresses only when the local device was chosen); ledger L9
  - **Verify**: gate; `grep -rn 'ShareAddresses' yaacc/src` -> nothing
  - **Constraints**: SI-4 (no `InetAddress.getByName`, zone ids stripped, mapped IPv4 normalized); run the `ProxyAccessPolicy` mutants
- [ ] `ShareLink` = `LinkTargetPolicy` + `HttpLinkTarget` + `LinkContentType` | `ShareLink.java` (`git mv` of `LinkTargetPolicy.java`, then merge), callers `UpnpClient`, `ProxyShareResponder`, `YaaccUpnpServerContentHttpHandler`, tests
  - **Accept**: `ShareLink` keeps every static method of `LinkTargetPolicy` and `LinkContentType` under the same names (`isAllowedTarget`, `find`, ...) and is the registry target type of an `HTTP_LINK` entry (`of(String)`, `isFetchable(String)`, `getUrl()`, `getRendererStates()`, `equals`/`hashCode`, a `toString()` that never shows the link); `instanceof HttpLinkTarget` in the responder and the handler becomes `instanceof ShareLink`; ledger L10
  - **Verify**: gate; `grep -rn 'HttpLinkTarget\|LinkTargetPolicy\|LinkContentType' yaacc/src` -> nothing
  - **Constraints**: SI-15; nothing is resolved, no `URL`/`URI`/`InetAddress` in the parser; `docs/tech.md` and `known-risks.md` names are updated in Group 22, not here
- [ ] `ChunkSink` becomes `RangeStreamer.Sink` | `RangeStreamer.java`, `RangeStreamerTest.java`
  - **Accept**: the interface is nested, `serve(...)` keeps both overloads; ledger L11; `ChunkSink.java` gone
  - **Verify**: gate; `grep -rn ChunkSink yaacc/src` -> nothing
  - **Constraints**: `serve` stays (test-only API, owner question Q2)
- [ ] `ShareProducers` (optional; drop it if the diff stops being reviewable) | the three producers -> `yaacc/src/main/java/de/yaacc/upnp/server/http/ShareProducers.java`, tests
  - **Accept**: three package-private nested classes with the BASE bodies; `ProxyShareResponder` uses them; ledger L12; `ProducerTestSupport` stays
  - **Verify**: gate
  - **Constraints**: do not unify the two guards (changes the SI-7 surface); lease release on construction failure stays in the responder
- [ ] Gate for Group 18 | n/a
  - **Accept**: gate (G) 1-8; file count 53; mutants for `ServerSideSeeking`, `ProxyAccessPolicy`, `ShareLink`, `ShareProducers`, `ProxyShareResponder`
  - **Verify**: as in "Phase 4 rules"
  - **Constraints**: reviewer, then security-reviewer
- Status (Group 18): not started.

## Group 19: Provider calls, file open and copy
- [ ] `BoundedCall` becomes `ProviderCalls` and absorbs `ProviderCallGate` and `ProviderKeys` | `ProviderCalls.java` (`git mv` first), `ShareIntentHandler.java`, `ShareService.java`, tests
  - **Accept**: `ProviderCalls` has the BASE `call`/`callDetailed`/`Result` API, a nested `Gate` (BASE `ProviderCallGate`, `createDefault()` unchanged: one call per provider, no expiry, `ShareLimits.PROVIDER_MAX_TRACKED`) and `ProviderCalls.keyOf(PackageManager, String authority)` (BASE `ProviderKeys.of`, same `"unresolved"` fallback); `ShareIntentHandler.PROBE_CALLS` and `ShareService.PROVIDER_CALLS` stay two instances with two gates and `ShareLimits.PROVIDER_POOL_THREADS` threads each; ledger L13
  - **Verify**: gate; `grep -rn 'BoundedCall\|ProviderCallGate\|ProviderKeys' yaacc/src` -> nothing
  - **Constraints**: SI-12; the permit is released in the pooled task's `finally` as at BASE; do not share a gate between the pools; `ProviderCalls` mutants
- [ ] `SharedFileOpener` absorbs `ProviderProbe` | `SharedFileOpener.java`, `ShareIntentHandler.java`, tests
  - **Accept**: `SharedFileOpener.probe(ContentResolver, Uri)` returns the BASE `Info` (type, name cut at 1024 chars, string columns only, `RuntimeException` -> null); ledger L14
  - **Verify**: gate; `grep -rn ProviderProbe yaacc/src` -> nothing
  - **Constraints**: the probe still runs only through `ProviderCalls` off the main thread (SI-12); no logging of the provider's text
- [ ] `WatchedCopy` absorbs `AbandonableCall`; `BudgetedCopier` absorbs `CopyBudget` | the four classes, `SharedFileOpener.java`, tests
  - **Accept**: `AbandonableCall` is a nested class of `WatchedCopy` (same cap, same `onAbandon` on the caller's thread, same `disposeLate`); `CopyBudget` is a nested `BudgetedCopier.Budget` with the same `Decision` values and check order ("too big wins over no space"); ledger L15, L16
  - **Verify**: gate; `grep -rn 'AbandonableCall\|CopyBudget' yaacc/src` -> only the nested names
  - **Constraints**: SI-13: the budget is asked before EVERY chunk, `COPY_MAX_STUCK_WORKERS` stays 3, an abandoned worker's output stream is still closed from the caller; mutants for both
- [ ] `IncomingCache` absorbs `IncomingFileNames` and `DeleteOnCloseSource` | `IncomingCache.java`, callers, tests
  - **Accept**: `IncomingCache.randomName(Consumer<byte[]>)` / `isOurIncomingName(String)` and a nested `IncomingCache.HeldCopy` (BASE `DeleteOnCloseSource`: close the delegate first, then delete); ledger L17
  - **Verify**: gate; `grep -rn 'IncomingFileNames\|DeleteOnCloseSource' yaacc/src` -> nothing
  - **Constraints**: SI-13: names stay `share-<32 hex>.bin` from the injected `SecureRandom` bytes, `CREATE_NEW`, the sweep deletes only exact-shape regular files and never follows links
- [ ] Gate for Group 19 | n/a
  - **Accept**: gate (G) 1-8; file count 46; mutants for `ProviderCalls`, `SharedFileOpener`, `WatchedCopy`, `BudgetedCopier`, `IncomingCache`, `HeldFileSource`
  - **Constraints**: reviewer, then security-reviewer; `HeldFileSource`/`SharedFileOpener` I/O has no unit test, so the reviewer reads those diffs line by line
- Status (Group 19): not started.

## Group 20: Intake and the share service
- [ ] `SharedContentPolicy` absorbs `OwnProviders`, `StreamIntake`, `ShareUriPolicy` | `SharedContentPolicy.java`, `LauncherActivity.java`, `LauncherForwardPolicy.java`, `ShareIntentHandler.java`, tests
  - **Accept**: `SharedContentPolicy.isOwnAuthority(PackageManager, String ownPackage, String authority)` and `fileProviderAuthority(String)` (BASE `OwnProviders`, fail closed), `validateStream(...)` (BASE `StreamIntake.validate`, same checks in the same order, `READ_FLAG`), `isAllowedText(String)` / `MAX_TEXT_LENGTH` (BASE `ShareUriPolicy`); string-level parsers stay string-level; ledger L18. Optional (owner Q8) and only if it deletes wiring: `validateStream` takes `PackageManager` + own package instead of the `Predicate`/`ownAuthority` pair (the 11 `StreamIntakeTest` cases change their arrange code only)
  - **Verify**: gate; `grep -rn 'OwnProviders\|StreamIntake\|ShareUriPolicy' yaacc/src` -> nothing
  - **Constraints**: SI-8, SI-10 (any provider of this app refused, a throwing check is refused, the intent is consumed before validation); no `android.net.Uri` parsing in the policy; `SharedContentPolicy` mutants
- [ ] `LauncherForwardPolicy` absorbs `SenderGrantCheck` | `LauncherForwardPolicy.java`, `LauncherActivity.java`, tests
  - **Accept**: `LauncherForwardPolicy.mayForward(int sdkInt, IntSupplier permissionCheck)` and `FIRST_SDK_WITH_CALLER_CHECK` are the BASE bodies; ledger L19
  - **Verify**: gate; `grep -rn SenderGrantCheck yaacc/src` -> nothing
  - **Constraints**: SI-8 (below 35 allowed; 35+ fails closed on denial, null check or any `RuntimeException`); `LauncherActivity` changes only the call
- [ ] `ShareService` absorbs `ShareAddressResolver`, `SharedItemFactory`, `HttpServerProbe` | `ShareService.java`, `UpnpClient.java`, tests
  - **Accept**: `ShareService.allowedAddresses(UpnpClient, Context, Set<String> receiverIds)` (BASE `ShareAddressResolver.resolve`), `ShareService.createItem(...)` (BASE `SharedItemFactory.create`), `ShareService.isHttpServerRunning()` (BASE `HttpServerProbe.isRunning`, 500 ms loopback connect); `UpnpClient#isHttpServerRunning()` stays public and delegates; `UpnpClient#createHttpLinkShare` stays in `UpnpClient` and calls the new homes plus `registerOrEvict`; ledger L20
  - **Verify**: gate; `grep -rn 'ShareAddressResolver\|SharedItemFactory\|HttpServerProbe' yaacc/src` -> nothing; the Group 17 characterization tests still pass unchanged
  - **Constraints**: SI-4 (allowed set from the ids captured at confirm, local device rule), SI-14 (ownership pairing in `prepare`); do not move `discardShareOf` or `createHttpLinkShare` out of `UpnpClient`
- [ ] `ShareIntentHandler` absorbs `ShareIntakeGate` | `ShareIntentHandler.java`, `TabBrowserActivity.java`, `BrowserActivity.java`, tests
  - **Accept**: `ShareIntentHandler.admits(Layout own, Layout effective)` and `admitOrDiscard(Layout, Layout, Intent)` are the BASE bodies and are what both activities call from `handOverShare`; ledger L21
  - **Verify**: gate; `grep -rn ShareIntakeGate yaacc/src` -> nothing
  - **Constraints**: SI-10; the rule stays the same as `BrowserLayoutSwitch.shouldRelaunch`; the public API list is unchanged (the new methods are package-private/static as the gate was)
- [ ] Gate for Group 20 | n/a
  - **Accept**: gate (G) 1-8; file count 38; no `upnp/server/share` anywhere in `yaacc/src`; mutants for `SharedContentPolicy`, `LauncherForwardPolicy`, `ShareIntentHandler`, `ShareService`, `LaunchNonces`, `PendingShare`
  - **Constraints**: reviewer, then security-reviewer; the security reviewer re-walks the launcher -> nonce -> browser -> pending share chain on the real code
- Status (Group 20): not started.

## Group 21: Receiver picker
- [ ] `PendingPick` absorbs `ReceiverPickResult` and `PickSheetResult` | `PendingPick.java`, `DeviceSheetFragment.java`, `ReceiverPickController.java`, tests
  - **Accept**: `PendingPick.Decision` + `PendingPick.decide(boolean, Set<String>)` (BASE `ReceiverPickResult`, incl. `keepPickerOpen()`, `receiversToUse()`, `startPlayback()`, `clearSelectionAfterStart()`), `PendingPick.SheetGate` (BASE `PickSheetResult`: `confirm`, `dismissed`, `silence`, `isDelivered`, `confirmedIds`); `PendingPick.Callbacks` and `Outcome` unchanged; ledger L22
  - **Verify**: gate; `grep -rn 'ReceiverPickResult\|PickSheetResult' yaacc/src` -> nothing
  - **Constraints**: SI-11 (a second `open` never replaces, stale/forged ids ignored, state cleared before callbacks, one answer per sheet); the three test-only members stay (Q2); `PendingPick` mutants
- [ ] `ActionFlight` absorbs `GuardedPickedAction` | `ActionFlight.java`, `ReceiverPickController.java`, tests
  - **Accept**: `ActionFlight#guard(PickedAction)` returns the `PendingPick.Callbacks` the BASE `new GuardedPickedAction(flight, action)` was (flight begun at confirm, refused -> `onCancelled`, a throwing action ends the flight and rethrows); `PickedAction` stays a top-level interface; ledger L23
  - **Verify**: gate; `grep -rn GuardedPickedAction yaacc/src` -> nothing
  - **Constraints**: SI-11 (watchdog `ACTION_FLIGHT_MAX_MILLIS`, a stale ticket cannot end the next flight)
- [ ] `ReceiverPickController` absorbs `AfterStartSelection`; `DeviceSheetFragment` absorbs `PickTitle` | `ReceiverPickController.java`, `DeviceSheetFragment.java`, `TabBrowserActivity.java` (javadoc), tests
  - **Accept**: `ReceiverPickController.afterStartHook(UpnpClient, boolean newLayout)` keeps its signature and builds the plan (new layout clears; classic restores a copy of the previous ids, null/empty ids dropped; `CLASSIC_MODE` stays the one constant that decides) inside the controller; `DeviceSheetFragment.nameOrFallback` / `subtitle` are the BASE `PickTitle` bodies (static, package-private, still through `MiniPlayerPresenter.cleanText`); ledger L24, L25
  - **Verify**: gate; `grep -rn 'AfterStartSelection\|PickTitle' yaacc/src` -> nothing
  - **Constraints**: SI-11, SI-19 (`filterTouchesWhenObscured` untouched, fixed fallback text); the new layout never restores, classic never clears
- [ ] Gate for Group 21 | n/a
  - **Accept**: gate (G) 1-8; file count 33; mutants for `PendingPick`, `ActionFlight`, `ReceiverPickController`, `ReceiverBarController`
  - **Constraints**: reviewer, then security-reviewer
- Status (Group 21): not started.

## Group 22: Documentation and spec update
- [ ] Update documentation for Phase 4 | `docs/tech.md`, this folder
  - **Accept**: `docs/tech.md` "Sharing local files" names the new classes everywhere (overview table, "As built" subsections, "Known limitations", "Not verified on a device") and has a short "Phase 4: what moved" note with the old -> new table; `known-risks.md` names (`LinkTargetPolicy`, `ProviderKeys.UNRESOLVED`, `ShareIntakeGate`, ...) point to the new classes; statuses of Groups 17-21 above filled with the actual numbers and ledger; `design.md` Phase 4 gets the final class list; `decisions.md` gets the as-built entry and the owner's answers to Q1/Q2/Q7; `CHANGELOG.md`, `README.md`, `docs/index.md`, `docs/settings.md` are NOT changed (no user-visible change)
  - **Verify**: `grep -rnE 'ShareProxyUrl|ShareFailureMessage|ShareRegistrar|DescriptorWindow|ReceiverBarLabel|ReceiverSelection|RendererIdPolicy|RendererStateMap|SeekPositionCalculator|ShareAddresses|ShareAddressResolver|HttpLinkTarget|LinkTargetPolicy|LinkContentType|ChunkSink|StreamIntake|SenderGrantCheck|OwnProviders|ShareUriPolicy|ShareIntakeGate|ProviderProbe|ProviderKeys|ProviderCallGate|BoundedCall|AbandonableCall|CopyBudget|IncomingFileNames|DeleteOnCloseSource|SharedItemFactory|HttpServerProbe|SharedFileService|ReceiverPickResult|PickSheetResult|GuardedPickedAction|AfterStartSelection|PickTitle|upnp\.server\.share' docs README.md CHANGELOG.md yaacc/src` prints only the "Phase 4: what moved" table and history notes; `grep -rn 'TODO\|FIXME\|PLACEHOLDER' docs/tech.md || true` shows nothing plan-related
  - **Constraints**: nothing descoped is documented; keep exploit-level detail out of `docs/` (`known-risks.md` stays the place)
- [ ] Final verification | n/a
  - **Accept**: gate (G) 1-8 on the final HEAD; `ls yaacc/src/main/java/de/yaacc/share | wc -l` -> 27; share/pick files in `browser/` -> 4, in `upnp/server/http/` -> 2 (1 if `ShareProducers` was dropped: then 4); the on-device checklist below is handed to the owner again; `.claude/specs/currentspec.md` is deleted when all groups pass
  - **Constraints**: reviewer, then security-reviewer, full-diff read `git diff -M ecb26c59..HEAD -- yaacc/src/main`
- Status (Group 22): not started.

## Owner's open items
Questions and checks only the owner can answer or run. None of the share, layout or theme work was run on a device.
1. **Device checks** (reasoned or unit-tested only). Share chain: sender -> launcher -> browser with a real grant on Android 11-16 and
   real senders (gallery, files, messengers); `getInitialCaller()` via the system chooser on API 35+; the launcher's
   `SecurityException` fallback; whether `resolveContentProvider` sees a freshly forwarded provider on Android 11+; whether closing a
   provider stream wakes a blocked read; a descriptor staying valid after the grant ends and the sender dies; which providers return
   pipes; the address real renderers fetch from versus their SSDP location; the proxy URL not on the notification or lock screen.
   Picker: opens on play / play-all / row tap / add-to-playlist; Cancel, back, tap outside and swipe change nothing; OK with nothing
   ticked shows the hint (TalkBack); the pick sheet layout with 10+ receivers, on a small screen and in landscape (buttons stay
   visible, list scrolls); D-pad and Android TV focus; dark mode; receivers appearing while it is open; rotation with it open (closes,
   no crash); a share arriving while the browser is in the background; the classic share path (selection ticked, restored after the
   start). Layouts: switching both directions with players running; a stale notification tap after a switch; rotation in both layouts;
   process death; the 5-minute idle exit; TV launcher entry and D-pad reach; launcher re-tap; splash screen on Android 12+; the toolbar
   timer, mini-player rows (several players, scrolling, buttons incl. exit, tap to open) and the sheet at every level; the classic
   footer in portrait and landscape. Look: card grid spacing, thumbnail sharpness and column count (portrait, landscape, tablet,
   multi-window); toolbar colors and icons in both themes (dark title bar, light icons, also in the image viewer); gray controls.
2. **Decided 2026-10-28** (owner): classic keeps pre-ticking the saved receivers for a shared file (known-risks 8 stays as accepted);
   the local video share keeps the implicit `ACTION_VIEW` and no in-app video player is built (known-risks 4 accepted); add to playlist
   creates a player for each chosen receiver that has none (done, `PlaylistTargets`); `.claude/specs/` is NOT merged into the main branch
   (last step before the merge: one commit that removes the folder from the tree, so a squash merge carries none of it); Phase 4 stage 2
   (27 -> 18 files) is not done; the test-only members stay with their tests; the offline address `"0.0.0.0"` is a separate item; several
   player sessions per renderer stay as they are. Q8 (`StreamIntake` taking a `PackageManager`) stays optional in Group 20.
3. **Still open**: whether the receivers of the last confirmed pick are pre-ticked in the picker of the new layout too (today the new
   layout ticks nothing and clears the selection after every action); the older product questions about the local video session row and
   the sheet on TV long term.
4. **Backlog worth keeping** (discuss before any work; any new input type keeps the share intake rules: fresh allowlisted forward from the
   exported launcher, capped input, no payload in logs, no intent or path built from shared data, and needs a security review):
   - Share UX: (the notice when a shared file is dropped is done, `share_not_accepted`); show the cleaned host for a link
     instead of "Shared link"; the picker re-opening after a rotation (needs the pending share to outlive the activity); a cool-down
     after a cancelled pick (known-risks 7); a cancel for a running picked action or a "no bytes for N seconds" copy limit (known-risks 9).
   - Link intake: tighten `ShareUriPolicy` (require `://`, reject whitespace and control characters) and add an overall deadline around the
     link metadata reads (known-risks 20, 21); classic still auto-plays a shared link (known-risks 19) and waits blindly for 3 s + 3 s.
   - Hardening nits: `intakeBusy` static; one `try` per removal in `consume`/`discard`, nonce first (known-risks 25); the legacy
     media-server answer and server-wide HTTP limits (known-risks 13); `ImageDownloader`/`IconDownloadTask` scheme allowlist, timeouts and
     a size cap; some VERBOSE/DEBUG traces not redacted (known-risks 12).
   - Layout and sheet: stale notification `PendingIntent`s may leave a second browser instance after a layout switch
     (`FLAG_ACTIVITY_REORDER_TO_FRONT` or rebuild the notification); fixing classic back; the sheet does not add or remove receivers
     that appear while it is open and returns to its initial position after rotation; `RendererStatusMonitor` polls on one thread (one
     slow renderer delays all status); `PlayerService#getPlayer()` returns a mutable view; remote devices claiming `LOCAL_UID` are not
     dropped; `PlayerListFragment`/`ReceiverListFragment` never remove their listener; `Yaacc.startShutdownTimer(0)` exits at once (the
     new toolbar cannot reach it).
