# Known risks of the share feature (full technical list)

Moved here from `docs/tech.md` (published by the docs site) on 2026-10-26: the published page keeps only a neutral summary, the technical detail stays with the spec. Sources: decisions.md, security-review.md.


Not fixed in this branch; accepted or left for the owner (decisions.md, security-review.md). Public docs do not repeat the technical detail.

1. **Host names that resolve to loopback are not caught.** `LinkTargetPolicy` never resolves names (no DNS on the intake path), so a
   host name such as `localtest.me`, a DNS record that points at `127.0.0.1`, or a rebinding name passes the link policy.
2. **Redirects are not re-checked** by the parts that follow them on their own: the proxy's upstream fetch (`HttpURLConnection`
   with default redirects) and `MediaMetadataRetriever#setDataSource(String)`. Only the content-type fallback (`LinkContentType`)
   checks every hop. A public link can therefore redirect to a target the policy would have refused. Possible fix: no automatic
   redirects, at most 3 hops by hand through `LinkTargetPolicy`, and connect to a checked address.
3. **The link policy is enforced only when the proxy option is on.** With it off the receiver gets the link itself and fetches it from
   its own address; the phone fetches nothing for it, but the metadata read at intake still runs on the phone.
4. **A local video share hands the proxy URL, key included, to another app.** `MultiContentPlayer#startItem` starts an implicit
   `ACTION_VIEW` with the URL as data for whatever app handles the video type (only when "this device" was chosen). The handler
   already receives the bytes; what it additionally gets is a bearer URL that works until the share expires. It cannot reach any other
   share. Remedy would be an in-app video player. Audio and images do not do this (`LocalImagePlayer` uses an explicit intent to the
   app's own viewer).
5. **Unresolved providers are served one at a time.** All providers whose package cannot be resolved share one gate key
   (`ProviderKeys.UNRESOLVED`), because the manifest has no `<queries>` element, so package visibility on Android 11+ may hide the
   provider's package. Two shares from such providers cannot overlap.
6. **A provider that never returns pins a thread until the process ends.** A binder call cannot be interrupted; the gate bounds the
   damage to one thread and one of three places per provider (and at most 3 of the 4 pool threads), so three distinct hung providers
   (or one app with an unresolvable provider) can make further file shares fail with "busy" until a call returns. The same holds for
   a copy worker that stays stuck after the deadline (at most 3 are tolerated).
7. **The picker can be re-opened by a hostile app after a cancel.** There is no cool-down: an app that re-sends a share after every
   cancel can open the picker again. Every pick is user-visible, needs the user's OK, there is at most one pending share, and the
   second share while one waits is dropped with a toast. Decided: Backlog.
8. **Classic pre-ticks the saved selection for a shared file.** One tap on OK plays the sender's file on the user's usual receivers
   (default: this device), whereas the new layout ticks nothing. The picker shows only the cleaned name, no sender and no type.
   Owner decision of 2026-10-25; `AfterStartSelection.CLASSIC_MODE` and the initial-ticks argument are the places to change.
9. **A picked action in flight blocks the picker.** From a confirm until the action ends (a cold start, a link's network work, or a
   copy of up to 5 minutes) no second pick opens; a lost end callback expires after the copy maximum plus a minute.
10. **A rotation or process death with the picker open drops the share** (the pending action lives in the activity); the sender
    shares again. After process death the system re-delivers link TEXT: the new layout opens the picker again (nothing plays without
    OK); classic plays the link again, as it always did.
11. **Upstream I/O on the HTTP I/O threads.** A `HEAD` on an `HTTP_LINK` share and every range or server-seeking request run a
    blocking upstream request on one of the server's 8 I/O threads, and `HEAD` takes no response slot. A party that holds the key
    (and, with the restriction on, a chosen receiver's address) can occupy them. This predates the registry; the key and the address check gate it.
12. **Other open items.** Some VERBOSE traces of SOAP/GENA messages (`YaaccUpnpServerProtocolRequestHandler`, `ReceivingAction`) and
    DEBUG album-art URL logs are not passed through `ShareLogRedactor`; the default log level is above them. `ImageDownloader` accepts
    any scheme with no timeout or size cap. A share dropped for a type reason (`*/*`, no read flag, other types) is dropped silently.
    Two receivers with identical cleaned names show once in the receiver bar. A restricted share fails with 403 for a receiver that
    fetches from another address than the one it announced, or one whose description names a host (host names are not resolved):
    that is what the setting is for.

## Research findings about the pre-existing proxy (moved from docs/tech.md)

Moved on 2026-10-26 from "Research notes (Group 12)" (c) and finding 8 of `docs/tech.md`, which the docs site publishes. Line numbers are of the checkout before the build (`d8696971`). Finding 8 said: the existing range code is not reusable as is (suffix ranges, `Content-Range`, a malformed header throws, a path segment echoed into the 404 page, keys written to the log); see the text below. All of this concerns the proxy as released in 5.4.0 and was replaced for registry entries in Group 13.

#### (c) The existing proxy HTTP path, and what must change

All in `upnp/server/http/YaaccUpnpServerContentHttpHandler.java` (1774 lines) unless stated; [code].

**Request flow (`handle`, `:198-360`).**

- `:210` only `GET` and `HEAD` (others -> `MethodNotSupportedException`); `:220` `/health` answers 200 for
  everyone (used as a readiness probe below); `:227` anything but 2 or 3 path segments is 403.
- **Gate `:231-236`**: `contentServerEnabled` = `settings_local_server_provider_chkbx`, `contentProxyEnabled` =
  `settings_local_server_proxy_chkbx`; both false -> 403 for everything, including `/proxy/`. Neither reads
  `settings_local_server_chkbx` (finding 1). A registry branch must run **before** this gate; the proxy
  preference then only means "proxy shared http(s) links" and is consulted by `createPlayableItem`.
- `:266-267` logs every header at debug; `:268-271` `HttpRange.parseRangeHeader(...)`, which **throws
  `RuntimeException`** for a header it does not understand (`util/HttpRange.java:75-110`); the handler does not
  catch it. A registry path must catch and either ignore the header (200, full body) or answer 416.
- **`/proxy/` branch `:279-291`**: logs the whole request URI including the key (`:280`); the 3-segment form
  `/proxy/<urlencoded deviceId>/<key>` (`:283-287`, enables the `manage_external_seeking_<deviceId>` pref path)
  was **not produced** at the time of the research (the only producer was `UpnpClient.java:1646`, `/proxy/<key>`).
  **As built it is produced again** for server-side seeking, see "As built: serving"; `Uri.getPathSegments()` decodes segments, so `%2F` cannot smuggle a path, and a key is only a map key.
- `:312-320` not found: the page echoes `pathSegments.get(1)` unescaped into HTML and logs it (`:314-316`);
  replace with a constant body. `:322-325` logs type/length/ranges; `ContentHolder#getContentLength()` is called
  from the log line and again for `Content-Range`, which for an http target is a blocking `HEAD` to the remote
  host on a reactor thread each time (`:1187-1206`, 5 s timeouts).
- `:326-342` partial content: status 206 whenever `ranges` is non-empty; `Content-Range` is computed from
  `ranges.get(0)` as `start = range.start or 0`, `end = range.end or size-1` and **ignores suffix ranges**
  (`-N` gives `start 0`), so the header is wrong for them; no 416; header only if `fileSize > 0`.
- `:345-347` fixed headers `Connection: close`, `transferMode.dlna.org: Streaming`, `contentFeatures.dlna.org:
  DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=0170...`; `:358` `Accept-Ranges: bytes`; `:359` submit. `Connection:
  close` means each range request is a new TCP connection (so concurrent ranges are separate connections).

**Content holders.**

- `lookupProxyContent(String, List<HttpRange>, String)` `:772-878`: reads
  `PROXY_LINK_KEY_PREFIX + key` (`:775-777`) and `PROXY_LINK_MIME_TYPE_KEY_PREFIX + key` (`:783-785`) from the
  default preferences, logs the target URI (`:777`), applies the optional per-device server-side seeking, and
  returns `new ContentHolder(mimeType, targetUri, ranges, context)` (`:878`). The MIME comes from
  `MimeType.valueOf(targetMimetype)` (`:871-875`; an unparsable stored value throws).
- `ContentHolder` `:1147-1590`: for `http(s)` it opens the upstream with the same `Range` header, up to 3 tries
  with 1 s sleeps, on the reactor thread (`:1274-1330`); for other targets it reads a local file (`:1397`) and
  serves it (`AsyncEntityProducers.create(file, ...)`, `readRangeFormFile` `:1230` has an off-by-one in the end
  position, `createStreamingEntityProducer` `:1506` for files > 50 MiB) or a `DocumentFile` (`:1405-1490`,
  "range requests not implemented"). This non-http branch is what the design replaces for registry entries. `SafContentHolder` `:1005-1145` is the best template for a range
  producer (inclusive end, suffix handled, clamps) but computes the range separately from the header in
  `handle`.

**Writers/readers of `proxy_link_`** (grep of the whole repo): writer `UpnpClient.java:1647-1648`
(content key derived from the shared string, `:1645`, `:1657-1676`; proxy URL built at `:1646`, only when
`settings_local_server_proxy_chkbx` is on, `:1644`); readers `YaaccUpnpServerContentHttpHandler.java:776,
784`; bulk deleter on exit `Yaacc.java:135-139` (collects all keys starting with `proxy_link_mime_type` or
`proxy_link_`, removes them with `commit()`); the two constants `YaaccUpnpServerService.java:114-115`
(`"proxy_link_"`, `"proxy_link_mime_type"`; `PROXY_PATH = "proxy"` `:116` stays). No test, no doc other than
`docs/tech.md` lines 1182 and 1399 refers to them. Related and unchanged: `AVTransportPlayer.java:1385-1390,
1480-1487` recognizes a proxy item by `"/proxy/"` in the URI and takes the **last path segment** as the key for
`updateRendererPosition`/`server_position_<device>`; a random 22-character key keeps that working.
`settings_local_server_proxy_chkbx` is also read by `BackgroundIdleTimer.java:79`, `ServerListFragment.java:302`,
`YaaccUpnpServerControlActivity.java:99-104`, `YaaccUpnpServerService.java:377,385`.

**What must change for an in-memory registry with random keys.**

1. A process-wide `ShareRegistry` (e.g. a field of `Yaacc` next to `upnpClient`, reachable from the handler through
   `getApplicationContext()`: the handler is built with the application context, `YaaccUpnpServerService.java:787`).
2. `handle`: before the gate, if `pathSegments.size() == 2` and `PROXY_PATH.equals(segment0)`, validate the key
   syntax (22 chars of `[A-Za-z0-9_-]`), `registry.lookup(key)`, then the IP step, then serve; unknown, expired and
   malformed keys get the same answer. Delete the 3-segment form and `lookupProxyContent`. Uniform failures:
   403 for IP denied (payload-free log line with the remote address and no key), 404 constant body for the rest.
3. A holder for `HTTP_LINK` entries: the existing http branch of `ContentHolder`, fed with `entry.getTarget()`
   (a `String` URL) and `entry.getMimeType()`; its blocking upstream calls (5 s `HEAD`, 15 s connect, 60 s read, `:1187-1206`, `:1274-1330`)
   already run on reactor threads; do not add more. A holder for `LOCAL_FILE`: positional producer over the shared source with the range math below.
4. Range math in one pure class (recommended, see (g)): from `(List<HttpRange>, long length)` to
   `{200 | 206 | 416, start, endInclusive}`; used for both the status/`Content-Range` and the producer, so they
   cannot disagree. Rules: no `Range` or an unparsable one -> 200 full; multiple ranges -> first range only
   (RFC 9110 allows ignoring `Range`); `start >= length` -> 416 with `Content-Range: bytes */<length>`; end
   clamped to `length - 1`; suffix `-N` = last N bytes (N > length -> whole file); `-0` unsatisfiable.
5. `UpnpClient.createPlayableItem`: no preference writes; `ShareRegistry.register(...)` and the URL
   `http://<ip>:49157/proxy/<key>` (the IP from `InterfaceResolutionHelper.getIpAddress`, as now); for content
   shares the MIME and metadata come from the provider and the shared source (finding 3). `Yaacc.exit()` calls
   `registry.removeAll()` instead of the `proxy_link_` loop. One-time cleanup of stale `proxy_link_*` keys of
   existing installs at start (they hold the URLs users shared; `allowBackup` is already off).
6. A sweeper: `expire()` must run without requests (a scheduled task every ~30 s on the existing executor or a
   `Handler`), otherwise an expired descriptor stays open. `release(key)` when the share's player is removed
   (`UpnpClient.addPlayerListChangeListener`, `PlayerListChangeNotifier.Listener.onPlayerRemoved`; keep a
   `playerId -> key` map) [reasoned].
7. Logging: no key, URI, path or display name anywhere on this path (`:280`, `:773-777`, `:314-318`, and
   `PlayableItem` logging in the players).
