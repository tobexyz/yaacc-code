# Ticking or unticking a shared folder tells the control points

## Problem Statement
In the server settings the owner can tick or untick the shared SAF folders (and sub folders). Control points that cache the
ContentDirectory only refresh when `SystemUpdateID` changes. Adding or removing a folder does that; ticking/unticking does not
(`MediaPathFilter.saveSelectedSafPathes` sends no broadcast). Documented as a known gap in #299.

## Goals
- Ticking or unticking a shared SAF folder changes `SystemUpdateID` (the control points are told).
- Adding a SAF folder (which writes both preference sets) still produces ONE change, not two.

## Non-Goals
- No change to which folders are served; no ContainerUpdateIDs.

## Decisions (owner, 2026-11-01)
- 3: yes, fix it.

## Design
`YaaccUpnpServerService.onSharedPreferenceChanged` already runs for both SAF preference keys (cache cleared, change check reset).
It also asks for a `SystemUpdateID` change now. The existing broadcast receiver (add/remove of a folder) asks too. Both go through a
`DebouncedAction` (1 s): any number of requests within the delay run the action ONCE, on a worker thread. No other behavior changes.
