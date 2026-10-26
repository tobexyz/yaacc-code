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

