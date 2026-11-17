# Decisions: FCast receivers (#327)

Status 2026-11-17: all entries are **proposals** from the spec author; the owner has not decided yet. Each says what happens if the owner does not object. Open questions for the owner are at the end.

## Proposals

### D1. YAACC is an FCast sender only
**Context:** FCast has senders and receivers; YAACC could also be a receiver (like the OpenHome/UPnP renderer it already is).
**Proposal:** sender only, as the issue says ("cast to FCast receivers"). No receiver, no mirroring, no companion resource protocol.
**Rationale:** smallest useful scope; a receiver is a separate feature with a listening socket and its own security rules.

### D2. Phase 1 speaks protocol v3, v4 is a later phase with its own go
**Context:** v3 is plain JSON over TCP (no dependency). v4 is TLS 1.3 plus FlatBuffers. The Android receiver in the repository announces and speaks v3; the Rust receiver announces v4 and still serves v1 to v3 sessions (`notes.md`).
**Proposal:** build v3 first (Groups 1 to 6), judge v4 after the owner has seen it working with his receivers (Group 7).
**Rationale:** works with every receiver family today without new dependencies; v4 brings tracks, subtitles and encryption but costs a dependency decision and an API level problem (D3).

### D3. v4 on Android API 27 and 28
**Context:** the platform has TLS 1.3 from API 29. minSdk is 27.
**Options:** (a) v4 only on API 29 and later, API 27 and 28 stay on v3; (b) add a TLS 1.3 provider library (Conscrypt, native code, large); (c) never do v4.
**Proposal:** (a), decided when Group 7 starts.

### D4. Playing v3 to a receiver that announces v4
**Context:** the v4 document says a sender that knows a receiver supports v4 should refuse to fall back to an unencrypted version (downgrade protection), but YAACC is in phase 1 plain v3 only.
**Proposal:** allow it, show "unencrypted (protocol v3)" in the sheet, and describe it in the docs. Once v4 exists, a switch in the settings *Require encrypted connection to receivers that support it* (default: off for API below 29, on above).
**Rationale:** YAACC's media URLs are plain HTTP on the LAN anyway; refusing v3 would make phase 1 useless with the Rust receiver.

### D5. Naming: package, classes, UI
**Context:** FUTO's trademark policy forbids "FCast" as or at the start of a product name and as a *published* package name; descriptive use is fine (`notes.md`, "Brand rules"). The Snapcast work used a neutral package (`de.yaacc.multiroom`) and MusicCast uses `de.yaacc.yxc`.
**Proposal:** package `de.yaacc.fcast` (an internal package of an app that is not named FCast; the policy's example is about published package names), class names `FCast...`; "FCast" as plain text in the UI ("FCast receiver", "compatible with FCast"); the disclaimer text from the policy in the About screen and in the README; no FCast logo (the policy would allow it inside the UI, the owner decides later, like the Snapcast logo question).
**Veto path:** a neutral package such as `de.yaacc.castpush` costs a rename and nothing else; say so before Group 1 starts.

### D6. Identity of a receiver
**Proposal:** the id is `fcast:<host>:<port>` (the endpoint), parsed from the right, like the Snapcast server key; a changed IP makes a new receiver.
**Alternative:** the mDNS instance name (stable across DHCP changes, but two receivers can share a name and a hand-added receiver has none).
**Rationale:** simple, testable, predictable; the receiver disappears and reappears under its new address.

### D7. Discovery switch default
**Proposal:** *find FCast receivers* is **on by default** (as the Snapcast switch), searches only while the receivers page or list is shown, and the summary says announcements are not authenticated.

### D8. The queue stays in YAACC; no playlist push
**Proposal:** one `Play` per item; end of item moves to the next item in YAACC. The `application/json` playlist container of v3 is not used in phase 1.
**Rationale:** the same behaviour as for every other receiver (repeat, shuffle, mixed sources, error skipping); receivers "may" support playlists. Cost: a short gap between tracks (the receiver loads the next URL after the end). If the gap matters, playlist push or v4 queue messages are the later fix (Group 7).

### D9. URLs that carry credentials
**Proposal:** an item URL with user information (`user:pass@host`) or an item that the user's UPnP server only serves with a token is **not sent** to an FCast receiver in phase 1; the player skips it with a toast that says why. Ordinary UPnP resource URLs and YAACC's own content-server URLs are sent as they are.
**Open:** the owner may prefer to allow it with a warning.

### D10. Mute is emulated
**Proposal:** the protocol has no mute; YAACC sets the volume to 0 and remembers the previous value; unmute restores it; a volume change from outside forgets it.

### D11. No connection for an idle card
**Proposal:** discovery shows presence; a TCP connection exists only while a player exists, the sheet is open, or 30 s after that. Volume on an idle card connects on demand.

### D12. No sender SDK
**Proposal:** do not use FUTO's Rust sender SDK (native code); implement v3 in Java from the documents (MIT would allow reuse; the build and native-code cost is the reason).

## Questions for the owner (before Group 1)
- **Q1.** Is the scope right (sender only, v3 first), and are the proposals D1 to D12 acceptable, especially D5 (package `de.yaacc.fcast`), D9 (credential URLs) and D4 (v3 to v4-capable receivers)?
- **Q2.** Which receivers do you have for real checks (FCast Receiver on Android TV? the desktop receiver on Linux, Windows or macOS?) and on which networks (the receiver docs warn that mDNS may not work everywhere)?
- **Q3.** What do you want to play: music only, or video and pictures as well? (The design plays all three; images and video are the bigger test effort on real TVs.)
- **Q4.** The settings option: is "what the protocol offers plus device information and troubleshooting" enough for phase 1, knowing that FCast cannot change a receiver's own configuration? Is there a particular receiver setting you had in mind?
- **Q5.** Should a receiver be groupable with others or should there be a "play to several FCast receivers" feature? (FCast has no sync; proposal: no.)
- **Q6.** Should a discovered receiver be hideable from the list (a *Hide* entry in the sheet, stored by id)? Proposal: not in phase 1.
- **Q7.** v4 (encryption, tracks, subtitles): wanted soon, later, or never? It decides whether a FlatBuffers runtime and the API 27/28 question are worth planning now.
- **Q8.** The FCast logo: text name only (proposal) or ask FUTO about showing the logo on the cards like the Snapcast question?
