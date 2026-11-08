# Review: receivers and players on the top-level page, summary

Condensed: the verdict of every cycle and the blocking findings (Critical and Warning) in one line each; the long texts, the suggestions and
the notes of what was checked are in the git history of this file (the commits before the cleanup). The style changes after cycle 4
(section sizes, card spacing, no player rows under the receivers) had no review cycle. Gate at the end: build, 3395 tests, 0 failures, lint 0.

## Cycle 1 — receivers on the page — FAIL
- Critical: receivers found after the page started got no live status or group state (the status monitor and the group feed took a snapshot of
  the receivers): `ReceiverLiveUpdates.devicesChanged()`, called after every rebuild of the rows.
- Warning: the hint "No receivers found" never showed (the local device is always listed): the rule is "no other receiver", `noRemoteReceiver`.
- Warning: `ConcatAdapter` ignores the stable ids of the sheet adapter (D-pad focus): accepted and recorded in `decisions.md`.
- Warning: the position handling in a `ConcatAdapter` was untested: `AdapterPositions` with tests (`bindingPosition`, `laidOutRows`).
- Warning: the docs said the rows are live also for late receivers: true after the Critical fix, wording checked.

## Cycle 2 — PASS
No Critical, no Warning.

## Cycle 3 — the cap of 64 receivers and the Coalescer — PASS
No Critical, no Warning.

## Cycle 4 — Players section, bottom stack below the content only — PASS
No Critical, no Warning (the bind rule of the bottom stack is recomputed from the fragment on top on every change, nothing else used the bars
at the top level).
