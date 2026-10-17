# Decisions: Browse card-grid view

## 2026-10-17 — IconDownloadTask leak found during Group 1 research

**Context**: `tasks.md`'s original Group 2 "ViewMode support" task called
for `onBindViewHolder` to request a per-mode thumbnail size via
`IconDownloadTask`'s existing `(ImageView, int, int)` constructor instead
of the fixed-48px `(ImageView, BrowseContentItemAdapter)` one. Group 1's
doc-research task found that the `(ImageView, int, int)` constructor
unconditionally sets `browseContentItemAdapter = null`
(`IconDownloadTask.java:65-72`), so `onPostExecute`'s
`browseContentItemAdapter.removeTask(this)` cleanup never fires for tasks
created that way — `asyncTasks` would grow by one un-removed entry per
bound row for the adapter's lifetime. Not a pre-existing bug; this spec
would have introduced it.

**Decision**: add a new `IconDownloadTask` constructor overload —
`(ImageView, BrowseContentItemAdapter, int, int)` — that sets both the
adapter reference (preserving `removeTask` cleanup) and the custom
width/height, and have `onBindViewHolder` call that one instead. Updated
`tasks.md`'s Group 2 adapter task accordingly before delegating Group 2.

**Rationale**: smallest change that preserves existing cleanup behavior
exactly while still allowing a non-48px size; no existing constructor's
behavior changes, so no risk to the unrelated `BrowseReceiverDeviceAdapter`
call site already using the 3-arg constructor for 512×512 album art.
