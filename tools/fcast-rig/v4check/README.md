# Checking YAACC's FCast v4 buffers against the reference

YAACC reads and writes the FlatBuffers messages of FCast protocol v4 with a small hand-written layer on top of the FlatBuffers runtime
(`yaacc/src/main/java/de/yaacc/fcast/protocol/v4/`, decision D23). These two programs check that layer against the reference
implementation (the Rust crate `fcast-protocol` of <https://github.com/futo-org/fcast>, MIT licence). Nothing of that crate is stored here.
Set the `path` in `Cargo.toml` to your clone, then:

* `cargo run -q > golden.txt` writes the messages as the reference builds them (one `name=hex` line each). The same bytes are in
  `yaacc/src/test/java/de/yaacc/fcast/protocol/V4Golden.java`; `V4ReaderTest` reads them with YAACC's reader.
* `./gradlew :yaacc:testDebugUnitTest --tests 'de.yaacc.fcast.protocol.v4.V4WriterTest'` writes `yaacc/build/v4-java-buffers.txt` with the
  buffers YAACC builds; `cargo run -q --bin verify -- yaacc/build/v4-java-buffers.txt` reads each with the reference's verifier and prints
  the fields it found (every line must say `ok` with the expected values).

Last run: 2026-11-22, reference commit `ea3982b3`, all 17 buffers `ok`.
