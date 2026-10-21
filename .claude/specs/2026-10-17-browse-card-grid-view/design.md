# Browse card-grid view — design

## Context

See `requirements.md` for the full current-state findings. This design
adds a second `RecyclerView` item layout ("card") alongside the existing
one ("list"), switchable at runtime, with no change to data loading,
pagination, or sort logic.

## Decision

Keep one `BrowseContentItemAdapter`, one backing list, one set of click
handlers — only the *view layer* varies by mode. Concretely:

- Add `BrowseContentItemAdapter.ViewMode { LIST, CARD }`, mirroring how
  `SortMode` already works (a field + persisted preference + a setter
  that triggers `notifyDataSetChanged()` — no reload, since the
  underlying data doesn't change, only how it's drawn).
- `getItemViewType(position)` changes from today's `return position;` to
  `return viewMode.ordinal();` (two real view types instead of one per
  position). This is a net improvement — it enables normal ViewHolder
  recycling instead of the current one-off-per-position behavior,
  without being asked to fix that pre-existing issue outright.
- `onCreateViewHolder` inflates `browse_content_item.xml` (list, as
  today) or a new `browse_content_item_card.xml` (card) based on
  `viewType`, returning the same `ViewHolder` class for both — the
  `ViewHolder` constructor already does `findViewById` by ID, and both
  layouts will reuse the same view IDs
  (`browseContentItemIcon`/`Name`/`Play`/`PlayAll`/`Download`/
  `PlaylistAdd`) so `onBindViewHolder`'s logic (per-content-type
  visibility, click handlers, thumbnail loading) needs **zero changes** —
  it already only refers to views by ID via the shared `ViewHolder`.
- `contentList.setLayoutManager(...)` switches between
  `LinearLayoutManager` (list) and `GridLayoutManager(context, 2)` (card)
  when the mode changes. `BrowseContentItemAdapter.scrollToPositionId`
  already casts to `LinearLayoutManager` — this needs to branch on mode
  (`GridLayoutManager` is itself a subclass of `LinearLayoutManager`, so
  the existing `scrollToPositionWithOffset` call works unchanged for
  both; only the cast target type needs loosening to the common base).
- New `browse_content_item_card.xml`: a `CardView` (reusing the existing
  `CardView` wrapper pattern from the list row, just a vertical
  `LinearLayout` inside instead of a horizontal `RelativeLayout`) with,
  top to bottom: the icon (enlarged — see Decisions Needed), the title
  below it, then the same up-to-4 `ImageButton`s in a horizontal row at
  the bottom of the card. Visibility rules per content type are
  untouched (`onBindViewHolder` already drives this by ID).

## Toggle button (list/card switch)

A third `ImageButton` in the header, next to the existing Name/Date sort
toggle (`contentListSortToggle` in `fragment_content_list.xml`), following
the same plain-`ImageButton` + `app:tint` convention. Wired in
`ContentListFragment` the same way `initSortToggle` is wired today
(`initViewModeToggle`), persisted via `SharedPreferences` the same way
`settings_sort_order_key` is (new key, e.g.
`settings_browse_view_mode_key`), read on `BrowseContentItemAdapter`
construction like `sortMode`/`showThumbnails` already are.

## Thumbnail resolution

`IconDownloadTask`'s adapter-aware constructor
(`IconDownloadTask(ImageView, BrowseContentItemAdapter)`) hardcodes
48×48px regardless of the target `ImageView`'s actual size
(`IconDownloadTask.java:47-54`). If the card's icon area is meaningfully
bigger than 48dp (see Decisions Needed), thumbnails need to be requested
at the card's icon size, not always 48×48. Fix: pass the current mode's
icon pixel size into the existing `IconDownloadTask(ImageView, int, int)`
constructor (already present, just unused from the adapter) instead of
the fixed-48 one, computed once in `onBindViewHolder` from the current
`viewMode`.

## Remote-control / keyboard (DPAD) navigation

Today's `ViewHolder`'s `OnKeyListener` (in `onCreateViewHolder`) hard-codes
"this row's buttons, in this order" for DPAD left/right, and leaves
up/down to the framework. In a 2-column grid, left/right at a card's own
button boundary should additionally be able to move into the
neighboring card (not just within one card's buttons) for the
navigation to still make sense — see Decisions Needed below for how far
to take this.

## Testing

- Unit tests (`BrowseContentItemAdapterTest`-style, Mockito, matching the
  project's existing test conventions): `getItemViewType` returns the
  right ordinal per mode; `setViewMode` persists and triggers
  `notifyDataSetChanged()` (not a reload); the thumbnail-size
  computation picks the right pixel size per mode.
- No new UPnP/network-layer tests needed — nothing in that path changes.
- Manual/on-device verification required for the grid visual layout,
  thumbnail sharpness, and DPAD navigation (not meaningfully testable
  headlessly) — call this out explicitly when this reaches the `tester`
  agent/build phase, same caveat as the httpclient5/media3 runtime-only
  issues earlier in this project.

## Decisions (confirmed by user)

1. **Icon/thumbnail size in card mode**: enlarge to a square ~96–120dp
   area (final value picked during implementation to fit the card
   comfortably with title + buttons below it — likely 96dp). Thumbnail
   requests are sized to match, not upscaled from 48px.
2. **List/card toggle**: a header icon button, parallel to the existing
   Name/Date sort toggle, persisted globally via `SharedPreferences`
   (new key `settings_browse_view_mode_key`), read the same way
   `sortMode`/`showThumbnails` already are.
3. **Column count scales with screen width** (not fixed at 2). Computed
   from the `RecyclerView`'s actual available width, not just
   `screenWidthDp`, so split-screen/multi-window is handled correctly
   too: `spanCount = max(2, availableWidthPx / targetCardWidthPx)`, with
   `targetCardWidthPx` derived from a fixed target card width (e.g.
   ~160dp — chosen so 2 columns is exactly what a normal phone portrait
   width yields, matching the original ask, while a landscape phone or
   tablet naturally gets more). Recomputed via `GridLayoutManager
   .setSpanCount(...)` (no need to recreate the layout manager) on:
   - initial switch into CARD mode,
   - `RecyclerView` layout/size changes (width-change listener), which
     also covers rotation and multi-window resize without a separate
     `onConfigurationChanged` hook.
4. **DPAD/remote-control grid navigation**: v1 keeps today's
   within-card button navigation unchanged and relies on Android's
   default focus-search when movement crosses a card boundary — no
   hand-wired 2D grid traversal. Revisit later if on-device testing
   shows this feels wrong, per the user's explicit "simple for v1"
   choice.
