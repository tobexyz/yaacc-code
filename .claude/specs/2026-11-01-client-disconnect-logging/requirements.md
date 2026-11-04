# A client that hangs up is not an error

## Problem Statement
Testing with Home Assistant produced `E ... YaaccUpnpServerService$4: connection error: java.io.IOException: Broken pipe`
with a 35-line stack trace. The client closed the connection while the server wrote the answer
(normal for a control point that times out, navigates away or closes the app). The exception callback of the HTTP server logs every
exception that is not a `SocketTimeoutException` / `ConnectionClosedException` at error level with the stack.

## Goals
- A client that hangs up (`Broken pipe`, `Connection reset by peer`, closed or timed out connection) is logged on ONE line at debug level, without a stack trace.
- Every other connection error stays an error with the stack.

## Non-Goals
- No change to how connections or answers are handled; no attempt to find out which request the client dropped (the callback does not know it).

## Success Metrics
- Unit test of the classification; the log of such a hang up is one D line.

## Open Questions
None. (If the same hang up shows up for long Browse requests, the cause is the client's timeout; the SAF listing work in #299 already makes repeated Browse cheap.)
