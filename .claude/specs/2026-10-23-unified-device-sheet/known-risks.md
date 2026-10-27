# Known risks of the share feature

The technical list behind the neutral "Known limitations" summary in `docs/tech.md` (the docs site publishes `docs/`, not this
folder). None of these is fixed on this branch: they are accepted or left for the owner (`tasks.md`, "Owner's open items"). The public
docs do not repeat the technical detail. Older released behaviour is described only in general terms here (softened 2026-10-26).
Class names are the Phase 3 names; Phase 4 renames some (see `design.md`), Group 22 updates this list.

1. **Host names that resolve to loopback are not caught.** `LinkTargetPolicy` never resolves names (no DNS on the intake path), so
   `localtest.me`, a DNS record pointing at `127.0.0.1` or a rebinding name passes the link policy.
2. **Redirects are not re-checked** where others follow them: the proxy's upstream fetch (`HttpURLConnection`) and
   `MediaMetadataRetriever#setDataSource(String)`. Only the content-type fallback (`LinkContentType`) checks every hop, so a public link
   can redirect to a target the policy would refuse. Fix: no automatic redirects, at most 3 hand-followed hops through the policy.
3. **The link policy runs only when the proxy option is on.** With it off the receiver fetches the link itself; the metadata read at
   intake still runs on the phone.
4. **A local video share hands the proxy URL, key included, to another app.** `MultiContentPlayer#startItem` starts an implicit
   `ACTION_VIEW` with it (only when "this device" was chosen). The handler gets a bearer URL valid until the share expires; it cannot
   reach any other share. Remedy: an in-app video player. Audio and images use explicit intents to the app's own viewers.
5. **Unresolved providers are served one at a time.** All providers whose package cannot be resolved share one gate key
   (`ProviderKeys.UNRESOLVED`; the manifest has no `<queries>`, so Android 11+ package visibility may hide the package).
6. **A provider that never returns pins a thread until the process ends.** A Binder call cannot be interrupted; the gate bounds it to one
   thread and one of three places per provider (at most 3 of 4 pool threads), so three hung providers (or one unresolvable app) make
   further file shares fail with "busy". The same holds for up to 3 stuck copy workers.
7. **The picker can be re-opened by a hostile app after a cancel** (no cool-down). Every pick is user-visible and needs the user's OK,
   there is at most one pending share and a second one is dropped with a toast.
8. **Classic pre-ticks the saved selection for a shared file.** One tap on OK plays the sender's file on the usual receivers (default:
   this device); the new layout ticks nothing. The picker shows only the cleaned name. Owner decision of 2026-10-25; the places to
   change are `AfterStartSelection.CLASSIC_MODE` and the initial-ticks argument.
9. **A picked action in flight blocks the picker** from the confirm until it ends (cold start, a link's network work, a copy of up to 5
   minutes); a lost end callback expires after the copy maximum plus a minute.
10. **A rotation or process death with the picker open drops the share** (the pending action lives in the activity). After process
    death the system re-delivers link text: the new layout opens the picker again (nothing plays without OK), classic plays it again.
11. **Upstream I/O on the HTTP I/O threads.** `HEAD` on an `HTTP_LINK` share and every range or server-seeking request block one of the
    server's 8 I/O threads on an upstream request, and `HEAD` takes no response slot. Whoever holds the key (and, with the restriction
    on, a chosen receiver's address) can occupy them. Pre-existing; gated by key and address check.
12. **Other open items.** Some VERBOSE traces of SOAP/GENA messages (`YaaccUpnpServerProtocolRequestHandler`, `ReceivingAction`) and DEBUG
    album-art URL logs bypass `ShareLogRedactor` (the default level is above them). `ImageDownloader` accepts any scheme with no timeout
    or size cap. A share dropped for its type (`*/*`, no read flag, other types) is dropped silently. Two receivers with identical cleaned
    names show once in the receiver bar. A restricted share fails with 403 for a receiver that fetches from another address than it
    announced or whose description names a host (host names are not resolved): that is what the setting is for.
13. **Legacy media-server answer and server-wide HTTP limits (pre-existing).** The old not-found answer of
    `YaaccUpnpServerContentHttpHandler` (the media-server paths, not `/proxy/`) should answer a constant body, and the HTTP server runs
    with default `Http1Config` limits and a 5-minute socket timeout that also applies before a request line arrives. Fix when the
    handler or server is next configured: constant body, explicit line and header limits. Not part of the share feature.
