# Decisions: FCast receivers (#327)

Status 2026-11-20: the owner answered D1 to D12 (below, each entry says **Decided** with the answer). Open questions that remain are at the end.

## Decisions (owner, 2026-11-20)

### D1. YAACC is an FCast sender only
**Decided (owner, 2026-11-20): agreed.** No receiver, no mirroring, no companion resource protocol. A receiver is a separate feature with a listening socket and its own security rules.

### D2. Phase 1 speaks protocol v3, v4 follows
**Decided (owner, 2026-11-20): v3 first.** v3 is plain JSON over TCP (no dependency). v4 (TLS 1.3 plus FlatBuffers) is phase 2 (Group 7), see D3 and D4.

### D3. v4 on Android versions that support it; minimum level unchanged
**Decided (owner, 2026-11-20): v4 on devices with a suitable Android version, the minimum level should not change if possible.**
The platform has TLS 1.3 from API 29 (Android 10). So v4 is used on API 29 and later; API 27 and 28 stay on v3. **minSdk stays 27** and no TLS provider library (Conscrypt, native code) is added. Whether API 29 really offers `TLSv1.3` through the default `SSLContext` for a plain socket upgrade is verified in task 7.1.

### D4. Choosing between v4 and v3
**Decided (owner, 2026-11-20): v4 is preferred, v3 is the fallback, decided by the Android version.**
Rule: use v4 when the Android version supports it and the receiver supports it (it announces `v` 4 or answers `Version` 4); otherwise v3. The v4 document's advice to refuse any fallback for a receiver known to support v4 is not followed (it would shut out API 27 and 28 devices and v3-only receivers). Two guard rails stay: (1) a **pin mismatch** (the certificate does not match `fp`) is an error shown to the user, never a silent fall back to v3; (2) the settings sheet shows which version is in use and whether the connection is encrypted, and says so when a v4-capable receiver is used with v3.

### D5. Naming: package, classes, UI
**Decided (owner, 2026-11-20): use `de.yaacc.fcast` if possible.** Package `de.yaacc.fcast` (an internal package of an app that is not named FCast; FUTO's policy forbids "FCast" as or at the start of a product name and as a *published* package name; descriptive use is fine, `notes.md`), class names `FCast...`, "FCast" as plain text in the UI ("FCast receiver", "compatible with FCast"), the policy's disclaimer in the About screen and the README, no FCast logo (Q8 open). If FUTO objects, a rename is mechanical.

### D6. Identity of a receiver
**Decided (owner, 2026-11-20): agreed.** The id is `fcast:<host>:<port>` (the endpoint), parsed from the right, like the Snapcast server key; a changed IP makes a new receiver.

### D7. Discovery switch default
**Decided (owner, 2026-11-20): agreed.** *find FCast receivers* is on by default, searches only while the receivers page or list is shown, and the summary says announcements are not authenticated.

### D8. The queue stays in YAACC; no playlist push
**Decided (owner, 2026-11-20): agreed.** One `Play` per item; the end of an item moves to the next item in YAACC. The v3 `application/json` playlist container is not used in phase 1; a short gap between tracks is the known cost (playlist push or the v4 queue messages are the later fix).

### D9. URLs are sent as they are
**Decided (owner, 2026-11-20): always send the links as they are.** The owner asked why not. There is no good reason to treat FCast differently: the URL of an item goes to a receiver the user chose, as it already does for UPnP renderers, and a URL that needs user information or a token would not play at all if it were stripped. The earlier proposal (refuse such URLs) is dropped. What stays: the docs say that the receiver sees the full URL, and the one refusal is a request that does not fit the packet limit (32 KB in v3).

### D10. Mute is emulated
**Decided (owner, 2026-11-20): agreed.** The protocol has no mute; YAACC sets the volume to 0 and remembers the previous value; unmute restores it; a volume change from outside forgets it.

### D11. No connection for an idle card
**Decided (owner, 2026-11-20): agreed.** Discovery shows presence; a TCP connection exists only while a player exists, the sheet is open, or 30 s after that. Volume on an idle card connects on demand.

### D12. No sender SDK
**Decided (owner, 2026-11-20): agreed.** FUTO's Rust sender SDK (native code) is not used; v3 and later v4 are written in Java from the documents.

## Questions still open
- **Q2.** Which receivers do you have for real checks (FCast Receiver on Android TV? the desktop receiver on Linux, Windows or macOS?) and on which networks (mDNS may not work everywhere)? Needed for the spike (task 1.4) and the gate (6.2).
- **Q3.** What do you want to play: music only, or video and pictures as well? (The design plays all three; images and video are the bigger test effort on real TVs.)
- **Q4.** Is "what the protocol offers plus device information and troubleshooting" enough for the settings option of phase 1, knowing that FCast cannot change a receiver's own configuration? Is there a particular receiver setting you had in mind?
- **Q5.** Grouping or "play to several FCast receivers": proposal no (FCast has no sync).
- **Q6.** A *Hide* entry for discovered receivers: proposal not in phase 1.
- **Q8.** The FCast logo: text name only (proposal) or ask FUTO like the Snapcast logo question?
- Answered by D2 to D4: Q1 (scope and proposals), Q7 (v4 wanted, after v3, on suitable Android versions).
