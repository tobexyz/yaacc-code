# Browse card-grid view (content tab)

## Problem Statement

The content/browse screen (`ContentListFragment` + `BrowseContentItemAdapter`,
`yaacc/src/main/java/de/yaacc/browser/`) currently renders only one
layout: a single-column list of full-width rows
(`browse_content_item.xml`). The user wants a second, switchable
presentation — a grid of cards — while keeping everything else about the
browse screen (sorting, the existing per-entry controls, data loading)
unchanged.

This is deliberately scoped small: a presentation change to one screen,
not a UI redesign. It may be a first step toward a broader redesign later,
but this spec covers only the content/browse tab's list/card toggle.

## Goals

- A second layout for the content list: a grid of cards, 2 per row on a
  normal phone in portrait.
- A way to switch between the existing list layout and the new card
  layout.
- The card layout shows exactly the same data and exactly the same
  per-entry controls as the list layout today — no new controls, no
  removed controls.
- The icon/thumbnail currently shown per list row (`browseContentItemIcon`)
  is reused as the card's primary visual, not replaced with something new.
- Sorting (Name/Date toggle, direction, persistence — issue #252) behaves
  identically in both layouts, since both read the same
  `BrowseContentItemAdapter` backing list.

## Non-Goals

- No redesign of any other screen/tab.
- No new per-entry actions beyond what already exists today (play, play
  all, download, add to playlist — whichever subset is already visible
  for a given content type).
- No "recent files" / cross-container aggregation view (discussed
  separately, not part of this spec).
- No change to how items are fetched, paginated, or sorted server-side or
  client-side — this spec only changes how already-loaded items are
  *drawn*.

## Current State (confirmed by source reading)

- `browse_content_item.xml`: a horizontal row — 48dp icon on the start
  edge, title (`TextView`, 2 lines max) next to it, then up to 4
  `ImageButton`s (32dp×48dp each) anchored to the end edge:
  `browseContentItemPlaylistAdd`, `browseContentItemDownload`,
  `browseContentItemPlay`, `browseContentItemPlayAll`.
- `BrowseContentItemAdapter.onBindViewHolder` sets each button's
  visibility per content type (`Container`, `AudioItem`, `ImageItem`,
  `VideoItem`, `PlaylistItem`, `TextItem`, unknown) — e.g. a `Container`
  shows only `play`+`playAll`; an `AudioItem` shows all four; a
  `PlaylistItem`/`TextItem` shows none. This per-type visibility logic is
  unchanged by this spec — the card layout just needs to host the same
  four buttons in the same visibility pattern.
- The icon itself is either a static type icon (folder/audio/image/
  video/playlist/text/unknown drawable) or a downloaded thumbnail (album
  art / cover) when the `showThumbnails` preference is on, loaded via
  `IconDownloadTask`.
- **Thumbnail resolution is hardcoded to 48×48px** — the
  `IconDownloadTask(ImageView, BrowseContentItemAdapter)` constructor used
  here (`IconDownloadTask.java:47-54`) hardcodes `targetWidth`/
  `targetHeight` to 48 regardless of the `ImageView`'s actual layout size.
  If the card's icon area is larger than 48dp, thumbnails will be
  upscaled from a 48×48 source and look soft/blurry unless this is
  addressed.
- `RecyclerView.Adapter.getItemViewType` currently returns `position`
  (i.e. every position is its own "view type"), which defeats normal
  ViewHolder recycling. Pre-existing behavior, not introduced by this
  spec, but worth not making worse.
- Row click handling (`ContentListClickListener`) and the four buttons'
  click handlers are wired in the adapter/click listener, independent of
  layout — reusable as-is for cards.
- `ViewHolder`'s key listener (`BrowseContentItemAdapter.ViewHolder`,
  `onCreateViewHolder`) implements DPAD left/right/up/down navigation
  across a *row's* buttons and between rows, for remote-control/keyboard
  use. A 2-column grid changes what "left"/"right" at a row/column
  boundary should do — addressed in design.md.
- The sort toggle buttons already live in the content list's header
  (`fragment_content_list.xml`, `contentListSortToggle`) as plain
  `ImageButton`s with `app:tint`, matching the rest of the app's icon
  button convention — the precedent this spec's list/card toggle button
  should follow for visual consistency.
- No existing `GridLayoutManager`/card-grid pattern exists anywhere else
  in the app to reuse.

## Success Metrics

- User can switch the content/browse tab between list and card layout and
  back, with the choice taking effect immediately on the currently
  browsed folder.
- Every control available on a list row for a given content type is
  present and functional on that same entry's card.
- No regression to existing list-layout behavior, sorting, pagination, or
  remote-control/keyboard navigation.

## Constraints

- Android, Java, existing `RecyclerView`/`CardView` stack — no new
  third-party grid/card library.
- Must not change `BrowseContentItemAdapter`'s public contract used by
  other classes (`BrowseItemLoadTask`, `ContentListClickListener`,
  `ContentItemPlayTask`, `ContentListFragment`) beyond what's needed to
  add the second layout.
