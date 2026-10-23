# Security Review: Browse card-grid view (content tab)

## Cycle 1 — 2026-10-17
Reviewing: Groups 1-N (diff `57caa8a..HEAD`, commits `b163c35..4ad002f`), after the general
review (`review.md`) PASSED with zero Critical/Warning.

### Critical

- [`yaacc/build.gradle:22-27`] This diff pins `org.apache.httpcomponents.core5:httpcore5` down
  from `5.4.4` to `5.2` (and `httpclient5` from `5.6.5` to `5.2.1`), to fix an unrelated Android
  ART `NoSuchMethodError` crash (`DefaultHttpClientConnectionOperator`'s static initializer
  probing `jdk.net.Sockets`/`ExtendedSocketOptions`, which Android blocks). `httpcore5 5.2` falls
  inside the publicly disclosed vulnerable range for two memory-exhaustion denial-of-service
  CVEs that the previously-pinned `5.4.4` was already patched against:
  - **CVE-2026-54399** — HTTP/1.1 header-parsing memory-exhaustion DoS, affecting
    `httpcore5` versions before `5.4.3` (CVSS 7.5, High). `5.2` < `5.4.3`, so it is affected;
    `5.4.4` was not.
  - **CVE-2026-54428** — HTTP/2 HPACK-decoder memory-exhaustion DoS (header list size limit
    not enforced before the SETTINGS ack), affecting `5.4.2` and earlier, fixed in `5.4.3`.
    `5.2` is affected.

  This is a real regression, not a hypothetical one: `httpclient5`/`httpcore5` is yaacc's actual
  HTTP transport for talking to other UPnP/DLNA devices on the network
  (`yaacc/src/main/java/de/yaacc/upnp/server/http/HttpRequestSender.java` builds a classic
  `CloseableHttpClient` from `org.apache.hc.client5`/`org.apache.hc.core5`, used by
  `YaaccUpnpServerContentHttpHandler`/`YaaccUpnpServerProtocolRequestHandler` to exchange HTTP
  requests/responses with other devices on the LAN). Any device on the same network segment —
  not necessarily the device the user intentionally browses — is a peer this HTTP stack parses
  responses/headers from, so CVE-2026-54399 (HTTP/1.1 header parsing) is a credible DoS vector
  against the app from an untrusted or compromised LAN peer. (HTTP/2/HPACK is less clearly
  reachable here since the classic blocking client is used rather than the async H2 module, but
  the library itself ships the vulnerable decoder regardless.)

  **Remediation:** Do not pin below `5.4.3` for either artifact. The ART crash and the CVE fix
  need to be reconciled, not traded off against each other — e.g. verify whether a version
  `>= 5.4.3` (ideally the already-used `5.4.4`, or a newer patched release) still hits the
  `NoSuchMethodError`, since the comment's claim that "5.2.1/5.2 predate this" only establishes
  that those old versions happen to avoid the crash, not that they're the only versions that do.
  If no version both avoids the ART crash and is patched for these CVEs, prefer a narrower fix
  (e.g. excluding/overriding the specific static initializer behavior, or catching/guarding the
  hidden-API probe) over downgrading past two patched, scored DoS CVEs. This blocks the gate
  until resolved — update `docs/tech.md`/`decisions.md` with whatever version is finally chosen
  and why.

### Warning

None beyond the Critical above.

### Suggestion

- [`yaacc/src/main/java/de/yaacc/browser/CardGridSpanCount.java:38`] `computeSpanCount` divides
  by `targetCardWidthPx` with no zero/negative guard. `availableWidthPx == 0` is already handled
  safely (`0 / x == 0`, then `Math.max(2, 0)` → `2`), and `targetCardWidthPx` is always
  `(int) (160 * density)` from `ContentListFragment.targetCardWidthPx()`, so it can only be `0`
  if `DisplayMetrics.density` is `0` on some device/emulator — not attacker-controlled, not
  reachable from untrusted network/server data, so this is not a security finding, but a defensive
  `Math.max(1, targetCardWidthPx)` (or similar) would avoid a theoretical `ArithmeticException`
  crash on a pathological display-metrics configuration.

