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

## Follow-up (owner, 2026-11-01): 416 and ranges of files up to 50 MB
Left over from the fix of #294 (see docs/tech.md "Issue #294"):
1. The status is 206 for EVERY range request, also when nothing can be served (start at or beyond the end of the file). RFC 9110: answer 416 Range Not Satisfiable
   with `Content-Range: bytes */<size>` and no body (the body of a 416 is empty or a short text).
2. `ContentHolder#readRangeFormFile` (in-memory path for files up to 50 MB) differs from the streaming path: the length is `end - start` (one byte less than the `Content-Range` announces),
   `bytes=0-0` returns the whole file, a start beyond the end throws `NegativeArraySizeException`, one `RandomAccessFile.read` may return less than the buffer.
   `bytes=0-0` behaves differently above and below 50 MB.
Preferred approach (verify, justify in the report): use the same `RangeWindow` + `RangedFileEntityProducer` for EVERY ranged request of a local file and drop `readRangeFormFile` and the 50 MB branch
(less code, one behavior, less memory); only keep the in-memory path if a test or a compatibility reason shows the streaming producer is not a drop-in replacement
(same body bytes, same Content-Length, same headers). The status and `Content-Range` decision belongs in one small testable class (e.g. `RangeResponsePlan`: status 200/206/416, Content-Range text, the window), used by `handle()` for ALL holder types (local file, SAF, album art, thumbnail, external URL with unknown length: no Content-Range, as today).
Edge cases to decide with the RFC (note the decision in tech.md): several ranges in one header (today the first one is served as 206: keep, or ignore the header and send 200? RFC allows ignoring Range; prefer keeping today's behavior), `Range` with a unit other than bytes (ignore -> 200), unparsable header (ignore -> 200), `If-Range` (not supported, ignore), HEAD with Range.
Non-goals: multipart/byteranges; live streams (no ranges).
