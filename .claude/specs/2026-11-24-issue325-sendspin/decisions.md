# Decisions: Sendspin support (#325)

Entries marked **CONFIRM** are recommendations the owner has not decided yet; the matching group does not start before they are confirmed. Newest decision wins; a superseded entry says so.

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

## CONFIRM - Package and class names (replaces "Neutral names")
**Recommendation**: `de.yaacc.sendspin.{session,secure,hub,speaker,discovery}` and `Sendspin*` class names, like `de.yaacc.fcast`, `de.yaacc.yxc`, `de.yaacc.openhome`.
**Context**: the draft of 2026-11-09 chose neutral names (`de.yaacc.multiroom.*`) out of trademark caution. `multiroom` now holds the Snapcast code, and the trademark rules (spec `TRADEMARKS.md`) allow the protocol name in docs and source as a factual statement; what is not allowed is using it as YAACC's own product name or a logo. UI text says "Sendspin players" descriptively with a not-affiliated notice, as for FCast and Snapcast (the owner's earlier "logo" decision: text name only, no logo).
**Decide**: neutral names or protocol names.

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