- [`yaacc/src/main/java/de/yaacc/util/image/ImageDownloader.java:63-121`] Not introduced or
  changed by this diff, but adjacent to it: `decodeSampledBitmapFromStream` sets
  `BitmapFactory.Options.outWidth`/`outHeight` before calling `BitmapFactory.decodeStream(...)`
  with `inJustDecodeBounds = false` and `inSampleSize = 1` fixed — those two fields are *output*
  fields the decoder overwrites, not inputs that constrain the decode size, so the source image
  is always decoded at its full native resolution and only downscaled afterwards via
  `Bitmap.createScaledBitmap`. This diff's new `IconDownloadTask(ImageView,
  BrowseContentItemAdapter, int, int)` overload and the 48dp→96dp size bump for CARD mode do
  **not** make this worse (the full-resolution decode already happened at 48dp request size
  too, under the untouched 3-arg/2-arg constructors), so it's not a regression this diff is
  responsible for. It is, however, a pre-existing resource-exhaustion path worth a follow-up
  ticket: a compromised/malicious UPnP device could serve an oversized album-art/thumbnail image
  and force a large in-memory decode regardless of the tiny requested icon size, in both LIST and
  CARD mode alike.

### Verified — no new risk found

- **DIDL text/URI binding (item 1 of the review brief):** `holder.name.setText(currentObject
  .getTitle())` in `BrowseContentItemAdapter.onBindViewHolder` is unchanged by this diff — the
  card layout (`browse_content_item_card.xml`) reuses the exact same view IDs/types
  (`TextView`/`ImageView`) as the list layout, bound through the same shared `ViewHolder` code
  path, with no new `Html.fromHtml`, `WebView`, `setText(..., TextView.BufferType.SPANNABLE)`,
  or raw URI-to-view binding introduced. Server-supplied titles/dates reach the view exactly as
  safely (or unsafely) as they did before this diff — no change in escaping/sanitization.
- **SharedPreferences (item 2):** `settings_browse_view_mode_key` is read/written with the same
  `valueOf`-with-try/catch-fallback pattern as `settings_sort_order_key`
  (`BrowseContentItemAdapter.readPersistedViewMode`/`ContentListFragment.initViewModeToggle`),
  stores only a non-sensitive enum name, uses the app's default (non-world-readable)
  `SharedPreferences`, and has no injection risk (no raw string concatenation into a query or
  command — `PreferenceManager.getDefaultSharedPreferences` keys are static resource strings).
- **`IconDownloadTask` 4-arg constructor width/height (item 3):** confirmed the constructor
  itself introduces no new resource-exhaustion risk beyond the pre-existing one noted above —
  it only changes which fixed, small, locally-computed `int` (48dp or 96dp, in pixels) is passed
  through to the unchanged `doInBackground`/`ImageDownloader`/`IconDownloadCacheHandler` chain.
  Both values are compile-time constants (`LIST_ICON_SIZE_DP`/`CARD_ICON_SIZE_DP`) converted via
  `dpToPx`, never derived from server/DIDL data, so there's no attacker-controlled width/height
  value being passed in like the brief's hypothetical.
- **`GridLayoutManager`/span-count (item 4):** see Suggestion above — no divide-by-zero risk
  from `contentList.getWidth()` being `0` before layout (numerator-zero case is safe); the only
  theoretical division-by-zero requires `density == 0`, not a network- or attacker-influenced
  value.

### Verdict: FAIL

## Cycle 2 — 2026-10-17
Reviewing: commit `2a59179` (the fix for Cycle 1's Critical finding), independently
re-verified rather than taken on the commit message's or `decisions.md`'s word.

### Scope check — `git show --stat 2a59179`

Confirmed the fix commit touches exactly four files: `yaacc/build.gradle` and three
spec docs (`decisions.md`, `security-review.md`, `tasks.md`). No feature Java/XML file
is touched, so Cycle 1's "Verified — no new risk found" section and its two Suggestions
(span-count divide guard, `ImageDownloader` full-resolution decode) still stand as-is
and are not re-litigated here.

### Independent verification performed

1. **Declared versions** (`yaacc/build.gradle:30,38,44`): `httpclient5` is pinned to
   `5.2.1`, `httpcore5` to `5.4.4`, and `httpcore5-h2` is now explicitly pinned to
   `5.4.4` as its own `implementation` line (not left to inherit from `httpclient5`'s
   transitive `5.2` request). All three declarations match what `decisions.md` claims.

2. **Resolved dependency graph** — ran
   `./gradlew :yaacc:dependencies --configuration releaseRuntimeClasspath --offline`
   myself (not reused from the prior cycle) and grepped the full output for every
   `httpcore5`/`httpclient5` occurrence:
   ```
   +--- org.apache.httpcomponents.client5:httpclient5:5.2.1
   |    +--- org.apache.httpcomponents.core5:httpcore5:5.2 -> 5.4.4
   |    +--- org.apache.httpcomponents.core5:httpcore5-h2:5.2 -> 5.4.4
   |    |    \--- org.apache.httpcomponents.core5:httpcore5:5.4.4
   +--- org.apache.httpcomponents.core5:httpcore5:5.4.4
   +--- org.apache.httpcomponents.core5:httpcore5-h2:5.4.4 (*)
   ```
   There is exactly one transitive request path for each artifact (both originating
   from `httpclient5`'s own POM, requesting `5.2`), and both are overridden to `5.4.4`
   — confirmed by the `->` conflict-resolution arrow, not just a bare declared number.
   No second, un-overridden path exists anywhere else in the tree (full-log grep for
   `core5` returned only these lines plus the identical subtree repeated for the
   `androidx.appcompat` branch, which pulls the same resolved versions). `5.4.4 ≥
   5.4.3`, so both CVE-2026-54399 (httpcore5) and CVE-2026-54428 (httpcore5-h2 HPACK)
   are patched in every resolved copy on the runtime classpath — not merely the
   top-level declaration.

3. **`httpclient5:5.2.1` jar inspection** — located the actual resolved jar in the
   Gradle module cache (`~/.gradle/caches/modules-2/files-2.1/.../httpclient5-5.2.1.jar`,
   hash `c900514d3446d9ce5d9dbd90c21192048125440`), extracted it, and ran
   `javap -c -p` against
   `org/apache/hc/client5/http/impl/io/DefaultHttpClientConnectionOperator.class`
   directly (not trusting the version number or the prior cycle's note alone).
   Findings:
   - The class has no `<clinit>` (static initializer) at all in this version.
   - `grep -rl "jdk/net"` and `grep -rl "ExtendedSocketOptions"` across every `.class`
     file in the entire extracted jar (not just this one class) both returned zero
     matches.
   This independently confirms the Android ART-crashing hidden-API probe genuinely
   does not exist in the resolved `5.2.1` jar — the fix's premise holds.

4. **Sanity pass for the same/other CVEs elsewhere in the dependency graph** — searched
   the full `releaseRuntimeClasspath` resolution log for every `org.apache.httpcomponents`
   occurrence; `httpclient5`, `httpcore5`, and `httpcore5-h2` are the only three
   artifacts from that group anywhere in the graph (no stray `httpcore5-h2` or
   `httpasyncclient`/`httpclient5-fluent` pulled in by a different library at an older
   version). One unrelated, pre-existing observation: `httpclient5` transitively
   requests `slf4j-api:1.7.36`, which resolves up to `1.8.0-beta4` (pulled in by
   another dependency elsewhere in the graph) — this predates commit `2a59179`
   entirely (same resolution appears on the pre-fix `57caa8a` base), is unrelated to
   either CVE in scope, and is not introduced or touched by this fix, so it's noted
   but not a finding against this commit. No other new CVE exposure was introduced by
   this fix itself.

5. **Full build** — ran `./gradlew build --offline` after the fix: `BUILD SUCCESSFUL`
   (94 actionable tasks, `:yaacc:test`, `:yaacc:lint`, `:yaacc:check`, `:yaacc:build`
   all completed without failure, `assembleRelease` and `assembleDebug` both produced
   artifacts).

### Critical

None.

### Warning

None.

### Suggestion

- Carried over from Cycle 1 (unchanged by this commit, still open as non-blocking
  follow-ups): `CardGridSpanCount.java:38` defensive `Math.max(1, targetCardWidthPx)`
  guard, and the pre-existing `ImageDownloader` full-resolution-decode-before-downscale
  resource-exhaustion follow-up ticket.
- The `slf4j-api` transitive resolution to a beta version (`1.8.0-beta4`, noted in
  verification step 4 above) is pre-existing and out of this fix's scope, but worth a
  follow-up to pin it to a stable release at some point.

### Verdict: PASS