14. **A hostile app on the phone, and the confused-deputy residual.** With "this device" chosen, loopback and the phone's own
    addresses pass the address step, so another app on the phone is stopped only by the 128-bit key (not published by the app, except
    item 4; reasoned, not observed). Android only checks that yaacc itself can read a forwarded `content:` URI and yaacc holds broad
    media permissions and SAF grants. Below Android 15 nothing verifies the sender holds the access; on 15+ only the launcher path asks
    (`getInitialCaller()`, not device-verified). Accepted by the owner.
15. **A receiver's `<URLBase>` host is allowed.** `ShareAddressResolver` adds the host of the descriptor URL AND of `<URLBase>` to the
    allowed set; a receiver the user picked can thereby name another address (e.g. the phone's own LAN address, admitting local apps).
    It already holds the key. Fix: drop the URL base unless its host equals the descriptor host.
16. **Private LAN ranges are allowed by design** (NAS and media-server links). With the proxy option on, a shared link can make the phone
    fetch a page of a LAN device (router, admin page) and relay it to the chosen receivers, not back to the sender; the pick shows only
    the fixed text "Shared link". Items 1-3 widen this.
17. **Abandoned copy workers keep their space.** A worker stuck in the provider at the deadline (at most 3) can keep what it wrote
    allocated until the process ends, invisible to the cache budget (up to 3 x 512 MiB). The abandon path closes its output stream;
    whether closing a provider stream wakes a blocked read is a device check.
18. **The share name is in the picker's saved state** (fragment argument). It is not a URI, token or key and a restored sheet closes at
    once; passing it through the host at show time would avoid it (cosmetic gain, more activity state).
19. **The classic layout auto-plays a shared link** from any app, without a prompt, on the stored receivers (or this phone), as before
    this branch; `TabBrowserActivity` is exported without a filter, so any app can send it the intent. The new layout asks first. A file
    never auto-plays. Owner-accepted and pre-existing; process death re-delivers the text and classic plays it again.
20. **Link metadata reads have no overall deadline.** `UpnpClient#readLinkMetadata` calls `MediaMetadataRetriever#setDataSource(String)`
    (no timeout of its own) and the content-type fallback can follow 3 redirects; a server that drips bytes can pin the single link worker
    thread and its 3 queued-share slots. Fix: a total deadline on an abandonable thread.
21. **`ShareUriPolicy` checks only the scheme and the length** (2048): `http:` followed by junk passes. No sink builds an intent, path or
    file from the text and the link policy and fetch checks apply; tightening is in the backlog.
22. **Link-local zone ids collide.** `ProxyAccessPolicy` strips an IPv6 zone id before comparing, so the same `fe80::` address on two
    interfaces counts as one. Accepted.
23. **A share that races a layout switch is dropped** (`ShareIntakeGate` discards the payload in a non-effective activity for about two
    seconds); the sender shares again.
24. **Some names display as empty.** `DisplayName` drops format characters, but a name made only of ZWJ/ZWNJ, variation selectors, U+034F,
    the Khmer inherent vowels or U+2800 shows as an empty-looking line instead of the fixed "Shared file" text (cosmetic).
25. **Carried hardening nits.** In `ShareIntentHandler`, `consume` and `discard` remove the extras inside one `try` each, so a crafted
    `EXTRA_STREAM` whose lazy unparcel throws skips the later removals, including the launch token (one `try` per removal, nonce first,
    would complete in every case). `ShareIntakeGate` removes the token from the intent but does not spend its store entry (expires after
    30 s, at most 8 held). `intakeBusy` is an instance field, so two handlers (a rotation overlap) may each run one intake probe; the
    pending slot, probe pool and provider gate are process-wide and bound it. No effect found today.

## What the design took from the research on the earlier proxy
- Shared links used to be stored in the settings (the option is off by default). `/proxy/` is now served from the in-memory
  `ShareRegistry`; the old preferences are deleted at start (`Yaacc#removeLegacyProxyLinks`).
- The old range handling and error bodies were not reusable: `ByteRangeResolver` and `ProxyShareResponder` replaced them (constant
  bodies, no key or path echoed, range maths in one tested class).
- The handler's preference gate is bypassed for registry entries; the HTTP server runs whenever the app runs, so a share waits for it.
- The remote address comes from the connection's endpoint details only (`docs/tech.md`, "Research notes (Group 12)", (c)).
- The 3-segment `/proxy/<deviceId>/<key>` form is used again for per-renderer server-side seeking.
