# Design: SAF change detection

- `SafChangeDetector` (pure): order independent 64-bit fingerprint per folder; first sight is a baseline; unlistable folders change nothing;
  one bump for many changed folders; minimum 15 s between bumps (a too early change is postponed, not dropped); at most 16 folders.
- `SafWatchSet` (pure): selected roots always, plus folders a Browse listed in the last 15 min.
- `SafChangeMonitor` (pure): `runPass(roots)` re-lists watched folders (`SafListingCache.refresh`, puts the fresh listing in the cache),
  `onBrowse(...)` compares a listing that was just reloaded by a Browse (no extra query). A Browse thread never bumps.
- `SafChangeRunner` (Android glue): one daemon thread, first pass 10 s after start, then 30 s after the end of the previous pass
  (3x the pass duration when a pass took longer than 10 s); exists only while server, content provider and "serve SAF folders" are on
  and a SAF folder is selected. Bump = `YaaccContentDirectory.changeSystemUpdateID()` on the runner thread.
- `SafFolderBrowser.createItem` uses the size of the fresh listing instead of the cached first-sight size.
- Not used: ContentObserver (providers are not required to notify, and not for the tree uri), FileObserver (no paths in a SAF tree).
