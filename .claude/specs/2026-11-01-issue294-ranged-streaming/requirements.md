# Issue #294: ranged responses of files > 50 MB never end

## Problem Statement
Reporter (VLC on Android TV, curl): a ranged request for a local file larger than 50 MB (or a range larger than 50 MB) is answered with a
body that is shorter than `Content-Length` by the part that is still in the producer's 8 KB buffer (always a multiple of 8192 received), and the connection
stays open until the client times out. MP4 files with the index at the end are hit first (VLC reads the end of the file first).

Cause (`YaaccUpnpServerContentHttpHandler.ContentHolder#createStreamingEntityProducer`, an `AbstractBinAsyncEntityProducer(8192, ...)`):
`availableData()` returns 0 once `bytesRead >= rangeLength`, so `produceData()` is not called again and `channel.endStream()` is never reached; the tail in the buffer is not flushed.
Proposed fix of the reporter: end the stream in the same `produceData()` call as the last write (`if (read <= 0 || bytesRead >= rangeLength) { close; endStream }`).

## Goals
- Every ranged response of the streaming producer is complete (exactly `Content-Length` bytes) and the stream ends at once.
- The same check for the other producers of that file that read from a stream: SAF with ranges (`getEntityProducer` of the SAF holder, uses `availableData() = input != null ? 1 : 0`),
  SAF without range (`input.available()`), external URL (`availableData() = 8192` while the input is open). Fix only what a test shows to be broken.
- Regression tests that fail without the fix.

## Non-Goals
- No redesign of the content handler; no change of the 50 MB threshold; no multi-range (multipart/byteranges) support.

## Constraints
- Range edge cases of the streaming producer are checked and fixed only where clearly wrong and low risk: `bytes=0-0` (end 0 is treated as "no end" now), end beyond the file, suffix range,
  start beyond the end of the file. Existing behavior for normal ranges stays.
- Producers are anonymous classes with a `Context`; move the file producer into a small package-private class that a JVM unit test can drive (see `ProducerTestSupport`, `GuardedEntityProducerTest`, `LengthOnlyEntityProducerTest` for how producers are tested here).
