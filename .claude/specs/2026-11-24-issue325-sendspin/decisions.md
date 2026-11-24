# Decisions: Sendspin support (#325)

Entries marked **CONFIRM** are recommendations the owner has not decided yet; the matching group does not start before they are confirmed. Newest decision wins; a superseded entry says so.

## 2026-11-24 - Owner's answers to the questions of the first spec round (owner)
- **Package and class names: `de.yaacc.sendspin.*` and `Sendspin*` are fine.** The Snapcast package `de.yaacc.multiroom` may be renamed to `snapcast` later, once Sendspin works (a separate cleanup, not part of #325).
- **A Docker rig is needed** (the owner has none): `tools/sendspin-rig/`, task 1.4. The owner has a **Kodi device**; whether Kodi can act as a Sendspin player or server is not known and is checked at task 1.4 (nothing is assumed).
- **Proof of concept first, then the real implementation, is fine.** The owner cannot test for the next hours: **if the tests of the session look good, go ahead** with the next steps. Audio listening (task 4.6, Q3) stays the owner's; everything that a JVM test and the reference player can show is done without him. The session records what was and was not verified.
- The recommended defaults of the CONFIRM entries below are used while the owner is away (library, WebSocket in-house, key storage, pairing scope, discovery default). They stay open for veto and are easy to change before release.

## 2026-11-24 - Crypto inventory result (task 1.2, checked against developer.android.com)
Android reference pages: `KeyAgreement`/`KeyPairGenerator` algorithm `XDH` (X25519) is **API 33+**; `Cipher` `ChaCha20/Poly1305/NoPadding` is **API 28+**; `AES/GCM/NoPadding` is available on API 27 (and earlier). So at minSdk 27 a library is needed for X25519 in every case and for ChaCha20-Poly1305 on API 27. SHA-256 and HMAC-SHA256 are platform. Library: Bouncy Castle `bcprov-jdk18on` **1.87** (published 2026-11-11, so older than 7 days on 2026-11-24; SHA-1 `3cf2bbc1280a743fd3d4d7d556883c13e5d28be5`, 7.3 MB jar, multi-release, BC licence), used only through the lightweight API (`org.bouncycastle.math.ec.rfc7748.X25519`, `org.bouncycastle.crypto.modes.ChaCha20Poly1305`), never as a JCA provider. The project does not minify (`docs/adr/0001`), so the debug and release APK grow by roughly the compressed jar (about 5 to 6 MB; measured when the dependency is added). Tink was not measured; BC is the recommendation.

## 2026-11-24 - Reference implementation found (task 1.4)
`sendspin` 9.1.0 on PyPI (command line player and server, Apache-2.0, repository `Sendspin-Protocol/sendspin`) on top of `aiosendspin` 10.2.2 (Apache-2.0; uses `noiseprotocol`, `cpace`, `cryptography`, `zeroconf`). `sendspin daemon --unpaired-access true --audio-device null` runs a headless player (listens on 8928, path `/sendspin`, advertises `_sendspin._tcp`) in the sandbox without audio hardware (PortAudio plus the ALSA null device); `sendspin serve` is a reference server. The player prints its identity; behavior is compared, no code is copied.

## 2026-11-24 - Proof of concept result so far (Q1 to Q5; the owner's listening is open)
- **Q1 Crypto: yes.** Noise `KKpsk2` in plain Java with Bouncy Castle's lightweight `X25519` and `ChaCha20Poly1305` and the platform's AES-GCM: byte-identical to an independent implementation for both suites, accepted by the reference player. Cost: a 7.3 MB jar, no minification (`docs/adr/0001`); not measured in an APK.
- **Q2 Transport: an in-house WebSocket is enough** (RFC 6455 subset, both sides, 19 tests including every violation); no library was needed.
- **Q3 Server sync: works as far as a machine can tell, not heard.** The reference player's own measure converges to 0 ms within 25 s and stays within +-3 ms for 40 s of streaming, with a seek, a pause and a resume; no underrun. The first 25 s start at -60 to -130 ms, corrected at the player's speed cap, which is what the specification prescribes; a real listening test of the first seconds is the owner's (task 4.6).
- **Q4 Fit: yes with the FCast and Snapcast pattern**; the touched places are listed in `tasks.md`. One design point found: the tap's sample rate must be known when the ExoPlayer is created, so the hub keeps a connection per known player.
- **Q5 Interop: one reference player so far** (`sendspin` 9.1.0). Verified: unpaired with operator approval; a player with unpaired access off is recognized and shown as needing pairing. **Not verified:** the Pairing PSK (the reference daemon shows no token), Music Assistant, ESPHome, the owner's Kodi device.
- **Rig finding:** PortAudio can not open the ALSA `null` device by name; the rig uses a PulseAudio null sink (also real-time clocked). The Docker image was never built.
- **Discovery default (decided in the build, open for the owner's veto):** the main switch *Use Sendspin players* is **off by default** (the app part is untested on a device); the search switch *Find Sendspin players* is **on by default** but acts only while the main switch is on, so nothing is sent by default. Nothing is played to a found player before the operator plays to it.

## 2026-11-24 - Controller: owner's decisions and the open list
- **Connection**: YAACC connects out (client-initiated mode), no listening socket. **UI**: a card in the receiver list (the owner chose it over an own screen and a media notification; the card controls a server's group and is not a place to play to).
- **Added at the owner's request**: the mDNS search of servers (`_sendspin-server._tcp`) and the artwork (over the artwork role, not by fetching `artwork_url`: no HTTP to an address a server names).
- **Open list (owner)**: pairing as a client (K8) is an open task; the **security review of the client side is postponed** until there are more results (K10). Until then the client side, its artwork decoding and its discovery are not reviewed.
- **Default (open for the owner's veto)**: *Allow unpaired servers* is on by default for the controller (the user names or sees the server, the roles give it no audio or files); *Find Sendspin servers* is on by default but only while the controller is on, which is off by default.
- **Deviation from the specification**: group ids are accepted as opaque text (the reference server sends a UUID).

## 2026-11-24 - Use cases written down (`usecases.md`, owner's request); a deviation found
- Linking is a server concept: YAACC can link players only as the server; the phone as a player is a separate feature; the controller role controls its own group and cannot link arbitrary players. See `usecases.md`.
- **Deviation from the specification found while writing it:** the hub declares `'playback'` when an approved or paired player says hello, not when something plays. With another server on the network (Music Assistant) YAACC could take a player by connecting. Recommended fix and its place in the order of work: `usecases.md`, open points 1 and 2. **Fixed the same day** (agreed by the owner, with the order of work in `usecases.md`): the connection idles with the player role and no activity; `'playback'` is declared by `startPlayback()` and dropped by `stopPlayback()`; a play request before the first activation makes that activation declare it. Rig test with two servers on the reference player. Further owner decisions: the phone as a **full player** is wanted (P1 to P4); groups use **one common format** for all members; whether groups survive a restart is explained and still to be answered (recommendation: not in the first version).

## 2026-11-24 - Pairing PSK built; Q5 answered for the reference player (recommended defaults)
- **Pairing scope as recommended:** the Pairing PSK flow and operator-approved unpaired access; code-based pairing stays open (Group 11). The documentation says so and does not claim conformance.
- **Verified against the reference player (`sendspin` 9.1.0):** with unpaired access off and a Pairing PSK in its settings dir, the server paired it from a token, re-handshook to the long-term PSK, played, and the second connection needed no token. The token was built by an independent encoder (Python) and also matches the spec's reference vector. **Not verified:** a real device, Music Assistant, ESPHome, a token shown by a real player (the reference daemon has no command to show it; the PSK was injected for the test).
- **A held token means "pair now":** the server references the Pairing PSK whenever a token is stored for the player, so pasting a token also re-pairs a player that forgot the server. The token is deleted when the pairing succeeds (the player keeps its Pairing PSK, so the operator may paste it again).
- **Keys:** the long-term PSKs and tokens are in app-private preferences like the identity key; Keystore wrapping (3.5) remains open for the owner's review.

## 2026-11-09 - Server and client, MusicCast-like linking (owner)
**Decision**: YAACC implements both roles. Linking follows the MusicCast pattern: the server leads, members follow, "Play on" a member plays on its group.
**Rationale**: owner's request.

## 2026-11-24 - Issue #325 and a proof of concept first (owner's issue, recommended staging)
**Decision**: the issue is titled a PoC and asks whether support is possible. The work starts with stage P (`tasks.md`, Groups 1 to 5: a JVM crypto and transport spike, then one player played to unpaired with approval, then the Pairing PSK) and ends with a go / reduce / stop decision by the owner. Phases 2 to 4 of the earlier plan stay as the roadmap behind that gate.
**Rationale**: encrypted handshake and sync are the two unknowns; the server role gives the value first and needs no listening socket; the audio engine for the player role already exists but its target is tighter.
**Supersedes**: "Staging with gates" of 2026-11-09 (same idea, now with a PoC in front and the issue number).

## 2026-11-24 - Shared parts are answered by existing code (supersedes "Shared parts with the Snapcast spec")
**Decision**: no new receiver seam, no new capture sink, no shared NSD base for the PoC. Sendspin follows the FCast and Snapcast pattern (synthetic `Device`, own `Player`, own row code), reuses the PCM tap of `de.yaacc.multiroom.feed` and writes a third NSD class of the same shape. The places it touches are listed by task 1.3.
**Rationale**: the code exists and is reviewed; a seam is worth its cost only when a further protocol is added (`2026-11-10-open-device-model/notes.md`).

## 2026-11-24 - Operator approval by choosing the player (recommended, part of the PoC)
**Decision**: the server treats "Play on" a Sendspin player as the implied approval the spec allows ("an operator action that clearly means to use the client"), persists the approval per `client_id`, shows a list in which it can be revoked, and discards it on a pairing. A player that has not been approved is never given `'playback'` or roles. The row always says whether a player is paired, approved, or neither.
**Rationale**: spec section *Unpaired Access*; matches how the other receivers are used (you pick one and it plays).

## DECIDED 2026-11-24 - Package and class names (replaces "Neutral names")
**Recommendation**: `de.yaacc.sendspin.{session,secure,hub,speaker,discovery}` and `Sendspin*` class names, like `de.yaacc.fcast`, `de.yaacc.yxc`, `de.yaacc.openhome`.
**Context**: the draft of 2026-11-09 chose neutral names (`de.yaacc.multiroom.*`) out of trademark caution. `multiroom` now holds the Snapcast code, and the trademark rules (spec `TRADEMARKS.md`) allow the protocol name in docs and source as a factual statement; what is not allowed is using it as YAACC's own product name or a logo. UI text says "Sendspin players" descriptively with a not-affiliated notice, as for FCast and Snapcast (the owner's earlier "logo" decision: text name only, no logo).
**Decided by the owner on 2026-11-24: protocol names.**

## CONFIRM - Cryptography library
**Context**: Noise `KKpsk2` needs X25519, ChaCha20-Poly1305 or AES-GCM, SHA-256 and HMAC; the Pairing PSK flow needs HMAC and SHA-256/512; the code-based methods (Phase 4) need CPace (X25519, SHA-512). On API 27 the platform has AES-GCM, SHA-2 and HMAC; X25519 is believed to arrive around API 33 and ChaCha20-Poly1305 at API 28 (task 1.2 verifies this on a device or from the platform sources).
**Recommendation**: use a library only for what the platform lacks, through its **lightweight API, never as a JCA provider**: Bouncy Castle (`org.bouncycastle:bcprov-jdk18on`, exact pin, at least 7 days old) for `X25519` and `ChaCha20Poly1305`; AES-GCM, SHA-256 and HMAC from the platform. The Noise state machine is ours, verified against the published Noise test vectors and against the reference player on the rig. Alternative to measure in 1.2: Tink's subtle classes (pure Java, X25519 and ChaCha20-Poly1305). A small Noise library is rejected for now (age and maintenance unknown).
**Consequence**: a few MB more in the unshrunk APK (the project does not minify, `docs/adr/0001`); task 1.2 measures it.

## CONFIRM - WebSocket implementation
**Recommendation**: a small in-house RFC 6455 subset (client side for the PoC, server side for the player role): text and binary frames, ping/pong, close, masking, fragmentation (Group 6), size caps, timeouts, with its own tests. Alternative: a library (`Java-WebSocket`; one more dependency to review, API 27 behavior to check). httpcore5 is in the project but has no WebSocket.
**Rationale**: we need little of it and control limits and timeouts; the cost is code that needs the security review.

## CONFIRM - Pairing scope
**Recommendation**: the PoC and the first release implement the **Pairing PSK** (token paste on the server side, token shown on the player side) and operator-approved **unpaired access**; the code-based methods (dynamic and static pairing codes with CPace) are Phase 4. The spec says servers MUST implement all three, so the documentation says "implements the protocol except code-based pairing", without claiming conformance.
**Rationale**: CPace has no standard Java implementation and is the riskiest crypto piece; the Pairing PSK is mandatory for every client, so every player can be paired with it.

## CONFIRM - Unpaired access default on the phone (player role, Phase 3)
**Recommendation**: **off**. A server must be paired with the token the phone displays. A setting "Allow unpaired servers (less secure)" with a warning.
**Rationale**: an enabled-by-default listener that accepts anyone on the LAN is a bad default; the spec leaves the default to the implementer.

## CONFIRM - Key storage
**Recommendation**: the identity key pair and the long-term PSKs live in the app's private storage, wrapped by an Android Keystore AES-GCM key (the Keystore has no X25519 on API 27); backup excluded; removed by "forget all pairings".
**Rationale**: no extra dependency; protects offline copies of app data.

## CONFIRM - Discovery default
**Recommendation**: discovery of Sendspin players **on by default**, as the owner chose for Snapcast and FCast (2026-11-14), because nothing is played to a discovered player before the operator chooses it; a switch turns it off; the discovery report shows what was seen.
**Decide**: on, or off (as the phone's player listener, which stays off).

## CONFIRM - Which players the owner has (task 1.4)
**Question**: which Sendspin players and servers can the owner test with (a real player such as an ESPHome device, a Music Assistant installation, a Raspberry Pi or PC running the reference command line player), and does the owner accept a Docker rig like those of Snapcast and FCast for the reference player? The PoC's evidence (Q3 and Q5) depends on at least one real or reference player that accepts unpaired access or can show a pairing token.
