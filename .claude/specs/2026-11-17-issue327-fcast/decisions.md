# Decisions: FCast receivers (#327)

Status 2026-11-20: the owner answered D1 to D12 and the questions Q2 to Q8 (below, each entry says **Decided** with the answer); D13 and D14 record the last answers, D15 the discovery reuse decision. No question is open.

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
**Decided (owner, 2026-11-20): use `de.yaacc.fcast` if possible.** Package `de.yaacc.fcast` (an internal package of an app that is not named FCast; FUTO's policy forbids "FCast" as or at the start of a product name and as a *published* package name; descriptive use is fine, `notes.md`), class names `FCast...`, "FCast" as plain text in the UI ("FCast receiver", "compatible with FCast"), the policy's disclaimer in the About screen and the README, no FCast logo (Q8, answered in D14: text only). If FUTO objects, a rename is mechanical.

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

### D13. Test receiver rig for a notebook
**Decided (owner, 2026-11-20):** the owner has no receiver hardware and tests with a software receiver on a notebook; a Docker setup or an equivalent is wanted. **Outcome:** official desktop receiver on the notebook for real media, plus `tools/fcast-rig/` (task 1.5): a small test receiver with Docker and native instructions (design, "Test rig and real receivers"). A Docker image of the real receiver is task 1.6, optional.

### D14. Trademarks
**Decided (owner, 2026-11-20): text only, respect all trademarks.** Brand names appear as plain text (FCast, Snapcast, MusicCast, Yamaha, Chromecast and the like), used descriptively with the owners' disclaimers; no third-party logos or icons (Q8 answered: no FCast logo); no name that suggests an official product.

### D15. Reuse of the Snapcast discovery code (task 1.2, 2026-11-20)
**Decided (spec author, by reading the classes; the owner may veto):** **no extraction and no change to any Snapcast class** (they are finished and owner-tested). The neutral ones are used as they are from `de.yaacc.fcast.discovery`; the Snapcast-specific ones get a small FCast counterpart.
- **Used as they are (logic is service-type neutral, only the class names say Snapcast):** `SnapcastResolveQueue<T>` (serial `NsdManager` resolves, bounded, timeout), `SnapcastResolveRetry` (which failures are retried), `SnapcastBrowseHandle` (a stop before the start is confirmed), `SnapcastNsdErrors` (names of the NSD failure codes for the report), `SnapcastAddressFilter` (local-network address ranges for discovered hosts), `SnapcastPreferencesStorage(context, key)` with the `SnapcastManualServers.Storage` interface (a plain string list under a key of our own), `SnapcastEndpoint.parse(text, defaultPort)` (host or IP with optional port, IPv6, validation; called with 46899), `SnapcastResolver` (name lookup for a typed host name, takes a `SnapcastEndpoint`), `MonotonicClock`, and (found in Group 3) `SnapcastDiscoveryGate` (the page counter and the switch, `de.yaacc.multiroom`).
- **New FCast classes (the Snapcast ones are tied to Snapcast types):** `FCastNsd` (one browse `_fcast._tcp`, the lifecycle of `SnapcastNsd` without the type list, role logic and `SnapcastDirectory` coupling), `FCastDirectory` (small: id, name, endpoint, TXT hints, last seen, caps; no server-group rules), `FCastManualReceivers` (the list on top of the storage, default port 46899, cap 16), `FCastDiscoveryLog` and `FCastDiscoveryReport` (small, one type), `FCastServiceType` (the type string and its canonical form: `NsdManager` reports types with a trailing dot, in any case).
- **Why not extract a neutral base:** it would touch the Snapcast classes and their tests for no behaviour gain; the neutral classes are already public and final. **Cleanup option (owner's call, later):** rename the neutral ones to neutral names in one mechanical commit once the Snapcast review of Group 12 is done.
- **Risk:** `de.yaacc.fcast` depends on `de.yaacc.multiroom.discovery` and on `MonotonicClock`, which lives in `de.yaacc.multiroom.control`; if the Snapcast code is ever removed or reshaped, those classes must move first. Recorded here and in `tasks.md` 3.1 and 3.3.

### D16. More ways to add a receiver: connection URL, QR scan, device name (owner's request, 2026-11-20)
**Decided (owner): add the QR scan and the input of a device name as alternative connection types.** Tasks 5.5 (connection URL, pasted), 5.6 (QR scan) and 5.7 (device name) in Group 5. Reading the sources showed that both FUTO receivers already show a QR code with an `fcast://r/<base64url JSON>` URL (name, addresses, services, optional `txt` with `v` and `fp`), so the scan needs no protocol work, only the URL parser and a scanner. **Owner's idea (2026-11-20, adopted): no scanner in the app.** The device's own camera or scanner app reads the QR code and opens the `fcast://r/...` link; YAACC registers the `fcast` scheme (task 5.6) and shows a confirmation dialog before anything is added; copy and paste (5.5) is the fallback; an in-app scanner stays an optional follow-up. No camera permission, no new dependency. The `fp` in the URL is the trusted channel for the v4 pin (7.2). The device name is resolved as: IP, then a receiver already discovered, then the system name lookup, then a short `_fcast._tcp` search; the failure text says what was tried.

### Answers to the earlier questions (owner, 2026-11-20)
- **Q2:** no receiver hardware; a software receiver on a notebook (D13).
- **Q3:** music, video and pictures all three are in scope (the design already plays all three).
- **Q4:** yes, "what the protocol offers plus device information and troubleshooting" is enough for the settings option of phase 1.
- **Q5:** agreed, no grouping and no play to several FCast receivers.
- **Q6:** agreed, no *Hide* entry for discovered receivers in phase 1.
- **Q8:** text only, respect all trademarks (D14).

## Questions still open
None. New questions go here.
