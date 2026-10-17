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
