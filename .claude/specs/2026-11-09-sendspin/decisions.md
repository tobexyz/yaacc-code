# Decisions: Sendspin support

Entries marked **CONFIRM** are recommendations the owner has not decided yet; the matching group does not start before they are confirmed.

## 2026-11-09 - Server and client, MusicCast-like linking (owner)
**Decision**: YAACC implements both roles. Linking follows the MusicCast pattern: the server leads, members follow, "Play on" a member plays on its group.
**Rationale**: owner's request.

## 2026-11-09 - Staging with gates (recommended)
**Decision**: Phase 1 foundations (pure Java, no UI), Phase 2 server (YAACC plays to Sendspin players and links them), Phase 3 client (phone as player), Phase 4 optional roles and extras. Each phase is releasable; the owner decides at each gate. The risky spikes (crypto feasibility, PCM capture, player sync) come first inside their phase, with kill criteria.
**Rationale**: the protocol is large (Noise, pairing, tight sync), young, and niche; the server gives the value the owner wants first; the client carries the audio-sync risk.

## CONFIRM - Cryptography library
**Context**: Noise `KKpsk2` needs X25519, ChaCha20-Poly1305 or AES-GCM, SHA-256; pairing needs HMAC-SHA-512 and, for the code-based methods, CPace (X25519, SHA-512). The platform JCA on API 27 is believed to lack X25519 (added later, around API 33) and ChaCha20-Poly1305 (API 28); Group 1 task 1.1 confirms the exact levels against the Android documentation.
**Recommendation**: Bouncy Castle lightweight API (`org.bouncycastle:bcprov-jdk18on`, exact pin, at least 7 days old), used only through `org.bouncycastle.crypto.*` / `math.ec.rfc7748.*`, never as a JCA provider; Noise state machine written by us and tested against the published Noise test vectors. Alternative: a small Noise library (license and maintenance to be checked in Group 1) - rejected for now because of age and a Java 8 baseline unknown to us.
**Consequence**: roughly 4 to 6 MB more in the (unshrunk debug) APK.

## CONFIRM - WebSocket implementation
**Context**: The protocol needs a WebSocket client (server role connects to players) and a WebSocket server (player role; client-initiated connections for the server role). YAACC's HTTP stack (httpcore5) has no WebSocket.
**Recommendation**: a small in-house RFC 6455 subset in `de.yaacc.multiroom.session` (text/binary, ping/pong, close, fragmentation, masking, size caps), with its own tests (and optionally a run of an external conformance suite on the rig). Alternative: the `Java-WebSocket` library (one more dependency to review, API 27 behavior to check).
**Rationale**: we need little of it, we control limits and timeouts, no dependency churn; the cost is code that needs the security review.

## CONFIRM - Pairing scope
**Recommendation**: first release implements the **Pairing PSK** method (token paste on the server side, token shown on the player side) and operator-approved **unpaired access**; the code-based methods (dynamic/static pairing code with CPace) come in Phase 4. The server role then is not fully conformant (the spec says servers MUST implement all three methods); the README states "implements the protocol except code-based pairing".
**Rationale**: CPace has no standard Java implementation; it is the riskiest crypto piece and not needed for the common players (Pairing PSK is mandatory for every client).

## CONFIRM - Unpaired access default on the phone (player role)
**Recommendation**: **off**. A server must be paired with the token the phone displays. Setting "Allow unpaired servers (less secure)" for convenience, with a warning.
**Rationale**: an enabled-by-default listener that accepts anyone on the LAN is a bad default; the spec leaves the default to the implementer.

## CONFIRM - Key storage
**Recommendation**: identity key pair and long-term PSKs live in the app's private storage, wrapped by an Android Keystore AES-GCM key (the Keystore has no X25519 on API 27); backup excluded (`allowBackup` rules), removed by "forget all pairings".
**Rationale**: no extra dependency; Keystore protects against offline copies of app data.

## CONFIRM - Shared parts with the Snapcast spec
**Decision proposal**: three parts are common and built once, in whichever spec starts first: (1) the synthetic receiver device (a `Device` not in Cling's registry, precedent `getLocalDummyDevice()`, or a seam); (2) the ExoPlayer-based PCM capture sink; (3) the `NsdManager` discovery helper. The inventory task is in both specs (Snapcast 1.4, Sendspin 1.4): do it once.

## CONFIRM - Neutral names
**Decision**: packages `de.yaacc.multiroom.session` (connection, framing, messages), `.secure` (Noise, pairing, key storage), `.hub` (server role), `.speaker` (player role); no brand name in code identifiers; UI text uses "Sendspin" descriptively.
**Rationale**: trademark terms in `TRADEMARKS.md`.
