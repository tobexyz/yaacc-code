# SystemUpdateID after a server start (issues #298, #299)

## Problem Statement
`YaaccContentDirectory` starts its `SystemUpdateID` at 0 on every server start. Content that changed while the server was stopped
(a download finished, files were copied) is not announced: a control point that last saw 0 believes its cached view is current.
#299 announces changes while the server runs; #298 asks for a refresh when the server starts.

## Decision (owner, 2026-11-01)
Implement on branch feat/issue299, reference #298 and #299 in the commit. No setting (it costs nothing). The owner comments on and closes both issues.

## Design
The start value is the current time in seconds (`SystemUpdateIds.startValue`, masked to 32 bit, at least 1), so every start has a higher number than the
previous one (the number is only a change hint; later changes increment it). The constructor takes the value; tests pass 0 to keep their expectations.

## Non-Goals
Persisting the number; ContainerUpdateIDs.
