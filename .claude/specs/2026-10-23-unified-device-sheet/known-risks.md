# Known risks of the share feature (full technical list)

Moved here from `docs/tech.md` (published by the docs site) on 2026-10-26: the published page keeps only a neutral summary ("Known limitations"), the technical detail stays with the spec. Sources: decisions.md, security-review.md. These items are not fixed in this branch; they are accepted or left for the owner. The public docs do not repeat the technical detail.

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

13. **Legacy media-server answer and server-wide HTTP limits (pre-existing) (softened 2026-10-26).** The old "resource not found" answer of
    `YaaccUpnpServerContentHttpHandler` (the branch for the media-server paths, not `/proxy/`) puts a request path segment into its
    HTML response without escaping it. The HTTP server also runs with the default `Http1Config` (no `maxLineLength` or
    `maxHeaderCount`) and a 5-minute socket timeout that also applies before a request line has arrived. Fix when the handler or the
    server is next configured: answer a constant body and set both limits. Not part of the share feature; the `/proxy/` path itself
    answers with constant bodies.
14. **A hostile app on the phone, and the confused-deputy residual.** When "this device" is a chosen receiver, loopback and the phone's
    own addresses pass the address step, so another app on the phone is stopped only by the 128-bit key. The app does not publish the
    key (notification, `MediaSession` metadata and Media3 controllers carry no proxy URL; reasoned, not observed on a device), except
    for the local video case in item 4. Separately, yaacc holds broad media permissions and SAF grants, and Android only checks that
    yaacc itself can read a forwarded `content:` URI. Below Android 15 nothing verifies that the real sender holds the access; on
    Android 15+ only the launcher path asks (`getInitialCaller()`, not device-verified). Accepted by the owner; mitigations are the
    audio/video/image type check, the receiver pick that shows the cleaned name, and the address and expiry limits.
15. **A receiver's `<URLBase>` host is allowed.** `ShareAddressResolver` adds the host of a chosen receiver's descriptor URL AND of
    its `<URLBase>` to the allowed address set. The URL base is chosen by the receiver's own description, so a receiver the user picked
    can name another address, for example this phone's own LAN address, which would admit local apps. It already holds the key.
    Possible fix: drop the URL base unless its host equals the descriptor host.
16. **Private LAN ranges are allowed by design.** `LinkTargetPolicy` refuses loopback, link-local and the phone's own addresses but
    accepts private ranges (NAS and media-server links). With the proxy option on, a shared link can therefore make the phone fetch
    a page of a LAN device (a router or admin page) and relay the answer to the chosen receivers. The answer does not go back to the
    sender, and the pick shows only the fixed text "Shared link". Items 1-3 describe how names and redirects widen this.
17. **Abandoned copy workers keep their space.** A copy worker that is stuck at the deadline is abandoned (at most 3). The caller
    closes its streams and deletes the partial file, but a worker that stays blocked in the provider can hold what it already
    wrote allocated until the process ends, and that space is invisible to the cache budget: up to 3 x 512 MiB. The abandon path
    closes the output stream to reduce this; whether closing a provider stream wakes a blocked read is a device check.
18. **The share name is in the picker's saved state.** The cleaned display name is passed to the pick sheet as a fragment argument and
    is therefore part of the saved instance state. It is not a URI, token or key, and a restored sheet closes at once because its
    pick is gone; passing the name through the host at show time would avoid it (cosmetic gain, more activity state).

19. **The classic layout auto-plays a shared link.** A shared `http`/`https` link from any app is played at once, without a prompt, on
    the stored receivers (or on this phone when none is ready), exactly as before this branch (`ShareIntentHandler`, classic and a
    missing host). The new layout asks first (picker). Owner-accepted and pre-existing (Group 12 cycle 1 W2, Group 15 cycle 1 S9);
    `TabBrowserActivity` is exported without a filter, so any app can send it the intent directly. A file never auto-plays in either
    layout. Process death re-delivers the link text and classic plays it again.
20. **Link metadata reads have no overall deadline.** At link intake `UpnpClient#readLinkMetadata` calls
    `MediaMetadataRetriever#setDataSource(String)`, a network call without a timeout of its own, and the content-type fallback
    (`LinkContentType`, HEAD with a 5 s timeout per hop) can follow up to 3 redirects. A server that drips bytes can pin the single
    link worker thread (and the 3 queued-share slots) for as long as it keeps answering. Fix: a total deadline around the reads, run
    on a thread that can be abandoned (Group 14 cycle 1 Suggestion 2).
21. **`ShareUriPolicy` checks only the scheme and the length** (2048 chars): `http:` followed by junk passes. No sink builds an intent, a
    path or a file from the text, and the later link policy and fetch checks apply; tightening is Backlog 1 item 8.
22. **Link-local zone ids collide.** `ProxyAccessPolicy` strips an IPv6 zone id (`%wlan0`) before comparing, so the same `fe80::` address
    on two interfaces counts as one address. Accepted and documented in the Group 12 plan.
23. **A share that races a layout switch is dropped.** `ShareIntakeGate` discards the payload in an activity that is not the effective
    layout, so a share arriving in the roughly two seconds of a relaunch is lost; the sender shares again.
24. **Some names display as empty.** `DisplayName` removes format characters, but a name made only of ZWJ/ZWNJ, variation selectors,
    U+034F, the Khmer inherent vowels or U+2800 is shown as an empty-looking line instead of the fixed "Shared file" text (cosmetic;
    review S1 of Group 15 cycle 2).
25. **Carried hardening nits.** In `ShareIntentHandler` `consume` and `discard` remove the extras inside one `try` each, so a crafted
    `EXTRA_STREAM` that makes the bundle's lazy unparcel throw skips the removals after it, including the launch token (one `try` per
    removal, nonce first, would complete in every case). `ShareIntakeGate` removes the token from the intent but does not spend its
    entry in the store (it expires after 30 s, at most 8 are held). `intakeBusy` is an instance field, not static, so two handlers
    (a rotation overlap) may each run one intake probe; the pending slot, the probe pool and the provider gate are process-wide and
    bound it anyway. No effect found today (Group 15 cycle 2 S3, S4; Group 14 carry-over 4).

## Research findings about the pre-existing proxy (short summary (softened 2026-10-26))

The Group 12 research looked at the old proxy path before it was replaced. The old detail (request flow, line numbers, storage layout)
is no longer recorded here. What the as-built design took from it:

- The old proxy option (off by default) stored shared links in the app's settings and could expose local paths. It was replaced for
  `/proxy/` by the in-memory `ShareRegistry` with random keys; the old preferences are deleted at start (`Yaacc#removeLegacyProxyLinks`).
- The old range handling and error bodies were not reusable; `ByteRangeResolver` and `ProxyShareResponder` replaced them (constant
  bodies, no key or path echoed, range maths in one tested class).
- The proxy gate of the handler (provider/proxy preferences) is bypassed for registry entries; the HTTP server runs whenever the app
  runs, so a share waits for the server instead of starting it.
- Upstream fetches for link shares still block an HTTP I/O thread (item 11).
- The remote address of a request comes from the connection's endpoint details only (kept in `docs/tech.md`, research (c)).
- The 3-segment `/proxy/<deviceId>/<key>` form is used again for per-renderer server-side seeking (as built, `docs/tech.md`).
