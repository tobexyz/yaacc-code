# Technical notes: verified APIs

## Issue #252: Sort by Date / Recently Added for UPnP folders

Shipped in two specs on this branch, both fully built, reviewed, and
security-reviewed (final verdict PASS on both gates for both specs; one
or two early review cycles FAILed and were fixed before merge, per the
loop's own rules). Full planning history (requirements/design/tasks/
decisions/review cycles, ~3400 lines) removed after shipping — this is
the compact record. Git history has the rest if ever needed.

### Client-side sort (yaacc browsing a remote DLNA/UPnP server)

Files: `ContentListFragment`, `BrowseContentItemAdapter`,
`BrowseItemLoadTask` (`yaacc/src/main/java/de/yaacc/browser/`).

- Header toggle (Name/Date) next to the folder path, each with its own
  remembered direction (persisted via `SharedPreferences`, applied on
  app restart and across folders).
- Sends `SortCriterion` (`-dc:date`/`+dc:date`/`dc:title`) through
  `UpnpClient.browseSync(...)` → `Browse`'s `orderBy` first, so a
  compliant server returns pre-sorted results. Falls back to a
  client-side sort once the whole folder has loaded (no mid-load
  reshuffle).
- If a server **rejects** `SortCriteria` outright (a UPnP action
  failure), the app retries the same request unsorted instead of
  showing an empty folder, and remembers the rejection for the rest of
  that folder's chunked load.
- "Date" is disabled per-folder when nothing loaded so far has a
  `dc:date` value — no offering a no-op sort.
- Name mode keeps folders listed before items; Date mode fully
  interleaves containers and items by date.

### Server-side sort (yaacc's own embedded UPnP server honoring other clients' sort requests)

Files: `YaaccContentDirectory`, `SortSupport`, and the 20
`*FolderBrowser`/`*ItemBrowser` classes
(`yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/`).

- `GetSortCapabilities` now advertises `dc:title,dc:date` instead of an
  empty list, and `Browse` requests with a valid `SortCriteria` for
  either property actually determine result order (previously silently
  ignored, always returning each browser's hardcoded default order).
- `SortSupport` is the shared, pure-JDK (no `android.*` imports, unit
  testable on plain JVM) utility: `validateSupported(...)`,
  `toMediaStoreSortOrder(...)` (pushes sort into the existing
  `ContentResolver.query`'s `sortOrder` arg for MediaStore-backed
  browsers — cheapest, avoids reading a whole cursor just to re-sort
  it), and `toComparator(...)` (in-memory fallback for SAF/synthetic
  container browsers with no cursor).
- `dc:date` wiring per content type: music already had it (`YEAR`);
  images now attach the already-queried `DATE_TAKEN` to the DIDL item
  (was queried but never wired); video now also queries `DATE_ADDED`;
  SAF files use `DocumentFile.lastModified()`.
- A `SortCriteria` for anything other than `dc:title`/`dc:date` is
  rejected with `UNSUPPORTED_SORT_CRITERIA`, same as malformed syntax.
  Default (no `SortCriteria`) order is unchanged — confirmed via a
  stable-sort argument during review, not just asserted.
- `MAX_SORT_CRITERIA = 8` guards against a resource-exhaustion DoS via
  an arbitrarily long duplicate-criteria chain (added during the
  security-review cycle that initially FAILed on this).

### Known limitations (by design, not bugs)

- Sort mode/direction is global, not remembered per folder.
- Only `dc:title`/`dc:date` are supported — no `upnp:artist`/genre/etc.
  on either side.
- Manual on-device verification against real DLNA servers (the gap
  neither spec could exercise in this sandboxed environment) was done
  by the user directly during development — confirmed working,
  including the server-rejects-sort-criteria fallback path and the
  Date-button-disabled-when-no-dates behavior.

## Browse card-grid view

Research notes for spec `2026-10-17-browse-card-grid-view` (Group 1,
research task). Verified by direct inspection of the actual vendored
AndroidX bytecode (`javap` disassembly of the `classes.jar` extracted
from the exact `.aar` Gradle resolves for this project) and, for
`View.addOnLayoutChangeListener`, the official platform doc fetched
live — not assumption, not training-data recall. Exact artifacts
inspected:

- `androidx.recyclerview:recyclerview:1.3.1` — the version this
  project's dependency graph resolves to (confirmed present under
  `~/.gradle/caches/modules-2/files-2.1/androidx.recyclerview/recyclerview/1.3.1/`;
  no explicit `implementation 'androidx.recyclerview:...'` line exists
  in `yaacc/build.gradle` today — it's pulled in transitively, e.g. via
  `androidx.appcompat`/`com.google.android.material`).
- `androidx.cardview:cardview:1.0.0` — already the version in actual
  use (`browse_content_item.xml:31` wraps the list row in
  `<androidx.cardview.widget.CardView>`, confirmed by grep).

### 1. `GridLayoutManager.setSpanCount(int)` mutates a live instance — confirmed

Disassembled `androidx.recyclerview.widget.GridLayoutManager.setSpanCount(int)`
from `recyclerview-1.3.1.aar`'s `classes.jar`
(`javap -c -p -classpath classes.jar androidx.recyclerview.widget.GridLayoutManager`).
Decompiled bytecode logic:

```java
public void setSpanCount(int spanCount) {
    if (spanCount == mSpanCount) {
        return;                       // no-op if unchanged
    }
    mPendingSpanCountChange = true;
    if (spanCount < 1) {
        throw new IllegalArgumentException("Span count should be at least 1. Provided " + spanCount);
    }
    mSpanCount = spanCount;           // mutates the field on THIS instance
    mSpanSizeLookup.invalidateSpanIndexCache();
    requestLayout();                  // re-layout, no new LayoutManager object
}
```

No constructor call, no `RecyclerView.setLayoutManager(...)` call —
the method only writes `mSpanCount` on the receiver and calls
`requestLayout()`. This confirms `((GridLayoutManager)
contentList.getLayoutManager()).setSpanCount(newCount)` (as `design.md`
decision 3 calls for, from a width-change listener) can be called
repeatedly on the one `GridLayoutManager` the `RecyclerView` already
holds, with no need to call `setLayoutManager` again.

### 2. `GridLayoutManager extends LinearLayoutManager` — confirmed, and the inherited method the existing code calls is not overridden

`javap -public -classpath classes.jar androidx.recyclerview.widget.GridLayoutManager`
prints:

```
public class androidx.recyclerview.widget.GridLayoutManager extends androidx.recyclerview.widget.LinearLayoutManager {
  ...
  public int getSpanCount();
  public void setSpanCount(int);
  ...
}
```

i.e. `GridLayoutManager`'s own class file declares `setSpanCount`,
`getSpanCount`, `onLayoutChildren`, etc., but **not**
`scrollToPositionWithOffset` — that method is inherited unmodified
from `LinearLayoutManager`. Consequences for
`BrowseContentItemAdapter.scrollToPositionId`
(`yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java:626-629`):

```java
public void scrollToPositionId(Integer id) {
    if (id == null) return;
    ((LinearLayoutManager) contentList.getLayoutManager()).scrollToPositionWithOffset(id, 0);
}
```

- The cast compiles today regardless of the declared type of
  `contentList.getLayoutManager()` (`RecyclerView.LayoutManager`) — a
  downcast to any named class always compiles; the question is only
  whether it succeeds at runtime.
- Since `GridLayoutManager extends LinearLayoutManager`, every
  `GridLayoutManager` instance **is** a `LinearLayoutManager` at
  runtime too (standard Java subtype rule) — the cast never throws
  `ClassCastException` when `contentList`'s current layout manager is
  a `GridLayoutManager`.
- Because `scrollToPositionWithOffset` is inherited, not overridden,
  calling it on a `GridLayoutManager` instance runs the exact same
  `LinearLayoutManager` implementation it runs for a plain
  `LinearLayoutManager` — behavior is unchanged, not just "compiles".
- Net: this method needs **zero code changes** when the adapter's
  `RecyclerView` is switched to a `GridLayoutManager` for `CARD` mode,
  confirming `design.md`'s claim ("only the cast target type needs
  loosening to the common base" — and in fact it doesn't even need
  loosening, since `LinearLayoutManager` already is that common base).

### 3. `RecyclerView.addOnLayoutChangeListener` fires on width changes (rotation, multi-window resize) — confirmed

`RecyclerView` (`recyclerview-1.3.1.aar`) extends `android.view.ViewGroup`
extends `android.view.View`
(`javap -p -classpath classes.jar androidx.recyclerview.widget.RecyclerView`
→ `public class androidx.recyclerview.widget.RecyclerView extends android.view.ViewGroup implements ...`).
`addOnLayoutChangeListener`/`removeOnLayoutChangeListener` do not
appear in `RecyclerView`'s own member list (`javap -p`) or in
`ViewGroup`'s (checked against this project's
`compileSdk 36` `android.jar`,
`/home/user/android-sdk/platforms/android-36/android.jar`,
`javap -p -classpath android.jar android.view.ViewGroup`) — so neither
class overrides it, and `RecyclerView.addOnLayoutChangeListener(...)`
resolves to the plain `android.view.View` implementation.

`android.jar`'s own signature
(`javap -p -classpath android.jar android.view.View`):

```
public void addOnLayoutChangeListener(android.view.View$OnLayoutChangeListener);
```

Official platform doc (fetched live via the `android-docs` MCP server,
`get_api_reference` for `android.view.View`, `developer.android.com/reference/android/view/View`),
verbatim:

> `addOnLayoutChangeListener(View.OnLayoutChangeListener listener)`
> — Add a listener that will be called when the bounds of the view
> change due to layout processing. Added in API level 11.

A rotation or multi-window resize changes the `RecyclerView`'s
measured width/height, which triggers a layout pass and therefore a
bounds change — so the listener fires in both cases, consistent with
`design.md` decision 3's plan to recompute span count from such a
listener instead of a separate `onConfigurationChanged` hook. API
level 11 is far below this project's `minSdk`, so no
`SDK_INT` guard is needed.

### 4. `IconDownloadTask` constructor signatures — confirmed, with one correction to the task's assumption

`yaacc/src/main/java/de/yaacc/util/image/IconDownloadTask.java:43-72`
(read directly — this class is yaacc's own code, not a library):

```java
public IconDownloadTask(ImageView imageView) {                                   // L43-45
    this(imageView, 48, 48);
}

public IconDownloadTask(ImageView imageView, BrowseContentItemAdapter browseContentItemAdapter) {  // L47-54
    this.imageView = imageView;
    this.cache = IconDownloadCacheHandler.getInstance();
    this.browseContentItemAdapter = browseContentItemAdapter;
    this.deviceId = null;
    this.targetWidth = 48;
    this.targetHeight = 48;
}

public IconDownloadTask(ImageView imageView, String deviceId) {                  // L56-63
    this.imageView = imageView;
    this.cache = IconDownloadCacheHandler.getInstance();
    this.browseContentItemAdapter = null;
    this.deviceId = deviceId;
    this.targetWidth = 48;
    this.targetHeight = 48;
}

public IconDownloadTask(ImageView imageView, int width, int height) {            // L65-72
    this.imageView = imageView;
    this.cache = IconDownloadCacheHandler.getInstance();
    this.browseContentItemAdapter = null;
    this.deviceId = null;
    this.targetWidth = width;
    this.targetHeight = height;
}
```

The `(ImageView, int, int)` overload exists exactly as the task
described, at the line range the task cited.

**Deviation from the task's assumption**: the task description states
this overload is "unused today". A repo-wide grep
(`grep -rn "new IconDownloadTask(" --include=*.java .`) shows it is
**already called once**, elsewhere in the same package:

```
yaacc/src/main/java/de/yaacc/browser/BrowseReceiverDeviceAdapter.java:197:
    new IconDownloadTask(holder.albumArt, 512, 512).execute(Uri.parse(player.getAlbumArt().toString()));
```

(`BrowseReceiverDeviceAdapter` loads 512×512 album art for a receiver
device's "now playing" view — a different adapter, different
`ImageView` field, unrelated to the browse-content-item list this spec
touches.) The other call sites are:

```
BrowseReceiverDeviceAdapter.java:213: new IconDownloadTask(holder.icon, device.getIdentity()...)   // (ImageView, String) overload
BrowseContentItemAdapter.java:489-490: new IconDownloadTask(holder.icon, this)                     // (ImageView, BrowseContentItemAdapter) overload — the one this spec replaces
BrowseDeviceAdapter.java:171: new IconDownloadTask(holder.icon)                                     // (ImageView) overload
```

This does not make the `(ImageView, int, int)` overload unsafe to add
a second, independent call site for in
`BrowseContentItemAdapter.onBindViewHolder` — each call constructs an
independent `IconDownloadTask` instance with its own final fields, and
`BrowseReceiverDeviceAdapter`'s existing usage shares no state with
`BrowseContentItemAdapter`'s. But Group 2 must account for one real
behavioral side effect of switching constructors, found by reading
`onPostExecute` (`IconDownloadTask.java:106-120`) together with how
`BrowseContentItemAdapter` uses its `asyncTasks` list
(`BrowseContentItemAdapter.java:85, 118, 491, 583-591, 593-597`):

```java
protected void onPostExecute(Bitmap result) {
    if (deviceId != null && !deviceId.equals(imageView.getTag())) {
        return;
    }
    if (result != null) {
        imageView.setVisibility(android.view.View.VISIBLE);
        imageView.setImageBitmap(result);
    }
    if (browseContentItemAdapter != null) {
        browseContentItemAdapter.removeTask(this);   // only called when this ctor set the field
    }
}
```

- `onBindViewHolder` (`BrowseContentItemAdapter.java:489-491`)
  explicitly does `asyncTasks.add(iconDownloadTask)` itself for every
  `IconDownloadTask` it creates, regardless of which constructor is
  used — that add call is unconditional.
- Removal from `asyncTasks` happens **only** via
  `removeTask(this)` inside `onPostExecute`, and that call is itself
  gated on `browseContentItemAdapter != null`.
- The `(ImageView, BrowseContentItemAdapter)` constructor (today's
  call) sets that field to `this`, so every successfully-completed
  thumbnail load removes itself from `asyncTasks`.
- The `(ImageView, int, int)` constructor always sets
  `browseContentItemAdapter = null` — it has no parameter for it. If
  `onBindViewHolder` is changed to call this overload instead (as the
  spec's Group 2 task describes), completed `IconDownloadTask`s will
  **stop removing themselves from `asyncTasks`**. Nothing else in
  `BrowseContentItemAdapter` clears or trims that list on success —
  `clear()` (`BrowseContentItemAdapter.java:291-299`) doesn't touch it,
  and the only other reader, `cancelRunningTasks()`
  (`BrowseContentItemAdapter.java:583-589`), only iterates/cancels, it
  doesn't remove entries either. So `asyncTasks` would grow by one
  finished (never-removed) `AsyncTask` + its captured `ImageView`
  reference per bound row for the lifetime of the adapter instance —
  a small but real unbounded-growth leak, not present today.
- This is a finding for Group 2 to resolve (e.g. keep passing a
  removal callback some other way, or accept/mitigate the growth), not
  something this research task fixes — flagging it per this task's
  scope (`tasks.md`'s Group 1 Constraints: "do not let Group 2 write
  code against unverified AndroidX API behavior" — this is yaacc's own
  code, not AndroidX, but the same "verify before Group 2 builds on it"
  spirit applies, so it's recorded here for Group 2 to see).

### Sources

- `androidx.recyclerview:recyclerview:1.3.1` `classes.jar`, extracted
  from
  `~/.gradle/caches/modules-2/files-2.1/androidx.recyclerview/recyclerview/1.3.1/3cb90a3c17fbb25e196f069101d99cd2f024d974/recyclerview-1.3.1.aar`,
  inspected with `javap -public`/`javap -c -p`.
- `androidx.cardview:cardview:1.0.0` `classes.jar`, extracted from
  `~/.gradle/caches/modules-2/files-2.1/androidx.cardview/cardview/1.0.0/158dbc2e2bc502815821191b04446b8f663c1874/cardview-1.0.0.aar`,
  inspected with `javap -public` (confirms `CardView extends
  android.widget.FrameLayout`, already the version used by
  `browse_content_item.xml:31`).
- `/home/user/android-sdk/platforms/android-36/android.jar` (this
  project's `compileSdk 36`), inspected with `javap -p` for
  `android.view.View` and `android.view.ViewGroup`.
- `developer.android.com/reference/android/view/View` — fetched live
  via the `android-docs` MCP server's `get_api_reference` tool, for
  `addOnLayoutChangeListener`'s documented contract.
- `yaacc/src/main/java/de/yaacc/util/image/IconDownloadTask.java:43-120`
  and `yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java:85,118,291-299,489-491,583-597,626-629`
  — read directly from this repo's working tree.

## As shipped: reconciliation with the design above (spec `2026-10-17-browse-card-grid-view`)

This section was written during planning/research, before implementation.
The feature is now built and both review gates have passed (general
review and security review — see
`.claude/specs/2026-10-17-browse-card-grid-view/review.md` and
`security-review.md`; the one security Critical, an inherited
`httpcore5` CVE regression, is covered below and fully traced in that
spec's `decisions.md`). The notes below record the concrete numbers and
gaps a future reader needs, rather than re-deriving them from the diff.

**Card icon size — 96dp, confirmed shipped.** The research above left
the exact dp value open ("~96-120dp"); the implementation settled on
the lower end, **96dp**, in two places that must stay in sync:
`yaacc/src/main/res/layout/browse_content_item_card.xml:67-68`
(`browseContentItemIcon`'s `layout_width`/`layout_height`, with a
layout comment explaining the choice) and
`BrowseContentItemAdapter.CARD_ICON_SIZE_DP = 96`
(`yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java:100`),
which feeds `dpToPx(...)` to size the `IconDownloadTask`'s requested
width/height for `CARD` mode (vs. the existing 48dp `LIST_ICON_SIZE_DP`
for list mode) — this is the actual fix for the fixed-48px thumbnail
bug `design.md`'s "Thumbnail resolution" section targeted.

**`targetCardWidthPx` / `TARGET_CARD_WIDTH_DP` — 160dp, lives in
`ContentListFragment`.** The span-count math (`CardGridSpanCount
.computeSpanCount`) is driven by `ContentListFragment
.TARGET_CARD_WIDTH_DP = 160`
(`yaacc/src/main/java/de/yaacc/browser/ContentListFragment.java:85`),
converted to pixels by the private `targetCardWidthPx()` method
(`ContentListFragment.java:342-344`, `TARGET_CARD_WIDTH_DP *
getResources().getDisplayMetrics().density` — density-scaled, not a
hardcoded pixel constant). Both the initial span-count computation on
toggle (`ContentListFragment.java:330`) and the width-change listener's
recompute (`ContentListFragment.java:294`) call this same method, so
there is one source of truth for the target width.

**Not verified headlessly — needs on-device confirmation.** Per this
project's existing pattern for runtime-only concerns (see the
httpclient5 Android-crash issue and the media3 runtime behavior
documented elsewhere in this file, neither of which a unit test or
`javap` inspection can exercise), the following are implemented and
compile/lint clean but have **not** been visually or interactively
confirmed, and need checking on a real device or emulator before
calling this feature done end-to-end:

- **Grid visual layout** — card spacing, `CardView` elevation/corner
  radius consistency with the list row, and whether the four
  `ImageButton`s fit comfortably under the 96dp icon + title without
  crowding at the chosen `targetCardWidthPx`.
- **Thumbnail sharpness** — whether 96dp-requested bitmaps actually
  render crisp (not upscaled/blurry) at the card's rendered size across
  density buckets; `IconDownloadTask`'s request size is correct by
  inspection, but the resulting bitmap's visual quality on screen is
  not.
- **DPAD cross-card navigation feel** — per `design.md` decision 4, v1
  deliberately relies on Android's default focus-search for movement
  *between* cards (nothing was hand-wired); whether that default
  behavior actually feels right for grid navigation on a TV/DPAD-driven
  device is unverified. Within-card button navigation needs no separate
  check — it reuses the same generic `onCreateViewHolder` wiring the
  list layout already uses, unchanged.

Flag these three for whoever reviews the PR as open on-device items, not
as silently assumed-working.

**Column count "scales with screen width" — unit-tested for the
span-count math only, not tablet-visually-confirmed.** `CardGridSpanCount
.computeSpanCount` has unit test coverage for its floor-division
behavior (phone-portrait width → 2, just-under-3× width → still 2,
wide/tablet-sized width → more than 2, narrower-than-target width →
never below 2 — see `CardGridSpanCountTest`), and the width-change
listener wiring that calls it on rotation/resize is implemented. None of
that has been confirmed by actually running the app on a tablet or a
resized multi-window on a larger screen — do not read "scales with
width" as tablet-tested; it is math-tested only.

**Known v1 limitation — no view-mode toggle button in landscape.**
`yaacc/src/main/res/layout-land/fragment_content_list.xml` has its own
`contentListSortToggle` row but, unlike the portrait
`fragment_content_list.xml`, was never given a
`contentListViewModeToggle` button (confirmed absent by grep — the
toggle button only exists in the portrait layout,
`fragment_content_list.xml:69`). `initViewModeToggle()` null-checks and
returns early in landscape, so nothing crashes, and a mode chosen in
portrait still *applies* correctly after rotating to landscape (the
persisted preference and the "set up LayoutManager for the persisted
mode" step both run regardless of the button's existence) — landscape
just has no way to *change* the mode via that button. This is a
deliberate, accepted v1 gap (see `decisions.md`'s "No view-mode toggle
in the landscape layout" entry), not an oversight silently left
unfixed; a follow-up to add the button to the landscape layout is
out of scope for this spec.

**Dependency note — httpcore5 CVE regression, fixed on this branch.**
This branch inherited an `httpclient5`/`httpcore5` version pairing from
`feat/issue252` that reintroduced two patched CVEs in `httpcore5`/
`httpcore5-h2`; Fix Group 1 corrected it by keeping `httpclient5` at
`5.2.1` (avoids the unrelated Android crash that version pairing was
chosen for) while bumping `httpcore5` and the transitively-pulled
`httpcore5-h2` to `5.4.4` (`yaacc/build.gradle:30,38,44`). This is part
of this branch's shipped state and both review gates passed against it;
the full CVE identification, decompilation evidence, and rationale are
already documented in
`.claude/specs/2026-10-17-browse-card-grid-view/decisions.md` and
`security-review.md` — not repeated here.

## Unified device sheet

Research notes for spec `2026-10-23-unified-device-sheet` (Group 1),
updated to match the code as built (Groups 2-3). Line numbers quoted in
section 6 are from the Group 1 research and may have moved. Section 8
summarizes how the pieces fit together as built.
Verified 2026-10-23 against `com.google.android.material:material:1.14.0`,
the version already declared in `yaacc/build.gradle:20` (nothing added).
Two sources were used:

- **[javap]**: `classes.jar` and `res/values/values.xml` extracted from
  `~/.gradle/caches/modules-2/files-2.1/com.google.android.material/material/1.14.0/b44cfc942dbe846c607aa50738e2bb1272796f8c/material-1.14.0.aar`,
  inspected with `javap -public`, `javap -constants` and `javap -c -p`.
- **[ref]**: the official API reference, fetched live through the
  `android-docs` MCP server (`get_api_reference`):
  `developer.android.com/reference/com/google/android/material/bottomsheet/BottomSheetBehavior`.
  That page's "View Source" link points at the `1.14.0` tag, so the
  reference matches this version.

### 1. Classes and imports

```java
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
```

From [javap] (`javap -public`):

```
public class BottomSheetDialogFragment extends androidx.appcompat.app.AppCompatDialogFragment {
  public BottomSheetDialogFragment();
  public BottomSheetDialogFragment(int);            // contentLayoutId
  public android.app.Dialog onCreateDialog(android.os.Bundle);  // returns new BottomSheetDialog(getContext(), getTheme())
  public void dismiss();
  public void dismissAllowingStateLoss();
}
public class BottomSheetDialog extends androidx.appcompat.app.AppCompatDialog {
  public BottomSheetBehavior<android.widget.FrameLayout> getBehavior();
  public void setDismissWithAnimation(boolean);
  public void setCancelable(boolean);
  public void setCanceledOnTouchOutside(boolean);
  ...
}
public class BottomSheetBehavior<V extends View> extends CoordinatorLayout$Behavior<V>
    implements com.google.android.material.motion.MaterialBackHandler {
  public static final int STATE_DRAGGING = 1, STATE_SETTLING = 2, STATE_EXPANDED = 3,
                          STATE_COLLAPSED = 4, STATE_HIDDEN = 5, STATE_HALF_EXPANDED = 6;
  public void setFitToContents(boolean);   public boolean isFitToContents();
  public void setHalfExpandedRatio(float); public float getHalfExpandedRatio();
  public void setState(int);
  public void setSkipCollapsed(boolean);
  public void setHideable(boolean);
  public void setDraggable(boolean);
  public void setMaxHeight(int);
  public void addBottomSheetCallback(BottomSheetBehavior$BottomSheetCallback);
  public static <V extends View> BottomSheetBehavior<V> from(V);
}
```

### 2. Getting the behavior

`BottomSheetDialog.getBehavior()` calls the private
`ensureContainerAndBehavior()` lazily if the behavior doesn't exist yet. That
method inflates `design_bottom_sheet_dialog`, finds the
`R.id.design_bottom_sheet` `FrameLayout`, and calls
`BottomSheetBehavior.from(bottomSheet)` and then `setHideable(cancelable)`
[javap: `javap -c -p ... BottomSheetDialog`, `getBehavior` and
`ensureContainerAndBehavior`]. So the fragment can call
`((BottomSheetDialog) requireDialog()).getBehavior()` at any point after
`onCreateDialog`, and never gets null back. There's no need for
`BottomSheetBehavior.from(view.getParent())`.

### 3. Starting half-expanded, then dragging to full height

Verified behavior [javap: `javap -c -p ... BottomSheetBehavior`]:

- **Defaults.** The constructor sets `fitToContents = true` and
  `halfExpandedRatio = 0.5f`. [ref] agrees: "Default value is true" for
  `setFitToContents`, and "The default value is 0.5" for
  `setHalfExpandedRatio`.
- **`setHalfExpandedRatio(float)`** throws `IllegalArgumentException`
  ("ratio must be a float value between 0 and 1") unless `0 < ratio < 1`.
  The half-expanded top offset is `parentHeight * (1 - ratio)`, from
  `calculateHalfExpandedOffset()`.
- **`setFitToContents(true)` while the state is `STATE_HALF_EXPANDED`
  forces `STATE_EXPANDED`.** The bytecode is
  `setStateInternal(fitToContents && state == 6 ? 3 : state)`, so **call
  `setFitToContents(false)` before `setState(STATE_HALF_EXPANDED)`**.
- **`setState(STATE_HALF_EXPANDED)` with `fitToContents == true`** turns
  into `STATE_EXPANDED` whenever the half-expanded offset is less than or
  equal to `fitToContentsOffset`, that is, whenever the content is no taller
  than the half-height. [ref] describes `STATE_HALF_EXPANDED` as "used when
  fitToContents is false".
- **`setState(STATE_DRAGGING | STATE_SETTLING)`** throws
  `IllegalArgumentException`. `setState(STATE_HIDDEN)` on a non-hideable
  sheet only logs a warning.
- **Expanded offset.** `getExpandedOffset()` returns `fitToContentsOffset`
  when `fitToContents` is true. Otherwise it returns `expandedOffset`
  (default 0, or the top inset when padding for the system window). With
  `fitToContents = false`, "expanded" therefore means the **top of the
  screen**, and the user drags up from half to get there.
- **Sheet height. This is something design.md doesn't cover.** The dialog's
  sheet container (`design_bottom_sheet_dialog.xml`, id
  `design_bottom_sheet`) is `layout_height="wrap_content"` [javap: the
  AAR's `res/layout/design_bottom_sheet_dialog.xml`]. With
  `fitToContents = false`, a short list (two or three receivers) would be
  pinned at the top in STATE_EXPANDED, leaving an empty scrim below it. To
  make "drag to full height" mean the sheet fills the screen, set the
  container's height to `MATCH_PARENT`, as in the snippet below.
- **Dialog start state.** `BottomSheetDialog.onStart()` only changes the
  state when it is `STATE_HIDDEN`, and then sets `STATE_COLLAPSED`. So a
  state set before `show()`, or in the fragment's `onStart()` after
  `super.onStart()`, is kept. Without a layout pass, `setState` stores the
  requested state through `setStateInternal`, and it is applied on the
  first `onLayoutChild`.
- **Dragging below half-expanded** goes to `STATE_COLLAPSED`, at the peek
  height. Use `setSkipCollapsed(true)` to make a drag down from expanded or
  half go straight to hidden, which dismisses the dialog. [ref]: "Setting
  this to true has no effect unless the sheet is hideable". The dialog sets
  hideable to its `cancelable` flag, which is true by default.

Usage pattern. As built, this lives in
`DeviceSheetFragment#applyInitialSheetState`. It runs once, from the first
`onStart()` after `super.onStart()`, so coming back from a player activity
keeps the user's drag position. It sets the container height through
`setLayoutParams`, and passes `STATE_EXPANDED` instead of
`STATE_HALF_EXPANDED` when not in touch mode (see section 5):

```java
@Override
public void onStart() {
    super.onStart();                          // DialogFragment shows the dialog here
    BottomSheetDialog dialog = (BottomSheetDialog) requireDialog();
    View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
    if (sheet != null) {                      // full-height container, so EXPANDED fills the screen
        sheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        sheet.requestLayout();
    }
    BottomSheetBehavior<FrameLayout> behavior = dialog.getBehavior();
    behavior.setFitToContents(false);         // must come before HALF_EXPANDED (see above)
    behavior.setHalfExpandedRatio(0.5f);      // 0 < ratio < 1, else IllegalArgumentException
    behavior.setSkipCollapsed(true);          // dragging down from half dismisses
    behavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
}
```

### 4. Theme. This is something design.md doesn't cover.

`BottomSheetDialogFragment.onCreateDialog` builds
`new BottomSheetDialog(getContext(), getTheme())`. With theme 0, the private
`BottomSheetDialog.getThemeResId` resolves `?attr/bottomSheetDialogTheme`.
If that attribute isn't set, it falls back to
`R.style.Theme_Design_Light_BottomSheetDialog`
[javap: `getThemeResId` bytecode].

yaacc's `AppTheme` has `Theme.MaterialComponents.DayNight.Bridge` as its
parent (`yaacc/src/main/res/values/styles.xml:21`). In the 1.14.0
`values.xml`, the Bridge chain
(`...DayNight.Bridge -> ...Light.Bridge -> Base.V14.Theme.MaterialComponents.Light.Bridge -> Platform.MaterialComponents.Light -> Theme.AppCompat.Light`)
**does not set `bottomSheetDialogTheme`**: the Bridge base style has 68
items, none of them for dialogs or sheets. The non-Bridge
`Base.V14.Theme.MaterialComponents.Light` sets it to
`@style/ThemeOverlay.MaterialComponents.BottomSheetDialog`. Without action,
the sheet would therefore use the light `Theme.Design` dialog theme, even
in night mode. There were two ways to fix it:

- Override `getTheme()` in `DeviceSheetFragment` to return
  `com.google.android.material.R.style.ThemeOverlay_MaterialComponents_BottomSheetDialog`.
- Add `<item name="bottomSheetDialogTheme">@style/ThemeOverlay.MaterialComponents.BottomSheetDialog</item>`
  to `AppTheme`.

**As built:** the first, through an app style.
`DeviceSheetFragment#getTheme()` returns `R.style.ThemeOverlay_App_DeviceSheet`,
declared in `yaacc/src/main/res/values/styles.xml` with
`ThemeOverlay.MaterialComponents.BottomSheetDialog` as its parent. Being an
overlay, it keeps `AppTheme`'s DayNight colors. `AppTheme` itself is
unchanged.

### 5. Back and D-pad focus inside the dialog

- **Back.** Back on a cancelable dialog calls `Dialog.cancel()`.
  `BottomSheetDialog.cancel()` dismisses straight away when
  `dismissWithAnimation` is false, which is the default. When it is true,
  it calls `behavior.setState(STATE_HIDDEN)` instead, and the dialog's
  callback dismisses it once the sheet is hidden [javap: `cancel()`].
  `BottomSheetDialog` also creates a `MaterialBackOrchestrator` over the
  behavior and starts listening for back callbacks only while it is
  `cancelable` (`updateListeningForBackCallbacks`).
  `BottomSheetBehavior.handleBackInvoked()` sets `STATE_HIDDEN` if the
  sheet is hideable, otherwise `STATE_COLLAPSED`. It checks
  `SDK_INT >= 34` for the predictive-back animation. Either path ends with
  the dialog dismissed, so **Back dismisses the sheet with no extra code**,
  as long as the default `cancelable = true` is left alone.
  `BottomSheetDialogFragment.dismiss()` animates the hide first when the
  dialog has `dismissWithAnimation` set and the behavior is hideable.
- **D-pad focus is trapped in the dialog.** The dialog is its own window
  (platform `Dialog`), so D-pad focus search stays inside it and can't
  reach the Content tab behind it. Material adds no key handling of its
  own: no `onKeyDown` or `dispatchKeyEvent` in `BottomSheetDialog`'s
  `javap -c -p` member list. The behavior's `updateAccessibilityActions`
  only adds accessibility expand and collapse actions, not D-pad bindings.
- **Risk (inferred from bytecode, not yet seen on a TV).** In
  `STATE_HALF_EXPANDED`, the sheet is laid out at its full height and
  moved down with `ViewCompat.offsetTopAndBottom` (`onLayoutChild`). The
  bottom half is off-screen but still laid out, so RecyclerView rows there
  are attached and focusable. D-pad down can move focus to a row below the
  screen edge, and nothing scrolls, because the row is inside the
  RecyclerView's own bounds. **As built:** the sheet opens in
  `STATE_EXPANDED` when the UI mode is `UI_MODE_TYPE_TELEVISION` or the
  activity's decor view isn't in touch mode
  (`DeviceSheetFragment#isTouchInput`), and in `STATE_HALF_EXPANDED`
  otherwise. Still to confirm in the manual TV check.
- **Initial focus.** design.md wants focus on the first receiver's
  checkbox. Nothing in Material sets initial focus. **As built:** when not
  in touch mode, the fragment calls `DeviceSheetAdapter#requestInitialFocus()`,
  and the adapter posts a focus request when it binds the first
  receiver row (the checkbox in Phase 1; since Phase 3 the normal sheet has no
  checkbox and the row takes the focus, in pick mode the first checkbox). Stable ids (`R:<udn>` / `P:<playerId>` keys) and `DiffUtil`
  keep focus on the same row across refreshes.

### 6. How the Player tab learns about new or ended players

`grep -rn "addPlayer\|removePlayer\|shutdown(" yaacc/src/main/java/de/yaacc/player/PlayerService.java`:

```
106:    public void addPlayer(Player player) {
122:    public void removePlayer(Player player) {
193:        shutdown();
295:                addPlayer(result);
332:                addPlayer(result);
382:                    shutdown(player);
395:                    shutdown(result);
423:            shutdown(result);
433:            shutdown(result);
519:    public void shutdown(Player player) {
530:    public void shutdown() {
533:            shutdown(player);
```

**Answer: polling on `onResume`. There's no listener or broadcast.**

- `PlayerService.addPlayer` (`PlayerService.java:106-120`) puts the player
  into `currentActivePlayer`, a plain `HashMap` (`:59`). It registers a
  `"playing"` property listener on the player that only calls
  `updateForegroundState()`, then calls `updateForegroundState()` itself.
  `removePlayer` (`:122-125`) and `shutdown(Player)` (`:519-525`) remove
  the player from the map and call `updateForegroundState()`. **None of the
  three notifies any observer.** `updateForegroundState` only manages the
  foreground notification.
- Callers: `addPlayer` from `createPlayer...` (`PlayerService.java:295,
  332`), and again from each player's async `onServiceConnected`
  (`AbstractPlayer.java:159`, `LocalImagePlayer.java:91`). `removePlayer`
  is called from `onServiceDisconnected` (`AbstractPlayer.java:167`,
  `LocalImagePlayer.java:98`) and `AbstractPlayer.onDestroy`
  (`AbstractPlayer.java:965`). `shutdown(this)` is called from `exit()`
  (`AbstractPlayer.java:732`, `LocalImagePlayer.java:227`).
- `PlayerListFragment` reads `upnpClient.getCurrentPlayers()`
  (`UpnpClient.java:829-834`, which delegates to
  `PlayerService.getCurrentPlayers()` at `:445-448`, an **unmodifiable live
  view** of the map's values, not a copy). It reads it in
  `populatePlayerList()` (`PlayerListFragment.java:80-93`), which is
  triggered only from `init` (`:58`, on view creation) and `onResume`
  (`:64-67`). It does register as a `UpnpClientListener` (`:57`), but that
  interface (`UpnpClientListener.java:29-39`) only carries device
  added/removed/updated events, and all five callbacks in the fragment are
  empty. A player that starts or ends while the Player tab is visible
  shows up only after the tab is resumed again.
- **Reusable by `DeviceSheetFragment`? No.** Refreshing on `onResume`
  doesn't help a sheet that stays open while playback starts. For example,
  the user picks a receiver and then taps play, and the session row should
  appear. Per-player state changes *are* observable through
  `Player#addPropertyChangeListener` / `removePropertyChangeListener`
  (`Player.java:131,138`), which fire `"playing"` (`AbstractPlayer.java:465`)
  and the item-change property (`AbstractPlayer.java:571`). That covers
  updating an existing session row, but not adding or removing one.
- **Added in Group 2:** a player-set listener on `PlayerService`,
  `addPlayerListChangeListener(PlayerListChangeNotifier.Listener)` /
  `removePlayerListChangeListener(...)`, with `onPlayerAdded(Player)` and
  `onPlayerRemoved(Player)`. It fires after the map has changed. `addPlayer`,
  `removePlayer` and `shutdown(Player)` are reached from
  `onServiceConnected`/`onServiceDisconnected`, which run on the main
  thread, and from `createPlayer...`, whose thread depends on the caller,
  so callbacks are posted to the main thread. UI code registers through
  `UpnpClient.addPlayerListChangeListener` / `removePlayerListChangeListener`
  instead: `UpnpClient` keeps the listeners and attaches them to the
  service whenever it (re)binds, without duplicates, so a sheet opened
  before `PlayerService` is bound, or kept open across a rebind, still gets
  events. The sheet registers in `onStart()` and unregisters in `onStop()`
  (with `onDestroyView()` as a backstop). Because `getCurrentPlayers()` is
  a live view of the map, the sheet (`DeviceSheetAdapter#refresh`) copies
  it, `new ArrayList<>(...)`, on the main thread before building
  `DeviceSessionGrouper.PlayerRef`s. `ReceiverRowBinder#primaryPlayer`
  iterates the live view directly on the main thread; that is safe because
  the backing map is a `ConcurrentHashMap` (weakly consistent iteration).
- **Repeat calls in both directions. The notifier ignores them.**
  - **Add twice.** `addPlayer` can run twice for the same player: once
    from `createPlayer...` (`PlayerService.java:295, 332`) and once from
    `onServiceConnected` (`AbstractPlayer.java:159`,
    `LocalImagePlayer.java:91`). Each call also adds another anonymous
    `"playing"` listener that is never removed
    (`PlayerService.java:113-117`). That is harmless today, because the
    listener is idempotent, and out of scope here.
  - **Remove twice.** `shutdown(Player)` (`PlayerService.java:519-525`)
    removes the player from the map and then calls `player.onDestroy()`.
    `AbstractPlayer.onDestroy()` calls `playerService.removePlayer(this)`
    again (`AbstractPlayer.java:958-965`).
  - So the notifier (`PlayerListChangeNotifier`, as built in Group 2)
    decides from what `put` returns: `null` fires **"added"**; the same
    instance fires nothing (a re-add); a *different* instance fires
    **"removed"** for the replaced player and then **"added"** for the new
    one (a same-id replacement - `MultiContentPlayer`'s id is fixed, see
    decisions.md). Removal uses `remove(id, player)` and fires
    **"removed"** only if that exact instance was still in the map, so the
    second removal from `onDestroy()`, or a stale instance after a
    replacement, notifies nothing. Otherwise one player start or stop
    would notify twice, or for the wrong player.
- **Group 2: threads that write `currentActivePlayer`, and the fix.** The
  map is written off the main thread. `ContentItemPlayTask.doInBackground`
  calls `ContentListFragment.play(upnpClient.initializePlayers(...))`,
  which reaches `PlayerService.createPlayer` and then `addPlayer` on a
  background thread. The renderer path does the same
  (`AvTransportMediaRendererPlaying.java:88`). Meanwhile the Player tab
  reads the live `getCurrentPlayers()` view on the main thread. As a
  `HashMap`, the map could throw `ConcurrentModificationException` or
  lose entries. It is now a `ConcurrentHashMap`. Its `values()`
  iteration is weakly consistent and never throws, and `put`/`remove`
  are atomic, so the "added"/"removed" decision taken from their return
  values (`PlayerListChangeNotifier`) is reliable. Keys are `int` ids,
  and every put site dereferences the player before the put, so there
  are no null keys or values. `getCurrentPlayerById(null)` returns null
  explicitly. The listener API is
  `PlayerService.addPlayerListChangeListener(PlayerListChangeNotifier.Listener)`
  / `removePlayerListChangeListener(...)`. Callbacks are posted to the
  main thread. A listener removed before a queued delivery runs is not
  called.

### 7. `DeviceSessionGrouper`

- `yaacc/src/main/java/de/yaacc/browser/DeviceSessionGrouper.java` has the
  public API from design.md, implemented in Group 2. `PlayerRef` exposes
  record-style accessors `playerId()`, `deviceId()`, `playing()` and
  `sequence()` (the creation sequence), matching `Row`'s `kind()`,
  `deviceId()` and `playerId()`. These are plain final classes, not
  records, with no `android.*` imports.
- `group` lists receivers in the given order, each followed by its
  sessions in ascending creation sequence (oldest first).
  `primarySession` picks the playing session with the highest creation
  sequence, otherwise the highest overall; equal sequences fall back to
  the higher player id, so the result never depends on input order. The
  player id is only the lookup key: `AVTransportPlayer` ids are random
  and local player ids are fixed per type, so they say nothing about age.
- `yaacc/src/test/java/de/yaacc/browser/DeviceSessionGrouperTest.java`
  (JUnit 4.13.2, plain JVM) pins two readings of design.md that it left
  open:
  1. Sessions on a device that isn't a receiver get **no RECEIVER row**.
     A receiver row means bound device controls, which a vanished device
     can't have. The adapter labels the trailing group from the session
     row's `deviceId()`. Those sessions stay contiguous per device, in
     ascending creation-sequence order (oldest first), after all receiver
     groups. As built in Group 2 (fix cycle 1), sessions are ordered by the
     creation sequence `PlayerService#getPlayerCreationSequence` assigns,
     not by player id (see the spec's decisions.md).
  2. With no receivers at all, every session is still listed.

### 8. As built: how the pieces fit

- **Entry points (new layout only).** The sheet is reached from the new layout's
  receiver bar (`R.id.browserReceiverBar` in `activity_browser.xml`, bound by
  `ReceiverBarController`) through `BrowserHost.showReceivers()`. (Phase 3: the
  no-receiver prompt is gone; the same fragment also serves as the "Play on..."
  picker, see "Sharing local files", "As built: the receiver picker".) The classic layout (`TabBrowserActivity`) has no
  entry point: `showReceivers()` there still switches to the Receiver tab, and no
  classic fragment opens `DeviceSheetFragment`. (Phase 1 had put the sheet into
  the classic Content tab; owner feedback, Group 11, reverted that.)
- **Receiver bar label.** Phase 1 showed the selected receivers; since Phase 3
  `ReceiverBarLabel` names the receivers that have a player in
  `PlayerService.getCurrentPlayers()` (a paused player counts), cleaned, merged,
  sorted and joined with `"; "`, or `receiver_bar_none_playing` ("No receiver
  playing"). The bar refreshes on the player-list listener and the receiver
  events. See "Sharing local files", "As built: the receiver picker".
- **Receiver choice per action (new layout only).** Phase 1 opened the sheet
  when playing with no receiver selected
  (`ContentListFragment#openDeviceSheetIfNoReceiverSelected()` and the
  `receiver_bar_none_selected` text; both are removed). Now play, play-all, an
  item tap and add to playlist call `ContentListFragment#startPlayAction`, which
  opens the "Play on..." picker first. In classic `startPlayAction` starts at
  once, so play behaves as on `origin/feat/issue287`, and the
  `error_no_receiver_selected` toast in `PlayerService.createPlayer` stays for
  classic and for non-UI callers.
- **Sheet contents.** `DeviceSheetFragment.show(FragmentManager)` does
  nothing if the sheet is already showing or the state is saved.
  `DeviceSheetAdapter#refresh()` takes the receivers from
  `getDevicesProvidingAvTransportService()` (the Receiver tab's source
  order: local device first, then by name), resolves each by id through
  `UpnpClient.getDevice(id)`, and lays the rows out with
  `DeviceSessionGrouper.group`. Receiver rows are bound by
  `ReceiverRowBinder`, the same binder the Receiver tab's
  `BrowseReceiverDeviceAdapter` uses; the sheet's rows have no selection
  checkbox (Phase 3; the classic Receiver tab keeps its checkbox through the
  four-argument `bind`). Session rows
  (`device_sheet_player_session_item.xml`) show the player name, current
  title and album art (through `IconDownloadTask`), plus the device id for
  sessions whose device isn't a receiver; all LAN strings are set with plain
  `setText`. Tapping a session sends the player's own notification
  `PendingIntent` (`ReceiverRowBinder.openPlayer`), as the Player tab does.
- **Receiver row tap.** It opens the activity of the device's primary
  session (`DeviceSessionGrouper.primarySession`). Only the
  `UpnpClient.LOCAL_UID` row is a no-op, as before the extraction; yaacc's
  own renderer (a `LocalDevice` with its own UDN, controlled through an
  `AVTransportPlayer`) opens its player.
- **Live updates, only while visible.** `startLiveUpdates()` runs from
  `onStart()` and `stopLiveUpdates()` from `onStop()` (with
  `onDestroyView()` as a backstop), so nothing polls while a player activity
  is on top or the app is in the background. Coming back, the list is
  rebuilt once. While started, the sheet:
  - subscribes to `RendererStatusMonitor.getShared()` with the AVTransport
    devices (10 s poll per remote renderer);
  - listens for player add/remove through
    `UpnpClient.addPlayerListChangeListener` and rebuilds the list;
  - polls every 2 s for the local device's status (it has no monitor feed)
    and for changed session titles or art, rebinding only rows whose shown
    state changed.
- **One shared monitor.** `RendererStatusMonitor.getShared()` is the only
  instance. `subscribe(listener, devices)` starts polling devices not yet
  polled; `unsubscribe(listener)` stops all polling once the last listener
  leaves, and a generation counter drops results still in flight. The
  bookkeeping is in `MonitorSubscriptions` (plain Java, unit-tested in
  `MonitorSubscriptionsTest`). The Receiver tab (`ReceiverListFragment`)
  uses the same instance, subscribing in `onResume()` and unsubscribing in
  `onPause()`. All of it is main-thread only.
- **Cleanup.** `onDestroyView()` calls `DeviceSheetAdapter#release()`, which
  shuts down the sheet's `ReceiverRowBinder` executor, so no thread is left
  behind per open.
- **Known limitations (follow-ups in the spec's decisions.md).** The classic
  layout does not offer the sheet (owner decision, Group 11); there the Receiver
  and Player tabs are the only way to control receivers and players. The sheet
  doesn't add or remove receivers that appear or vanish on the network while
  it is open (known receivers' status does update). After rotation it returns
  to its initial half/full position. `RendererStatusMonitor` polls on a
  single thread, so one slow renderer delays status for every receiver in
  both the tab and the sheet; transport controls are unaffected.

## Classic and new layout

Two browser activities share one app: the **classic** layout
(`TabBrowserActivity`: four tabs) and the **new** single-screen layout
(`BrowserActivity`: server list, then a server's content, with a receiver bar
and a mini-player on every level). The "Layout" setting picks one. This section
records the layout as built for spec `2026-10-23-unified-device-sheet`, Phase 2
(Groups 5-9), verified 2026-10-24 against the checkout at `e312ee8b` on branch
`feat/unified-device-sheet`, followed by the Group 5 research it was built
from (kept for its citations). Group 11 (owner feedback, see "Owner feedback
(Group 11)") changed three behaviors after that verification: the timer entry,
the classic footer and the mini-player. "As built" subsections cite classes, not line
numbers; the research subsections cite line numbers of the checkout *before*
Group 6, which have since moved.

Versions (nothing added by Phase 2): `yaacc/build.gradle` has `targetSdkVersion
33`, `minSdkVersion 27`. `appcompat:1.8.0` resolves `androidx.activity:activity:1.8.0`,
`androidx.fragment:fragment:1.5.4` and `androidx.core:core:1.16.0`
(`./gradlew --offline :yaacc:dependencies --configuration debugRuntimeClasspath`).
`androidx.preference:preference:1.2.1` and `material:1.14.0` are unchanged;
`swiperefreshlayout` is not a dependency, so the new layout has a refresh
button and no pull-to-refresh.

Sources, and how each claim in the research subsections is marked:

- **[code]**: read in this repo, cited as `file:line` (pre-Group-6 numbers).
- **[javap]**: `javap -public` on `activity-1.8.0.aar` from
  `~/.gradle/caches/modules-2/files-2.1/androidx.activity/activity/1.8.0/`.
- **[docs]**: official documentation, fetched through the `android-docs` MCP
  (it worked in this session) or `curl` on developer.android.com. URL given.
- **[aosp]**: AOSP source, tag `android-14.0.0_r1`, fetched from
  `raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/`
  (`android.googlesource.com` was not reachable). Platform behavior that the
  public docs do not state. Newer releases may differ.
- **[reasoned]**: inferred from code or the sources above, not run on a device.
  "Known limitations / owner decisions" lists what the owner still has to
  confirm.

### As built: overview

All classes are in `de.yaacc.browser` unless a package is given.

| Class | Role |
|---|---|
| `de.yaacc.LauncherActivity` | Invisible (`Theme.NoDisplay`), exported entry. Holds the launcher, Android TV and share filters and starts the browser of the effective layout with a fresh intent. |
| `LauncherForwardPolicy` | Plain Java. Decides what the launcher forwards (see "Launcher"). |
| `BrowserLauncher` | `effectiveLayout(String)` / `effectiveLayout(Context)`, `targetClass(...)`, `relaunchIfOtherLayout(Activity, Layout)`, the `NEW_LAYOUT_AVAILABLE` constant (`true`). |
| `BrowserLayoutChoice`, `BrowserLayoutSwitch`, `RelaunchGuard` | Plain Java: stored string to `Layout`, the "relaunch?" decision, the loop guard. |
| `TabBrowserActivity` | Classic. Exported, no intent filters, implements `BrowserHost`. |
| `BrowserActivity` | New. `exported="false"`, no intent filters, implements `BrowserHost`. |
| `BrowserHost` | Navigation the fragments and helpers may ask of their activity. |
| `BrowserActivitySupport` | Moved verbatim out of `TabBrowserActivity`: runtime permission request (code 101, result not handled), battery-optimization prompt (started on every `onCreate`), and the common menu items (exit, settings, about, log). |
| `ServerListFragment` | Server list for both layouts (see "Server list"). |
| `ContentListFragment` | Content of one server for both layouts. Classic: shows the original static footer (provider line, receivers line). New: hides it; the host owns the receiver bar. |
| `BrowserBackNavigation` | Plain Java: back decision of the new layout. |
| `ShareIntentHandler`, `ShareUriPolicy` | Shared handler for a link or one file shared into yaacc (Phase 3 added the file path, the pending share and the picker, see "Sharing local files"). |
| `ShutdownTimerController`, `ShutdownTimerToggle` | Shutdown timer UI logic and the toolbar tap decision (stop, or ask for a duration). |
| `StreamingPermissionHelper` | MediaProjection permission flow of the yaacc server row. |
| `BackgroundIdleTimer`, `StartedBrowserCounter` | One process-wide idle exit. |
| `ReceiverBarController`, `ReceiverBarLabel` | Receiver bar binding and label (new layout only). |
| `MiniPlayerBar`, `MiniPlayerAdapter`, `MiniPlayerRows`, `MiniPlayerArtKeys`, `MaxHeightRecyclerView`, `MiniPlayerPresenter` | Mini-player (new layout only): controller, row adapter, row order and art key, per-player art bookkeeping, height-capped list, text cleaning. |
| `de.yaacc.upnp.server.LocalServerLifecycle` | Starts or stops `YaaccUpnpServerService` from the preferences; both browsers call it from `onResume`. |

### As built: `BrowserHost`

```java
public interface BrowserHost {
    void showServers();
    void showContent();
    void showReceivers();
    void showPlayers();
    default boolean providesShutdownTimer()  { return false; }  // toolbar owns the timer
    default boolean providesReceiverBar()    { return false; }  // activity owns the receiver bar and the sheet
    default boolean showsBackAtContentRoot() { return false; }  // back arrow also at a server's root
    // Phase 3, see "Sharing local files": default no-ops returning false
    default boolean pickReceivers(PickedAction action, String shareName, Collection<String> initialIds) { return false; }
    default boolean isPickOpen(String pickId)                { return false; }
    default void    onPendingShare(String token, String displayName) { }
}
```

Callers tolerate "no host" and may call from any thread (`deviceAdded` is a
`UpnpClientListener` callback), so each implementation hops to the main
thread itself.

| Method | Classic (`TabBrowserActivity`) | New (`BrowserActivity`) |
|---|---|---|
| `showServers()` | `setCurrentTab(SERVER)` | `popBackStack("browser_content", POP_BACK_STACK_INCLUSIVE)`; a no-op when the server list is already on top, and skipped once the fragment state is saved |
| `showContent()` | `setCurrentTab(CONTENT)` | runs pending transactions, then replaces the container with a new `ContentListFragment` and adds it to the back stack under `browser_content`; a no-op when a `ContentListFragment` is already on top, skipped when finishing or state saved. The provider is stored by `UpnpClient#setProviderDevice`, which `ContentListFragment` reads itself, so no argument is passed |
| `showReceivers()` | `setCurrentTab(RECEIVER)` | `DeviceSheetFragment.show(getSupportFragmentManager())` (ignores a second call or a saved state) |
| `showPlayers()` | `setCurrentTab(PLAYER)` | no-op: the mini-player follows the running players by itself |

Capability defaults: the new activity returns `true` for all three, classic
keeps `false`.

Live call sites: `ServerListClickListener` (`parent instanceof BrowserHost`,
then `showContent()`), the `ShareIntentHandler` callback (classic passes
`this::showPlayers`, new passes `null`), `ServerListFragment` (`providesShutdownTimer()`,
and an `instanceof BrowserHost` guard around `setLocalServerState`),
`ContentListFragment` (`providesReceiverBar()`, `showsBackAtContentRoot()`,
`showServers()`). The four `getParent() instanceof TabBrowserActivity`
branches of the research (`getParent()` is `null` for a plain activity, so they
never ran) were removed, not wired: classic back on the Content root, Receiver
and Player tab is still swallowed (`onBackPressed()` returns `true`), as before.
Making classic back work is an owner decision.

### As built: launcher, effective layout, relaunch

**Launcher.** `LauncherActivity extends android.app.Activity` (not
AppCompat; `Theme.NoDisplay` is a framework theme), no `launchMode`, no
`taskAffinity`, label `@string/title_activity_main`. It holds the three filters
that used to sit on `TabBrowserActivity`, unchanged: `VIEW`+`MAIN` /
`DEFAULT`+`LAUNCHER` (the `VIEW` half has no `<data>`), `MAIN` /
`LEANBACK_LAUNCHER`, and `SEND` / `DEFAULT` with `text/plain`. Phase 3 added a
fourth, `SEND` / `DEFAULT` with `audio/*`, `video/*` and `image/*` (see "Sharing
local files"). `onCreate`
builds a **fresh** `Intent(this, BrowserLauncher.targetClass(this))`, applies
the forward policy, calls `startActivity` and `finish()` in `finally`
(`Theme.NoDisplay` requires `finish()` before `onResume` completes). Any
`RuntimeException` while reading the incoming intent is caught, logged by
class name only, and the launch is plain.

**Forward policy** (`LauncherForwardPolicy.decide(action, type, text)`, plain
Java, compared exactly and case-sensitively). This subsection is the Phase 2
state; Phase 3 adds ONE more forward, a single `content:` file (fresh intent,
one ClipData item, the read flag and a launch token, see "Sharing local files",
"As built: from the share menu to a pending share"). Everything below still
holds for the text forward and the plain launch:

- Forwarded: only `android.intent.action.SEND` with type `text/plain` and a
  non-empty text of at most `ShareUriPolicy.MAX_TEXT_LENGTH` (2048) chars. The
  forward carries action `SEND`, type `text/plain` and `EXTRA_TEXT`, nothing
  else. The text is `getStringExtra(EXTRA_TEXT)`, else the text of the last
  `ClipData` item that has one and is within the cap (a clip item's URI is
  never read).
- Everything else (`MAIN`, `VIEW`, no action, unknown actions, another type,
  empty or over-long text) becomes a plain launch. A text over the cap is
  dropped, not truncated.
- Never copied: flags (so no `FLAG_GRANT_*`), `data`, `ClipData`, URIs,
  `EXTRA_STREAM`, `EXTRA_INTENT`, other extras, categories, selector. The
  incoming intent is not modified. Origin is erased: no browser reads
  `getReferrer()` or `getCallingPackage()`.

**Effective layout.** `BrowserLauncher.effectiveLayout(Context)` is the only
reader of the preference (see "Layout preference"). It returns `CLASSIC` when
`NEW_LAYOUT_AVAILABLE` is `false`, else `BrowserLayoutChoice.resolve(stored)`.
`NEW_LAYOUT_AVAILABLE` is `true` since Group 8; keep the switch so a build
without a usable new layout cannot strand a stored `"new"` (backup restore,
adb) on a layout that does not exist. `targetClass(Context)` and
`targetClass(Layout)` are a two-way `if` on the enum. The launcher, the three
internal notification launches (`Yaacc.createYaaccGroupNotification`,
`PlayerService.createSessionActivity`, `PlayerService.updateServiceNotification`:
explicit, no extras, `FLAG_IMMUTABLE`) and the resume check all go through it.

**Resume check.** `BrowserLauncher.relaunchIfOtherLayout(activity, own)`
computes the effective layout once, asks `BrowserLayoutSwitch.shouldRelaunch(own,
effective)` and, if the layouts differ, asks `RelaunchGuard.shared().tryRelaunch()`
(one relaunch per 2000 ms, process-wide, monotonic clock; when it denies, the
activity stays and logs). Then it starts `new Intent(activity, targetClass(effective))`
(nothing from `getIntent()`), calls `overridePendingTransition(0, 0)` and
`finish()`; it returns `true` so the caller skips the rest of its callback. No
`CLEAR_TASK`: `finish()` only, so players and the other activities stay.
`TabBrowserActivity` calls it at the top of `onResume`; `BrowserActivity`
calls it first in `onCreate` (before `setContentView`, the share handler or any
listener) and again in `onResume`. Consequence of the order (Phase 2): a share that reached a
`TabBrowserActivity` whose layout was no longer the effective one was handled by
its `onCreate` handler. Since Phase 3 `ShareIntakeGate` makes both activities
discard the payload unread when they are not the effective layout, so such a
share is dropped, not played in the wrong branch. The launcher normally starts
the right class, so this only affects stale explicit launches. A switch does not touch players, `PlayerService` or the UPnP
client (they hang off the `Application`); the idle counter goes 1, 2, 1 and
never arms.

### As built: back, toolbar, settings round trip (new layout)

- **Back.** `BrowserActivity` registers one `OnBackPressedCallback` through
  `getOnBackPressedDispatcher().addCallback(this, callback)`; it does not override
  `onBackPressed()`. `BrowserBackNavigation.decide(onServerList, contentAtRoot)`:
  in a content folder below the root `ContentListFragment#onBackPressed()` pops
  one folder; at the root of a server `showServers()`; on the server list
  `Yaacc.exit()` then `finish()` (as `ServerListFragment#onBackPressed` does in
  classic). `ContentListFragment#isAtRoot()` is the null-safe read-only root
  test (`onBackPressed()` always returns `true`, so it cannot tell). The in-screen
  back arrow is also shown at a server's root when `showsBackAtContentRoot()` is
  true (content description `browser_back`) and calls `showServers()` there.
  Classic is unchanged: its `onBackPressed()` override still indexes
  `getFragments()` by tab position.
- **Toolbar** (`res/menu/activity_browser.xml`; ids shared with
  `activity_main.xml` for settings, log, about and exit so
  `BrowserActivitySupport.handleCommonMenuItem` serves both): checkable
  shutdown-timer item (`ifRoom`; while a timer runs it is checked, always shown
  and its title is the remaining time; it is the only entry of the timer),
  settings, log, about, and an always-visible exit item. The item is updated in place (the timer ticks every
  second), not through `invalidateOptionsMenu()`. The controller lives in the
  activity (attach in `onStart`, detach in `onStop`) so the item keeps updating
  on every level.
- **Timer tap** (`ShutdownTimerToggle.decide(running)`): running: `STOP`;
  otherwise `ASK_DURATION`, which opens the picker with
  `ShutdownTimerController.showPicker(fm, title, true)`. Confirming stores the
  chosen duration and, only if it is above 0, starts the timer; a duration of 0
  is stored but never starts a timer (starting with 0 ms would call
  `Yaacc.exit()` at once). A tap never starts a timer from the stored duration
  unseen. `setConfiguredMillis` still restarts a running timer with the new
  value. The former overflow entry and its string were removed in Group 11
  (see "Owner feedback (Group 11)" below).
- **Settings round trip.** `SettingsActivity.onPause()` sets a main-thread
  static `leftSettings`; `SettingsActivity.consumeLeftSettings()` reads and
  resets it. Both browsers call `LocalServerLifecycle.apply(this,
  SettingsActivity.consumeLeftSettings())` from `onResume` (after the layout
  check): with the local server preference on, the service is stopped first
  when the user visited settings, then started; with it off, stopped. Settings
  stays reachable from the new toolbar (overflow), so the layout can always be
  switched back there.

### As built: server list (`ServerListFragment` reuse)

There is no `ServerRootFragment`. `ServerListFragment` serves both layouts;
`onCreateView` inflates `fragment_server_root.xml` when its activity is a
`BrowserHost` with `providesShutdownTimer()`, else `fragment_server_list.xml`.
Both layouts use the same ids (`serverList`, `serverListRefreshButton`,
`serverListLocalServerEnabled`, `serverListLocalServerSettings`, status icons).

`fragment_server_root.xml` (top level of the new layout): a "Servers" header
(`serverRootHeader`, marked as an accessibility heading) with the refresh
button, the `serverList` (`BrowseDeviceAdapter`, shared with classic), and a
footer with the server switch, the gear (opens
`YaaccUpnpServerControlActivity`) and the provider/renderer/proxy status icons
(content descriptions from the `settings_local_server_*` strings). The footer
exists only at this level. The live-stream buttons (audio, video) are on the
yaacc row of `browse_device_item.xml`, as in classic. There are no timer rows;
the timer lives in the toolbar. Server names and icons come from the LAN:
`setText` and `IconDownloadTask` only. The switch writes only the
`settings_local_server_chkbx` preference; the service is started or stopped by
`LocalServerLifecycle.apply` on the next `onResume`, as in classic.

`ServerListFragment` and `ContentListFragment` register as `UpnpClientListener`
in `init` and remove themselves in `onDestroyView`;
`ContentListFragment` also cancels its load tasks there. For this
`UpnpClient#removeUpnpClientListener` was added and its listener list is a
`CopyOnWriteArrayList`. `PlayerListFragment` and `ReceiverListFragment`
(classic only) still never remove their listener.

### As built: shutdown timer and streaming permission

- **Timer.** The timer is in `Yaacc` (`startShutdownTimer`,
  `stopShutdownTimer`, `isShutdownTimerRunning`,
  `getShutdownTimerRemainingMillis`). Listeners (`ShutdownTimerListener#onTick`)
  are a `CopyOnWriteArrayList` with `addShutdownTimerListener` (`addIfAbsent`)
  and `removeShutdownTimerListener`; ticks and listener calls run on the main
  thread (`startShutdownTimer` re-posts itself there), and a failing listener is
  logged and skipped. The old single-slot listener field is gone, so the
  application no longer holds a destroyed fragment. `ShutdownTimerController`
  (plain class owned by the UI; `attach(Display)` in `onStart`, `detach()` in
  `onStop`) keeps the configured duration (a `long`, key
  `settings_shutdown_timer`), starts/stops the timer, shows the
  `MaterialTimePicker` (24 h, keyboard input, built from millis instead of from a
  formatted string) and publishes the state on attach. Because the state comes from
  `Yaacc`, the classic Server tab shows the running timer's state whenever the
  view is recreated (rotation, tab re-creation, layout switch); the old code
  restored only the remaining-time text and held the destroyed fragment as the
  single listener (a leak).
- **MediaProjection.** `StreamingPermissionHelper` serves every fragment that
  hosts `BrowseDeviceAdapter`: `createRequestIntent()` (null before Android 10)
  sets a static `requestPending`; the fragment starts it with
  `startActivityForResult(..., REQUEST_CODE_MEDIA_PROJECTION)` (2001) and passes
  `onActivityResult` to `onPermissionResult(requestCode, resultCode, data)`,
  which ignores a wrong request code or a result without a pending request.
  The permission `Intent` goes straight to `MediaProjectionHelper` and stays in
  its `volatile` static fields: never in a Bundle, preferences or another
  Intent, and its extras are not logged. `MediaProjectionHelper` keeps a
  `CopyOnWriteArrayList` of stop callbacks (`addStopCallback` /
  `removeStopCallback`), invoked on the main thread; the helper installs one
  process-wide state reset (preferences and `BrowseDeviceAdapter` flags set to
  `false`) and each screen adds its own refresh callback in `onStart` and
  removes exactly that callback in `onStop`. `BrowserActivity` does not
  override `onActivityResult`, so only the fragment that asked can feed a
  result. The `pending*` and streaming flags stay in `BrowseDeviceAdapter`; a
  result arriving after process death is ignored (it enabled nothing before
  either).

### As built: share handler

`ShareIntentHandler(Context appContext, UpnpClient)` replaces
`TabBrowserActivity#checkIfReceivedShareIntent`. `handle(Intent, Runnable
onPlayersStarted)` and `shutdown()` are main-thread. Both browsers call
`handle(getIntent(), ...)` in `onCreate` and `handle(intent, ...)` in
`onNewIntent`, and `shutdown()` in `onDestroy`.

This subsection is the Phase 2 state of the TEXT path. Phase 3 added a second overload,
`handle(Intent, Runnable, BrowserHost pickHost)`, which both browsers call through `ShareIntakeGate`, and the file path with
the pending share and the picker; see "Sharing local files" ("As built: from the share menu to a pending share", "As built: links").
In short: in the new layout (`pickHost.providesReceiverBar()`) a link is not played at once, it goes through the "Play on..."
picker and never falls back to `UpnpClient.LOCAL_UID`; classic keeps the behavior described below; a file is read from ClipData
item 0 (only, after the launch-token check). Where the text below differs, the Phase 3 section wins.

- Only `ACTION_SEND`. Text source: `EXTRA_TEXT`, else the text of the last
  `ClipData` item that has text and is at most 2048 chars. For a text share a clip
  item's URI is never used (before Phase 2 it was); a SHARED FILE is the exception
  added in Phase 3: its one `content:` URI is read from ClipData item 0 only.
- The text is removed from the intent (`removeExtra(EXTRA_TEXT)`,
  `setClipData(null)`) so a recreated activity does not replay it. After
  process death the system re-delivers the original intent and the link plays
  again (as in classic before).
- **Scheme policy.** `ShareUriPolicy.isAllowed(text)`: non-empty, at most 2048
  chars, and the part before the first `':'` equals `http` or `https`
  (ASCII, case-insensitive). Nothing is trimmed. Reason: only web links are relayed by the proxy. The check looks only at the scheme and length, so `http:` and
  `http://a\nfile:///x` pass (pinned by `ShareUriPolicyTest#onlyTheSchemeAndLengthAreChecked`;
  tightening is Backlog 1 item 8). A rejected text is only logged, with no
  payload. The text is never used to build an intent, PendingIntent or file path.
- **Waits and fallback** (classic and a missing host; in the new layout a link goes through the
  picker instead and does not wait or fall back): 0 s; +3 s when no receiver is ready; +3 s
  and `upnpClient.startService()` when the player service is not initialized.
  The worker then falls back to `UpnpClient.LOCAL_UID` when still no receiver
  is ready. The toasts ("start in N seconds", "no receiver found using local
  device") are hard-coded English.
- **Threads.** One `newSingleThreadScheduledExecutor()` per handler. A main
  `Handler` (application context only) posts the toasts and `play()`. Exceptions
  on the worker are caught and logged by class name only (the old code rethrew an
  `IOException` as `RuntimeException` and crashed the app).
- **Shutdown policy.** `shutdown()` sets a flag, clears the callback and calls
  the executor's graceful `shutdown()`: a share that is already waiting still
  plays (a rotation must not drop it), later `handle` calls are ignored. This is a
  deliberate deviation from "cancel pending work" (decisions.md 2026-10-24). The
  `onPlayersStarted` callback is held in a `volatile` field that `shutdown()`
  clears, so the queue does not pin a destroyed activity and a late share does
  not switch tabs on a dead screen. At most 3 shares may be queued per handler;
  more are ignored (logged without payload). A share waiting when the process
  dies is lost.
- No timeout on `MediaMetadataRetriever#setDataSource` inside
  `createPlayableItem`: a hostile host can hold the single worker thread (and the
  three slots) until its handler is gone. Follow-up.

### As built: idle timer

`BackgroundIdleTimer` (process-wide, application context only) replaces the
per-instance handler in `TabBrowserActivity`. Both browsers call
`browserStarted(this)` in `onStart` and `browserStopped(this)` in `onStop`.
`StartedBrowserCounter` (plain Java, JVM-tested) counts started browsers and
schedules the expiry (5 minutes, main-looper `Handler`) only when the count
drops to 0; a start cancels it; a stop without a start is ignored. When it
fires it re-reads the preferences and the player list and calls `Yaacc.exit()`
only if no player runs and the local server, renderer and proxy options are all
off. A counter is needed because on a layout switch (and on rotation) the old
browser's `onStop` runs after the new browser's `onResume`, so "arm in
`onStop`, cancel in `onResume`" would leave a timer running that quits the app
five minutes later.

### As built: receiver bar and classic footer

**New layout.** `ReceiverBarController(TextView bar, UpnpClient, Runnable
openSheet)` is owned by `BrowserActivity` only. `bind()` tints the compound
drawables, sets the click listener (tap or D-pad select opens the sheet), an
accessibility delegate (click action "Choose receivers") and adds itself as
`UpnpClientListener`; `unbind()` removes all of it. It refreshes through
`receiverDeviceAdded` / `receiverDeviceRemoved` (hopping to the main thread)
and once on every `bind()`; late events after `unbind()` are ignored. The
label is `ReceiverBarLabel.build` (Phase 3: the cleaned names of the receivers
that have a player, case-insensitive sort, joined with `"; "`, else
`receiver_bar_none_playing`; it no longer shows the selection), set with
`setText`; besides the receiver events the controller listens to the player list. `BrowserActivity` owns one bar below the container
(`activity_browser.xml`: separator, mini-player list, receiver bar in a
vertical `LinearLayout`, so a hidden mini-player cannot move an anchor) and
binds it in `onStart`/unbinds in `onStop` (and `onDestroy` as a backstop).

**Classic layout.** `ContentListFragment` shows the original static two-line
footer of `origin/feat/issue287`: `contentListCurrentProvider` (the provider's
friendly name, icon `ic_baseline_sensors_32`) and `contentListCurrentReceivers`
(the selected receivers' friendly names joined with `"; "`, in the client's
order, icon `ic_baseline_devices_32`), under `contentListBottomSeperator`. They
are not clickable and not focusable; the compound drawables are tinted with
`ThemeHelper.tintDrawable`; the texts are set when the provider is selected
and on receiver add/remove (`showCurrentProvider()` / `showCurrentReceivers()`).
The fragment does not use `ReceiverBarController` or `DeviceSheetFragment` any
more. Which footer shows is decided in `initFooter()` from the host:
`showsStaticFooter = host == null || !host.providesReceiverBar()`. When the host
provides the bar (new layout) the three footer views are set `GONE`, and nothing
visible anchors to them: in portrait the three sit in a never-`GONE`
`contentListBottomBar` container that the list anchors to; the landscape layout
(`layout-land/fragment_content_list.xml`) keeps the original chain (separator
above provider above receivers) that nothing outside the chain anchors to.

### As built: mini-player

`MiniPlayerBar` (controller) drives `mini_player_bar.xml`, a
`MaxHeightRecyclerView` (`R.id.miniPlayerBar`) included above the receiver bar in
`activity_browser.xml`. It shows **every** running player as one row
(`mini_player_row.xml`, a `MaterialCardView`: art, title, receiver name, previous /
play-pause / next / stop). The list is `GONE` while no player exists and is bound
by `BrowserActivity` between `onStart` and `onStop`. It replaced the single
"featured player" bar of Groups 8-10 (Group 11); `MiniPlayerPicker` and its tests
are gone.

- **Order.** `MiniPlayerRows.order(List<PlayerRef>)` reuses the device sheet's
  ordering (`DeviceSessionGrouper.sortedOldestFirst`): creation sequence
  (`UpnpClient#getPlayerCreationSequence`, assigned by `PlayerService` per added
  player) ascending, oldest first, equal sequences by player id; null entries are
  skipped, a duplicate player id keeps only its first entry (stable ids must not
  repeat), the input is not modified. `refresh()` hands it `PlayerRef` copies built
  from an `ArrayList` copy of `getCurrentPlayers()` and catches `RuntimeException`
  (a player mutating under the poll; retried on the next poll).
- **Height cap.** `MaxHeightRecyclerView` caps a `wrap_content` list at
  `mini_player_list_max_height` (180dp, three rows of 60dp including margins) and
  at most 40 % of the height offered to it, whichever is smaller; beyond that the
  list scrolls inside. An `EXACTLY` height is left alone.
- **Rows.** `MiniPlayerAdapter` has stable ids (the player ids) and diffs with
  `DiffUtil`, so a poll that changes nothing rebinds nothing and a changed title,
  play state or art key rebinds only its own row. Item animations are off.
- **Updates.** While bound: a `PlayerListChangeNotifier.Listener` registered
  through `UpnpClient#addPlayerListChangeListener` (main thread) plus a 2 s
  `Handler` poll (`POLL_INTERVAL_MS = 2000`) for title and play state, plus one
  extra refresh 500 ms after a command. `unbind()` removes the listener and
  both callbacks; nothing polls while the activity is stopped. The poll does
  in-memory reads only (title, play state, registry lookup), no network.
- **Text.** Title: the current item's title, else the player name
  (`MiniPlayerPresenter.title`). Receiver: the device name resolved with
  `UpnpClient#getDevice(deviceId)` (also answers `LOCAL_UID` with this device),
  cached per device id once resolved, until `unbind()`, else the player name
  (`receiverLabel`). Both are set with `setText` into one-line, ellipsized
  views (no `autoLink`, no HTML). `MiniPlayerPresenter.cleanText` (these strings
  come from LAN devices): whitespace runs, including line breaks, tabs, U+2028/2029,
  U+0085 and other space separators, become one space; control characters are
  dropped; so are LRM U+200E, RLM U+200F, ALM U+061C, the embeddings and
  overrides U+202A-202E, the isolates U+2066-2069, ZWSP U+200B, WJ U+2060 and BOM
  U+FEFF; the result is trimmed and cut at 200 chars (UTF-16 units) without
  splitting a surrogate pair. ZWJ U+200D and ZWNJ U+200C are **kept** (emoji
  sequences, Persian). **Not stripped:** tag characters U+E0000-E007F (part of
  some flag emoji), variation selectors (FE00-FE0F, E0100-E01EF), soft hyphen
  U+00AD, U+034F, U+180E, invisible operators U+2061-2064, deprecated format
  characters U+206A-206F, U+FFFC, and blank fillers (U+115F, U+1160, U+3164,
  U+FFA0, U+17B4, U+17B5), so a name made only of those can render empty.
  Strong RTL letters can still reorder neighbouring text.
- **Album art (per row).** The art key is `MiniPlayerRows.artKey(playerId,
  artUri)` = `playerId + ":" + artUri`, built from `Player#getAlbumArt()`
  (in-memory) and never from `getIcon()`. `Player#getIcon()` is read **once per
  player and key** (`MiniPlayerArtKeys.isNewKey`, in `MiniPlayerAdapter.bindArt`),
  never on the poll path: `AVTransportPlayer#getIcon()` queues a new download on
  every call while the 512x512 art is not cached, so calling it each poll would
  request a broken or hostile art URL every 2 s. On that first sight the row
  also starts `IconDownloadTask(art, key)`, which drops its result unless
  `art.getTag()` still equals the key. The adapter keeps that one `getIcon()`
  result per player (a `SparseArray`), so a recycled view holder bound again to a
  key already seen shows the stored icon (or the placeholder) and then reads only
  the in-memory `IconDownloadCacheHandler` (48x48): no request, so scrolling
  rows in and out of view or players flipping order floods nothing. A holder
  that was recycled clears its tag, so a late result for the old key drops
  itself. A player that disappears takes its key and icon with it
  (`retainOnly`); `unbind()` clears all of it. While uncached this costs two
  downloads of the same URL per player and key change (the `getIcon()` one and the
  task's).
- **Commands.** Previous, play/pause (`MiniPlayerPresenter.toggleAction(isPlaying)`),
  next and stop run on a single-thread executor created in `bind()` and shut
  down in `unbind()` (commands already queued still run). A command goes to the
  player of the row that was tapped; the play/pause decision follows that
  player's state at the moment of the tap. A tap on the row sends the player's
  own `getNotificationIntent()` through `ReceiverRowBinder.openPlayer` with an
  empty fill-in `Intent` (no extras, data or action).
- **Accessibility / D-pad.** The list itself is not focusable; each row is
  focusable and clickable before its buttons, so the D-pad reaches every row and
  then each of its buttons; each button has a content description (`mini_player_*` strings),
  each row has a click action label (`mini_player_open`), the art is marked
  decorative.

### As built: Layout preference

- `ListPreference` in `res/xml/preference.xml`, first item of the Appearance
  category: key `@string/settings_browser_layout_key`
  (`setting_strings.xml`, non-translatable), title `settings_browser_layout_title`
  ("Layout"), `summary="%s"`, default `classic`. Entries
  `settings_browser_layout_classic` ("Classic (tabs)") and
  `settings_browser_layout_new` ("New (single screen)") in 7 locales
  (`values/browser_layout.xml` and `strings.xml`); the stored values
  `classic` / `new` are the non-translatable `browser_layout_values` array.
  `BrowserLayoutValuesTest` pins the array and the default to
  `BrowserLayoutChoice.STORED_CLASSIC` / `STORED_NEW` / `DEFAULT_STORED_VALUE`.
- **One reader.** The key is read only in
  `BrowserLauncher.effectiveLayout(Context)`: `getString(key, null)` (default
  `null`: installs that never opened Settings have no stored value, because
  `Yaacc` calls `PreferenceManager.setDefaultValues(this, R.xml.preference,
  false)`), `RuntimeException` caught (a value of another type is classic), then
  `NEW_LAYOUT_AVAILABLE`, then `BrowserLayoutChoice.resolve`: exactly `"new"`
  gives `NEW`; null, empty, unknown or differently cased gives `CLASSIC`. The
  only writer is the `ListPreference`. The stored string is only compared, never a
  class name, action, extra, path or reflection target. Do not add a second
  reader.
- **Default** is classic on every device, Android TV included.
- **Switch.** There is no relaunch in Settings. The change applies when the user
  returns to a browser screen: the resume check ("Launcher, effective layout,
  relaunch") sees the mismatch and runs `relaunchIfOtherLayout`.
  `SettingsActivity` has no `android:exported` and no filter (not exported, and it
  reads no intent).

### As built: manifest

Re-checked 2026-10-24 with `./gradlew :yaacc:processDebugManifest --offline`
(`yaacc/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`).

- `LauncherActivity`: exported, `Theme.NoDisplay`, the three filters above (plus the Phase 3 media `SEND` filter, see "Sharing local files"). The
  only `MAIN`+`LAUNCHER` and `MAIN`+`LEANBACK_LAUNCHER` component (read the
  research check "exactly one exported launcher" as `MAIN`+`LAUNCHER`: four other
  activities declare `LAUNCHER` with `VIEW` only).
- `TabBrowserActivity`: `exported="true"`, **no** intent filters, no
  `launchMode`/`taskAffinity`. Kept exported so a home-screen shortcut that stored
  the old component still works; its resume check redirects when the setting says
  `new`.
- `BrowserActivity`: `exported="false"`, no filters, no `launchMode`/`taskAffinity`.
- `SettingsActivity`, `YaaccUpnpServerService`, `ImageViewerBroadcastReceiver`:
  no `exported` attribute and no filter, so not exported.
- Exported components in the merged manifest (12): `LauncherActivity`,
  `TabBrowserActivity`, `ImageViewerActivity`, `MusicPlayerActivity`,
  `AVTransportPlayerActivity`, `MultiContentPlayerActivity`, `AboutActivity`,
  `YaaccUpnpServerControlActivity`, `YaaccLogActivity`, `ServerAutostart`
  (`BOOT_COMPLETED`), and from libraries `androidx.media3.session.BluetoothValidationActivity`
  and `androidx.profileinstaller.ProfileInstallReceiver`. 21 `uses-permission`
  entries, as before Phase 2.
- `android:allowBackup="false"` is set on `<application>` (owner decision 2026-10-25;
  before that it was unset, default `true`).
- The source manifest was changed by Group 6 (`LauncherActivity` added, filters
  moved off `TabBrowserActivity`) and Group 7 (`BrowserActivity` added); Groups
  8, 9 and 11 did not touch it. `yaacc/build.gradle` has no Phase 2 change and no
  dependency was added.

### Known limitations / owner decisions

- **`allowBackup` (decided: off).** Auto Backup used to copy the default
  preferences file (layout, receiver selection, stored shared-link entries (retired in Phase 3), SAF cache entries)
  to the user's Google account. The owner chose `android:allowBackup="false"`
  (2026-10-25): users set the app up again on a new device. No backup or
  `dataExtractionRules` exclusion rules are needed then.
- **Stale notification PendingIntents.** The three internal PendingIntents keep
  the class chosen when the notification was posted. After a layout switch a tap
  starts the old class, which redirects at once, and may leave a second browser
  instance on the stack. A possible fix is `FLAG_ACTIVITY_REORDER_TO_FRONT` on the
  relaunch intent (fresh intent, no forwarded data) or rebuilding the notification
  on a layout change. To be observed in the manual checks.
- **Android TV.** D-pad reach of the new layout is not verified. The setting is
  visible on TV and the default stays classic; the new toolbar, receiver bar and
  mini-player are focusable by design. Recovery is the same Settings screen.
- **`ImageDownloader` / `IconDownloadTask`** accept any URL scheme the JVM
  handles, with no connect/read timeout and no byte cap, and run on the serial
  AsyncTask executor. The mini-player makes the exposure continuous (the art of
  every running player is fetched once per player and art URI on every browser screen). A scheme allowlist
  (http/https), timeouts and a size cap are follow-ups.
- **Names from the LAN.** The device-sheet and server-list name bindings and the
  classic footer lines show raw friendly names (trim only, as the original footer
  did): bidi controls and a name such as `Kitchen; Bedroom` are not neutralised.
  The mini-player's text and, since Phase 3, the receiver bar's names go through
  `MiniPlayerPresenter.cleanText`; the picker's rows use the cleaned friendly name.
- **Classic does not offer the device sheet** (owner decision, Group 11). The
  Receiver and Player tabs are the only way to control receivers and players in
  the classic layout, and playing with no receiver selected shows the old toast
  (`error_no_receiver_selected`) instead of a prompt. Whether the classic layout
  should ever get the sheet is closed with "no" (PRD open question 4). The one
  exception (Phase 3): a shared file opens the pick-mode sheet in classic too.
- **Share, Backlog 1** (still open, waiting for a discussion with the owner): which
  share (receiving into yaacc, or also the per-item share out of yaacc); the
  classic link auto-play and its blind 3 s waits; a notice when a share is dropped
  for its type; what the new layout shows when a share starts; a cool-down after a
  cancelled pick; re-opening the picker after a rotation; tightening
  `ShareUriPolicy` (require `://`, reject whitespace and control characters).
  Done in Phase 3: files and `content:` URIs, audio/video/image `SEND` filters,
  picking the receiver per action, localized feedback for failed shares (see
  "Sharing local files").
- **Other open items.** No timeout around `MediaMetadataRetriever#setDataSource`
  (share worker); `RendererStatusMonitor` polls on one thread (one slow renderer
  delays all status); `PlayerService.getPlayer()` returns a mutable view; remote
  devices claiming `LOCAL_UID` are not dropped at `UpnpClient` ingress;
  `PlayerListFragment` / `ReceiverListFragment` never remove their
  `UpnpClientListener`; `Yaacc.startShutdownTimer(0)` exits at once (the classic
  switch with no configured duration and `setConfiguredMillis(0)` on a running
  timer can still reach it; the new toolbar cannot);
  `AVTransportPlayer#getNotificationIntent` uses `FLAG_CANCEL_CURRENT`, so
  each call cancels the previous PendingIntent with the same identity; the share
  handler also runs on every recreation after process death.
- **Device checks still pending** (only reasoned, not run on a device; the
  Group 9 manual-check task is open with the owner): re-tapping the launcher icon
  with a finished root activity (no second browser instance; reasoned in (f));
  the splash screen on Android 12+ with the invisible launcher; sharing a link
  from another app through the share sheet (task placement, playback starts);
  switching Layout in both directions with players running; tapping an old
  notification after a switch; rotation in both layouts (fragment state, timer
  state, mini-player); process death and restore; the 5-minute idle exit after a
  rotation; the Android TV launcher entry and D-pad reach; the toolbar timer, the
  mini-player rows (several players, scrolling beyond about three rows, buttons,
  tap to open) and the receiver sheet at every level; the restored classic footer
  in portrait and landscape. Group 11 was built and unit-tested, not run on a device.

### Owner feedback (Group 11)

After the first on-device check the owner asked for three changes (decisions.md
2026-10-24 "Owner feedback after the first on-device check"; tasks.md Group 11).
They supersede earlier text of this section wherever the two differ.

1. **One shutdown-timer entry (new layout).** The toolbar icon is the only
   entry; the overflow entry "Set shutdown timer" and its string
   (`shutdown_timer_set`, 7 locales) are removed. A tap stops a running timer;
   otherwise it opens the duration picker and confirming a duration above 0
   starts the timer (`ShutdownTimerToggle.decide(running)`, picker with
   `startOnConfirm`). While running, the icon shows the remaining time. Classic's
   Server tab is unchanged.
2. **Classic keeps the original footer, the sheet is new-layout only.** The
   classic Content tab ends in the static provider line and receivers line (not
   clickable) as on `origin/feat/issue287`; the receiver sheet and the tappable
   receiver bar exist only in the new layout, and the "no receiver selected
   opens the sheet" prompt is not active in classic (removed altogether in Phase 3). PRD open question 4 is
   answered "no". See "As built: receiver bar and classic footer".
3. **Mini-player shows all running players.** One compact row per player,
   in the sheet's session order (oldest first), height capped at about three rows
   with an inner scroll, hidden when nothing runs. `MiniPlayerPicker` and its
   "featured session" notion are gone, replaced by `MiniPlayerRows`,
   `MiniPlayerArtKeys`, `MiniPlayerAdapter` and `MaxHeightRecyclerView`. See
   "As built: mini-player".

## Research notes (Group 5)

Everything below was written before any Phase 2 code existed ("recommended",
"proposed" and "must" describe the plan, and the pre-Group-6 line numbers no
longer match). The as-built text above wins wherever the two differ; each
subsection ends with a short "As built" pointer where it did. The cited
documentation and AOSP findings stay valid.

### (a) Host coupling: every `instanceof TabBrowserActivity` / `setCurrentTab` site

#### Finding: `getParent()` is never non-null here

Four sites test `requireActivity().getParent() instanceof TabBrowserActivity`.
`Activity.getParent()` "returns the parent Activity if this is a child
Activity of an ActivityGroup" and is deprecated since API 35 because
`ActivityGroup` is deprecated [docs:
`https://developer.android.com/reference/android/app/Activity#getParent()`].
`TabBrowserActivity` is a plain `AppCompatActivity` started with
`startActivity` (`TabBrowserActivity.java:85`, `AndroidManifest.xml:46-66`),
and the fragments live in the activity's own `FragmentManager`, so
`requireActivity()` is the `TabBrowserActivity` itself and its `getParent()`
is `null` [reasoned]. Those four branches never run. The fifth live site
(`ServerListClickListener`) works because it does not use `getParent()`.

#### Site inventory

State before Phase 2. As built: sites 1-4 were removed, site 5 and 8 call
`BrowserHost`, site 6 became an `instanceof BrowserHost` guard, 7 and 9 are
classic-internal.

| # | Site | Trigger | Wants from host | Live then? |
|---|---|---|---|---|
| 1 | `PlayerListFragment.java:105-106` | `onBackPressed()` | go to Receiver tab (`BrowserTabs.RECEIVER`) | **dead** (`getParent()` null); handler still returns `true`, so back is swallowed |
| 2 | `ReceiverListFragment.java:157-158` | `onBackPressed()` | go to Content tab | **dead**, back swallowed |
| 3 | `ContentListFragment.java:551-552` | `onBackPressed()` at folder root (`ITEM_ROOT_OBJECT_ID`, `:542-553`) | go to Server tab | **dead**, back swallowed. Folder pop at `:555-570` is live. |
| 4 | `ServerListFragment.java:162-163` | `deviceAdded(device)` when `device` equals `upnpClient.getProviderDevice()` (`:155`) | auto-jump to Content tab | **dead** |
| 5 | `ServerListClickListener.java:50-51` | click on a server row, after `upnpClient.setProviderDevice(...)` (`:49`) | go to Content tab | **live**: `parent` is the `Context` handed to `BrowseDeviceAdapter` (`BrowseDeviceAdapter.java:130`), which is `getActivity()` (`ServerListFragment.java:131`) |
| 6 | `ServerListFragment.java:238` | `v.getContext() instanceof TabBrowserActivity` guard around `setLocalServerState(view)` after the server switch is clicked | not navigation. A "are we in the classic activity" guard. | live in classic, but the guard is pointless: `setLocalServerState` only needs the view |
| 7 | `TabBrowserActivity.java:226, 228` | `onCreate`: restore saved tab; otherwise go to `CONTENT` if a provider device is selected | activity-internal | live |
| 8 | `TabBrowserActivity.java:315` | share handler, after `player.play()` | go to Player tab | live |
| 9 | `TabBrowserActivity.java:334-341` | `setCurrentTab(BrowserTabs)`: `runOnUiThread` then `tabLayout.getTabAt(ordinal).select()` | the implementation | live |

`SettingsActivity.java:42` (`TabBrowserActivity.leftSettings = true`) is a
static coupling, not navigation. See (c).

Non-fragment references that only build an explicit `Intent` to the class:
`Yaacc.java:172` (`createYaaccGroupNotification`, content intent, no extras),
`PlayerService.java:106` (`createSessionActivity`, used for the media session)
and `PlayerService.java:199` (`updateServiceNotification`). All three use
`FLAG_IMMUTABLE`, the first and third without `FLAG_UPDATE_CURRENT`.
`UpnpClient.java:97,1661` only uses `TabBrowserActivity.class.getName()` as a
log tag.

Tab order is `SERVER(0)`, `CONTENT(1)`, `RECEIVER(2)`, `PLAYER(3)`
(`BrowserTabs.java:21-25`); `TabBrowserFragmentStateAdapter` creates one
fragment instance per tab (`:28-97`). `TabBrowserActivity.onBackPressed`
indexes `getSupportFragmentManager().getFragments()` by the selected tab
position (`:420-421`), so it relies on fragment order equalling tab order.

#### `BrowserHost`

Built with the four methods this analysis found (`showServers()` for site 3's
intent, `showContent()` for sites 2 and 5, `showReceivers()` for site 1,
`showPlayers()` for site 8) plus three capability defaults; the interface and
each activity's implementation are under "As built: `BrowserHost`". Fragments
find the host with `getActivity() instanceof BrowserHost`,
`ServerListClickListener` with `parent instanceof BrowserHost`; both tolerate
"no host".

#### Decision for Group 6: the four dead sites

The Group 6 Accept says "classic behavior is unchanged (same tab
switches)". Wiring sites 1-4 to `BrowserHost` would make them **live** and
change classic: back on the Player tab would go to Receiver, Receiver to
Content, Content root to Server (today all three are swallowed no-ops), and
site 4 would yank the user to the Content tab whenever the selected provider
announces itself again. Decision, as built in Group 6:

- Sites 5 and 8 go through `BrowserHost` (they are live).
- Sites 1-3: the dead `getParent()` branch is removed and `return true` kept;
  they are not wired, so classic stays unchanged. Making classic back work
  properly is a behavior change for the owner to decide separately.
- Site 4: the dead branch is deleted, not wired; the new layout starts at the
  server list and does not auto-jump.
- The new layout needs real up-navigation anyway; it gets it from its own back
  handling (see (b)), not from these dead handlers.

### (b) Back handling

#### Flow in the classic layout [code]

1. `TabBrowserActivity.onBackPressed()` (`:417-427`) overrides the
   deprecated `Activity` method. It takes the fragment at index
   `tabLayout.getSelectedTabPosition()` of `getFragments()`; if it implements
   `OnBackPressedListener` (`OnBackPressedListener.java:25-28`: "return true
   if your processing has priority") and returns `true`, that is the end.
   Otherwise `super.onBackPressed()` (finish).
2. Per tab:
   - **Server**: `ServerListFragment.onBackPressed()` (`:113-118`) calls
     `Yaacc.exit()` and `finish()` and returns `true`. `exit()` shuts down the
     client, stops both services, cancels notifications, finishes all app
     tasks and ends with `Runtime.getRuntime().exit(0)` (`Yaacc.java:125-149`).
     So back on the Server tab quits the whole process.
   - **Content**: `ContentListFragment.onBackPressed()` (`:535-575`) cancels
     running loads. Below the root it pops one `Navigator` position and
     reloads that folder (`:555-570`); at the root it only refreshes the
     header and tries the dead site 3. Always returns `true`. The in-screen
     back arrow (`contentListBackButton`, `:112-117`) calls the same method;
     it is hidden at the root (`updateFolderNavigationUi`, `:470-490`).
     Possible NPE: `navigator` is dereferenced at `:537` without a null check
     while `updateFolderNavigationUi` does check it.
   - **Receiver**, **Player**: dead site, return `true`: back does nothing.
3. Net effect today: only the Server tab and folder levels respond to back.
   On the Content root, Receiver and Player tabs back is swallowed, so the
   spec's phrase "exactly as the classic back flow does" (Group 7) describes
   the code's intent, not its behavior.

#### Back handling in the new activity: API checks

- **API is available in appcompat 1.8.0 / activity 1.8.0 [javap]**:
  `ComponentActivity.getOnBackPressedDispatcher()` is `public final`;
  `OnBackPressedDispatcher.addCallback(LifecycleOwner, OnBackPressedCallback)`,
  `addCallback(OnBackPressedCallback)`, `hasEnabledCallbacks()` exist;
  `OnBackPressedCallback(boolean)`, `setEnabled`, `isEnabled`, `remove`,
  `handleOnBackPressed()` (abstract) exist. API reference:
  `https://developer.android.com/reference/androidx/activity/OnBackPressedDispatcher`
  (that page documents the latest release; the signatures used here are
  "Added in 1.0.0" and were checked against the 1.8.0 jar).
- `getOnBackPressedDispatcher().addCallback(this, callback)` is called once in
  `onCreate`. The docs say a callback registered with a `LifecycleOwner`
  follows its lifecycle and is removed on `DESTROYED`, so nothing leaks.
- Do not override `onBackPressed()`. The docs state that from API 33 back
  handling moves to an ahead-of-time model and `Activity.onBackPressed()`
  "should not be used" [docs:
  `https://developer.android.com/reference/android/app/Activity#onBackPressed()`].
  The classic override still works for this app because `targetSdkVersion` is
  33 and the manifest has no `android:enableOnBackInvokedCallback`
  [code: `build.gradle`, `AndroidManifest.xml`]; the new activity does not
  depend on that. `enableOnBackInvokedCallback` is not set by Phase 2 (not
  researched, and it would change classic).
- D-pad / TV: system Back arrives as the same back event; nothing special.

As built: see "As built: back, toolbar, settings round trip (new layout)"
(`BrowserBackNavigation`, `ContentListFragment#isAtRoot()`, the back arrow at a
server's root through `showsBackAtContentRoot()`). The new activity does not
index `getFragments()` by tab position the way classic does.

### (c) The `leftSettings` round trip

#### What happened before Phase 2 [code]

- `TabBrowserActivity.leftSettings` is `public static boolean`, marked
  `//FIXME dirty` (`TabBrowserActivity.java:88-89`).
- `SettingsActivity.onPause()` sets it to `true` (`SettingsActivity.java:40-43`).
  `SettingsActivity` is opened by plain `startActivity` from four places:
  `TabBrowserActivity.java:453`, `ImageViewerActivity.java:455`,
  `AVTransportPlayerActivity.java:403`, `YaaccUpnpServerControlActivity.java:415`.
  Never for a result.
- `TabBrowserActivity.onResume()` (`:355-376`): if the stored "local server"
  preference is on, it **stops and restarts** `YaaccUpnpServerService` when
  `leftSettings` is true (so changed server settings take effect), otherwise
  just starts it; if the preference is off it stops the service. Then resets
  the flag. The flag may be set by a settings visit from another activity and
  is consumed when the browser next resumes.
- The same `onResume` also re-reads `settings_swipe_chkbx` for the
  `ViewPager2` (classic only).

#### What the new activity needed

Built as `LocalServerLifecycle.apply(Context, boolean)`,
`SettingsActivity.consumeLeftSettings()`, `BrowserActivitySupport` (permission
request, battery prompt, menu actions) and `BackgroundIdleTimer`; see the
as-built subsections. The reason for the process-wide, counted idle timer:

- **Latent bug the relaunch makes more visible** (before Phase 2): the idle
  timer was a per-instance `Handler` + `Runnable`. `onStop()` posted the
  5-minute exit; `onResume()` cancelled only **this instance's** callbacks.
  After any recreation (rotation) or a relaunch, the old instance's runnable was
  still queued and, if there were no players and no local server/renderer/proxy
  enabled, called `Yaacc.exit()` five minutes later even though the user was
  active in the new instance [reasoned]. A plain "cancel on resume" is not
  enough, because the old browser's `onStop` can arm the timer after the new
  browser's `onResume` has cancelled it, so the timer counts started browsers
  and arms only when the count drops to 0 (decisions.md 2026-10-24, review W4
  and security review cycle 1).

#### Relaunch when the Layout preference changes: why check on resume

Each browser activity compares the **effective** layout with its own in
`onResume()` and, on a mismatch, starts the other with a fresh intent and
finishes, behind a relaunch guard (as built: `BrowserLauncher.relaunchIfOtherLayout`,
see "As built: launcher, effective layout, relaunch"). The effective layout
is `CLASSIC` while `NEW_LAYOUT_AVAILABLE` is `false`, and
`BrowserLauncher.targetClass()` and the check use the same function.

Without the effective-layout comparison and the guard, a stored `"new"`
while the new layout is unavailable (backup/restore, adb, downgrade) would
bounce between the two activities forever (security review cycle 1,
Warning 2). A pending share payload is never forwarded to the other browser
(see "Resume check" under "As built: launcher, effective layout, relaunch").

Why this one:

- Settings is always opened with `startActivity` on top of a browser (or a
  player screen) and the browser then stops and resumes, so `onResume` is a
  guaranteed point after the user is done. No result code, no listener.
- Works for all four Settings entry points: if Settings was opened from a
  player screen, the mismatch is caught when the user next reaches the
  browser.
- Also corrects **stale launches**: `Yaacc.java:172` and `PlayerService.java`
  `:106, :199` build explicit-class `PendingIntent`s when the notification is
  posted; after a layout change, tapping an old notification starts the
  "wrong" class, which immediately redirects itself. Group 6's launch helper
  alone cannot fix already-posted notifications.
- Process death: the preference is read again on recreation; nothing to
  persist.
- `getString(key, null)` returns `null` for existing installs: the new
  `ListPreference`'s default is **not** written for them, because
  `Yaacc.onCreate` calls `PreferenceManager.setDefaultValues(this,
  R.xml.preference, false)` and with `readAgain=false` that "sets the default
  values only if this method has never been called in the past" [docs:
  `https://developer.android.com/reference/androidx/preference/PreferenceManager#setDefaultValues(android.content.Context,int,boolean)`].
  `BrowserLayoutChoice.resolve(null)` -> `CLASSIC` (the Group 5 test) handles
  it. Never use a default other than `null` when reading.
- Players keep running: they live in `PlayerService`/`UpnpClient`, which hang
  off the `Application` (`Yaacc.java:62-66`, `UpnpClient(Context)` gets the
  application), not off an activity.

Rejected:

- `OnSharedPreferenceChangeListener` in the browser: it fires while Settings
  is on top and the browser is stopped; the new activity would start over
  Settings mid-edit. Listeners must also be strongly referenced (the registry
  holds them weakly) and unregistered.
- `ActivityResult` from Settings: Settings is not started for a result in any
  of its four entry points, and its default result is `RESULT_CANCELED` on
  back.
- `recreate()`: cannot switch classes.

Cost: one frame of the old UI after returning from Settings. Acceptable;
`overridePendingTransition(0, 0)` hides the cross-fade.

### (d) `ServerListFragment`: shutdown timer, MediaProjection, streaming flags

#### Shutdown timer before Phase 2 [code]

State and wiring:

- Timer lives in `Yaacc`: `CountDownTimer shutdownTimer`, one
  `ShutdownTimerListener shutdownTimerListener` (`Yaacc.java:65-66`);
  `startShutdownTimer(long)` stops any running timer, ticks every second and
  calls the listener, and calls `exit()` on finish (`:194-213`);
  `stopShutdownTimer()` (`:215`); getter/setter (`:221-227`).
  `ShutdownTimerListener` has one method `onTick(long)`
  (`util/ShutdownTimerListener.java:21`).
- `Yaacc` cannot tell whether a timer is running, and does not keep the
  remaining time. The UI therefore keeps its own copy.
- `ServerListFragment` views (`fragment_server_list.xml:33-80`):
  `serverListShutdownTimerLabel`, `serverListShutdownTimerEnabled` (switch),
  `serverListSetShutdownTimer` (button), `serverListShutdownTimerRemaining`
  (text). The duration is persisted as a `long` under
  `R.string.settings_shutdown_timer` (`setting_strings.xml:157`, default `0`).
  `FormatHelper.parseMillisToTimeStringTo(long)` renders `HH:MM:SS`
  (`FormatHelper.java:9-16`).
- Logic: switch on -> `setShutdownTimerListener(this)` +
  `startShutdownTimer(stored)` (`ServerListFragment.java:259-268`); switch off
  -> reset text to the stored duration + `stopShutdownTimer()`; set button ->
  parse `HH:MM` back out of the formatted string
  (`:272-299`, fragile: `Integer.parseInt(durationString.split(":")[0])`),
  show a `MaterialTimePicker` (`CLOCK_24H`, keyboard input), store millis,
  restart the timer if the switch is on. Remaining text is copied through
  `onSaveInstanceState` (`:207-211`, reads `getView()` unguarded) and only
  re-attaches the listener when restored from saved state (`:250-253`).
- Problems to avoid copying: the `Application` holds the fragment as listener
  and nothing ever clears it (fragment + activity leak after rotation or
  destroy); a recreated fragment does not know a timer is already running
  (the switch is not restored from `Yaacc`).

As built: `ShutdownTimerController` with `attach` / `detach`, plus
`Yaacc#isShutdownTimerRunning()` and `Yaacc#getShutdownTimerRemainingMillis()`
and a multi-listener list in `Yaacc` (see "As built: shutdown timer and
streaming permission"). Classic's `ServerListFragment` keeps its four views and
delegates to the controller; the new layout shows the timer in the toolbar and
the server list leaves the timer rows out when
`BrowserHost.providesShutdownTimer()` is true. `MaterialTimePicker` was already
used here and `material:1.14.0` is unchanged.

#### MediaProjection permission flow before Phase 2 [code]

- Fragment `onCreate` registers a **static** callback
  `MediaProjectionHelper.setStopCallback(...)` capturing the fragment
  (`ServerListFragment.java:87-110`; `MediaProjectionHelper.java:56-59`). It
  resets both streaming preferences, both static flags and refreshes the
  adapter. Nothing unregisters it, and a second fragment instance silently
  replaces the first.
- Request: `BrowseDeviceAdapter` gets a `StreamPermissionCallback` from the
  fragment (`ServerListFragment.java:133-138`), invoked by the audio/video
  stream buttons when `MediaProjectionHelper.hasPermission()` is false
  (`BrowseDeviceAdapter.java:246-256, 281-291`), after it sets the static
  `pendingAudioRequest`/`pendingVideoRequest` flags. The fragment answers with
  `startActivityForResult(MediaProjectionHelper.createPermissionIntent(...),
  REQUEST_CODE_MEDIA_PROJECTION)` (request code 2001,
  `MediaProjectionHelper.java:41`).
- Result: `ServerListFragment.onActivityResult` (`:362-415`) calls
  `MediaProjectionHelper.handlePermissionResult`, then on grant writes the
  matching preference(s) and sets the matching static flag(s) according to
  which button asked, clears the pending flags and `notifyDataSetChanged()`s;
  on denial clears both preferences, flags and refreshes.
- Streaming state is process-wide: `isAudioStreaming`/`isVideoStreaming` are
  `static` in `BrowseDeviceAdapter` (`:61-62`), re-read from preferences when
  the yaacc row is bound (`:196-197`); `Yaacc.onCreate` resets both
  preferences to `false` at every process start (`Yaacc.java:91-96`). So the
  state survives a classic <-> new switch inside one process.
- Fragment 1.5.4 and activity 1.8.0 both offer `registerForActivityResult`
  [javap: `ComponentActivity.registerForActivityResult`]; the old
  `Fragment.startActivityForResult`/`onActivityResult` still work.

What had to move: **nothing has to move to the toolbar or the activity** for
streaming. The buttons stay on the yaacc row, and the row only exists in the
fragment that hosts `BrowseDeviceAdapter`; the permission result is delivered
to the fragment that started it. As built, every such fragment uses
`StreamingPermissionHelper` (stop callbacks registered in `onStart`, removed in
`onStop`; the static `pending*` flags stay in `BrowseDeviceAdapter`).

### (e) Share-intent handler: `checkIfReceivedShareIntent`

`TabBrowserActivity.java:257-331`, called from `onCreate` with `null`
(`:232`, after the `upnpClient == null` early return at `:220-223`) and from
`onNewIntent` (`:251-255`).

Behavior before Phase 2 [code]:

1. Only `Intent.ACTION_SEND`. URI source order: `EXTRA_TEXT` string
   (`Uri.parse`), else the **last** `ClipData` item that has text or a URI
   (`:262-276`). The manifest filter is `text/plain` only (g).
2. Clears what it read so a recreated activity does not replay it:
   `intent.removeExtra(EXTRA_TEXT)`, `intent.setClipData(null)` (`:277-283`).
   It therefore mutates the `Intent` object it is given.
3. If there is a URI: delay 0 s; +3 s if
   `upnpClient.getReceiverDevicesReadyCount() == 0` (`:286-289`); +3 s and
   `upnpClient.startService()` if `!upnpClient.isPlayerServiceInitialized()`
   (`:290-294`). Creates a new `Executors.newSingleThreadScheduledExecutor()`
   per call, never shut down (thread leak) (`:297`). Toast "start in N
   seconds" when delayed (`:324`).
4. Worker: if still no ready receiver, toast "no receiver found using local
   device" (on the UI thread via `runOnUiThread`) and
   `upnpClient.setReceiverDeviceIds(Set.of(UpnpClient.LOCAL_UID))`
   (`:302-306`); `upnpClient.createPlayableItem(uri)` (`throws IOException`,
   rethrown as `RuntimeException` on the worker thread: crashes the app,
   `:317-319`); posts to the main looper: `initializePlayersWithPlayableItems`,
   `player.play()` for each, then `setCurrentTab(PLAYER)` (`:309-316`).

Dependencies: `UpnpClient` (`getReceiverDevicesReadyCount :1056`,
`isPlayerServiceInitialized :1673`, `startService :178`,
`setReceiverDeviceIds :1025`, `createPlayableItem(Uri) :1599`,
`initializePlayersWithPlayableItems :1247`), `Player.play()`, an application
`Context` for toasts, a main-thread `Handler`, and one UI callback
(`setCurrentTab(PLAYER)`). Nothing needs the `Activity` itself except
`runOnUiThread` for one toast, which a main-looper `Handler` replaces (and
stops capturing the activity for up to 6 s).

As built: `ShareIntentHandler` (see "As built: share handler"): both 3 s waits
and the fallback are unchanged, the executor is shut down, the callback is
`showPlayers()` in classic and `null` in the new layout, and the untrusted text
is only checked by `ShareUriPolicy`, `Uri.parse`d and passed to
`createPlayableItem`, never executed or used to build an `Intent`.

- `onNewIntent` is effectively unreachable in classic: `TabBrowserActivity`
  has no `launchMode`, so a second `ACTION_SEND` starts a **new instance**
  rather than delivering to the running one (for a standard-mode activity
  whose component equals the task root, `ActivityStarter` takes the
  "same component" branch but only calls `deliverNewIntent` for
  `singleTop`/`FLAG_ACTIVITY_SINGLE_TOP`, otherwise it adds an instance when
  the intent filter differs from the task's base intent, [aosp:
  `ActivityStarter.java` `complyActivityFlags`, ~lines 2466-2492]
  [reasoned]). Both browsers still pass `onNewIntent` to the handler, for an explicit
  `SINGLE_TOP` start.

### (f) A `Theme.NoDisplay` trampoline that forwards `ACTION_SEND`

#### Verified from documentation

- **`Theme.NoDisplay` contract**: "Default theme for activities that don't
  actually display a UI; that is, they finish themselves before being
  resumed." [docs: `https://developer.android.com/reference/android/R.style#Theme_NoDisplay`].
- **Enforcement** [aosp: `Activity.java:8753-8761`, `performResume`]: an
  invisible activity that has not called `finish()` before `onResume()`
  completes logs a warning, and for `targetSdkVersion` > 22 throws
  `IllegalStateException("... did not call finish() prior to onResume()
  completing")`. This app targets 33. So `finish()` must be called in
  `onCreate` on every path, including the one that cannot find the target
  (wrap `startActivity` in try/catch, `finish()` in `finally`).
- **`LEANBACK_LAUNCHER`**: `Intent.CATEGORY_LEANBACK_LAUNCHER` is "an activity
  optimized for Leanback mode, and that should be displayed in the Leanback
  launcher" (API 21) [docs:
  `https://developer.android.com/reference/android/content/Intent#CATEGORY_LEANBACK_LAUNCHER`].
  It is a plain category on a `MAIN` filter, so it works on a `NoDisplay`
  activity exactly like `LAUNCHER`. The launcher shows the **launcher
  component's** label/icon/banner: carry `android:label` over
  (`@string/title_activity_main`, `AndroidManifest.xml:49`); there is no
  `android:icon`/`android:banner` on the current entry, so both fall back to
  the `<application>` values (`:35-43`), which stays true for the trampoline.
  The manifest has no `<uses-feature android:name="android.software.leanback">`
  [code]; out of scope.
- **`android:exported`**: "If `true`, the activity is accessible to any app,
  and is launchable by its exact class name. If `false`, the activity can be
  launched only by components of the same application, applications with the
  same user ID, or privileged system components." With intent filters,
  `true` is required for other apps to start it [docs:
  `https://developer.android.com/guide/topics/manifest/activity-element#exported`].
  Explicit intents ignore filters, so `TabBrowserActivity`/`BrowserActivity`
  can be `exported="false"` once the trampoline holds the filters: the
  trampoline has the same UID.
- **`launchMode` / `taskAffinity`**: default `standard`; "By default, all
  activities in an application have the same affinity"; the affinity decides
  the task for `FLAG_ACTIVITY_NEW_TASK` launches [docs:
  `https://developer.android.com/guide/topics/manifest/activity-element#lmode`,
  `#aff`]. The current entry sets neither (`AndroidManifest.xml:46-66`).
  Recommendation: set **neither** on the trampoline and on the browsers, so the
  trampoline, the target and the notification launches all share one task.
- **URI grant semantics** [docs]:
  `FLAG_GRANT_READ_URI_PERMISSION`: "the recipient of this Intent will be
  granted permission to perform read operations on the URI in the Intent's data
  and any URIs specified in its ClipData ... only the grant flags of the
  top-level Intent are used"
  (`https://developer.android.com/reference/android/content/Intent#FLAG_GRANT_READ_URI_PERMISSION`).
  `setClipData`: the grant flags "will operate on any URI items included in the
  clip data" (`.../Intent#setClipData(android.content.ClipData)`).
  `ACTION_SEND`: if the sender sets no `ClipData`, `EXTRA_STREAM`/`EXTRA_TEXT`
  "will be copied there for you when calling `Context.startActivity(Intent)`"
  (`.../Intent#ACTION_SEND`). Lifetime: "The permissions you grant are
  temporary and expire automatically when the receiving app's task stack is
  finished" (`https://developer.android.com/training/secure-file-sharing/share-file`).
  Persistable grants need `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`, which a
  share does not carry.

#### Reasoned from AOSP (not stated in the public docs)

The docs do not say whether a receiver can pass a grant on by forwarding the
same intent. From `android-14.0.0_r1`:

1. The grant flags stay on the intent the receiver sees; nothing in
   `ActivityStarter` or `ActivityRecord` strips `FLAG_GRANT_*` (grep of both
   files finds no `FLAG_GRANT` use). The receiver's `getIntent().getFlags()`
   therefore still includes `FLAG_GRANT_READ_URI_PERMISSION`.
2. On `startActivity`, `ActivityStarter` calls
   `UriGrantsManagerService.checkGrantUriPermissionFromIntent(intent,
   callingUid, targetPkg, ...)` (`ActivityStarter.java:605`) and later
   `grantUriPermissionUncheckedFromIntent(intentGrants,
   mStartActivity.getUriPermissionsLocked())` (`:1750`). The check includes
   "Third... does the caller itself have permission to access this uri?":
   if the caller lacks the permission by itself it must hold a strong enough
   URI grant (`checkUriPermissionLocked(grantUri, callingUid, modeFlags)`,
   `UriGrantsManagerService.java:~1240-1260`). The trampoline holds exactly
   such a grant, because the system granted it when the share was delivered
   to it. So **a re-grant to the real activity succeeds**, provided the
   original sender's provider allows granting (`grantUriPermissions`), which
   any correct share provider does.
3. The grant is owned by the receiving `ActivityRecord`'s `UriPermissionOwner`
   and removed in `ActivityRecord.removeFromHistory` ->
   `removeUriPermissionsLocked` (`ActivityRecord.java:3967, 5164`). The
   target activity gets its own owner entry from step 2, so finishing the
   trampoline does not revoke the target's access. The trampoline must call
   `startActivity` **before** it is destroyed, i.e. in `onCreate`, which the
   `NoDisplay` rule requires anyway.
4. Same UID for source and target (both are this app) does not bypass the
   check: the "target already holds permission" shortcut only looks at the
   provider's manifest permissions, not at URI grants
   (`checkHoldingPermissionsUnlocked`, `UriGrantsManagerService.java:~1130`).
   Step 2 still applies.
5. `new Intent(Intent o)` copies flags and `ClipData` (`Intent.java:7534-7572`,
   `mFlags = o.mFlags`, `mClipData = new ClipData(o.mClipData)`), so a copy
   carries the grant exactly like the original. (Analysis only: this is why
   the recommended body below does **not** copy the incoming intent.)

The launcher is exported and an explicit intent ignores its filters, so any app
can send it any intent. Security review cycle 1 (Group 5) therefore required a
**fresh, allowlisted forward**, not a copy of the incoming intent (a copy would
turn a third-party intent into a same-app intent for the non-exported
`BrowserActivity` and keep grant bits, `ClipData` URIs, nested `EXTRA_INTENT`,
selector and flags). As built: see "As built: launcher, effective layout,
relaunch" (`LauncherActivity` plus `LauncherForwardPolicy`).

Rules: no flags, no `data`, no `ClipData` URIs, no `EXTRA_STREAM` /
`EXTRA_INTENT` / other extras, no selector, no categories (which drops every
URI grant); a `ClipData` item's *text* (never its URI) may be used as the
`EXTRA_TEXT` fallback; the browsers never trust `getIntent()` or the
referrer.

- `extends android.app.Activity`, not `AppCompatActivity`: `Theme.NoDisplay`
  is a framework theme, not an AppCompat theme, and the trampoline never
  inflates anything.
- Add no `FLAG_GRANT_*` and copy no flags from the incoming intent; do not
  call `setSelector`/`setPackage`.
- Because the forward is explicit, `BrowserActivity` and `TabBrowserActivity`
  need no `<intent-filter>`. `BrowserActivity` is `exported="false"`;
  `TabBrowserActivity` stays exported without filters (see (g)).
- Placement: trampoline and target share the default affinity, so the target
  lands in the same task as the trampoline, which is where a
  `TabBrowserActivity` launched from the share sheet lands today.
- Re-tapping the launcher icon with the app running: the task's root intent
  component is the trampoline (it stays the task's `realActivity` after it
  finishes), so a launcher start of the trampoline matches the task and brings
  it forward rather than adding a second instance
  (`ActivityStarter.complyActivityFlags`, "same component as the task root"
  branch, ~lines 2466-2506) [reasoned; confirm on device].
- Internal launches that name the browser class directly (notifications) do
  not match the task root once the trampoline is the root, and, like today,
  add an instance on top because their intent differs from the task's base
  intent (no `MAIN`/`LAUNCHER`). No regression expected, but I did not run it
  [reasoned]. Pointing those `PendingIntent`s at `LauncherActivity` instead
  would give the same result and always follow the setting; the
  relaunch-on-resume check in (c) covers both choices.

Not researched: Android 12+ notification-trampoline restrictions. They concern
broadcast receivers/services started from notifications, not an activity
starting an activity, so they should not apply here.

### (g) Manifest filters on `TabBrowserActivity` and component references

Current entry, `AndroidManifest.xml:46-66`: `android:name=".browser.TabBrowserActivity"`,
`exported="true"`, `label=@string/title_activity_main`, no `launchMode`, no
`taskAffinity`, no `theme` (inherits `AppTheme`, `:42`). Three filters:

| # | Filter | Moves to `LauncherActivity`? |
|---|---|---|
| 1 | actions `VIEW` + `MAIN`; categories `DEFAULT` + `LAUNCHER` (`:50-56`) | **Yes**, verbatim (this is the real launcher entry because of `MAIN` + `LAUNCHER`). The `VIEW` + `DEFAULT` half has no `<data>`, so it only matches data-less `VIEW` intents; moving it unchanged is behavior-preserving, dropping it is a decision for the owner. Flag it for the security review as exported surface. |
| 2 | `MAIN` + `LEANBACK_LAUNCHER` (`:57-60`) | **Yes** (Android TV entry). |
| 3 | `SEND` + `DEFAULT`, `mimeType=text/plain` (`:61-65`) | **Yes** (share target). |

`LauncherActivity` also takes `android:label="@string/title_activity_main"`,
`android:theme="@android:style/Theme.NoDisplay"`, `android:exported="true"`,
and is the **only** `MAIN`/`LAUNCHER` and `LEANBACK_LAUNCHER` component.
`TabBrowserActivity` and `BrowserActivity` then carry no filters;
`BrowserActivity` is `exported="false"` and `TabBrowserActivity` stays exported
(below).

Nothing outside the app needs the classic class by name:

- `grep -rn TabBrowserActivity` over `yaacc/src/main`, `.github`, `fastlane`,
  `README`/docs: only the manifest, `Yaacc.java:44,172`,
  `PlayerService.java:49,106,199`, `UpnpClient.java:97,1661` (log tag),
  `SettingsActivity.java:24,42` (static flag), the fragments, two layout
  `tools:context` attributes (`activity_tab_browse.xml:24` x2) and a CHANGELOG
  line. No shortcuts XML, no app widgets, no `res/xml` other than
  `file_paths.xml`, `locales_config.xml`, `preference.xml`; no
  `ComponentName`/`setClassName` building the class from a string.
  The only explicit-class `PendingIntent`s are the three internal ones above;
  `YaaccUpnpServerService.java:338-339` points at
  `YaaccUpnpServerControlActivity`, player notifications at the player
  activities (`LocalMediaSessionPlayer.java:104-106, 477-481`).
- Nothing in-repo needs `TabBrowserActivity` exported (the Group 6 Accept
  allowed `exported="false"` on that condition). Third-party launchers/automation that hard-code
  the component name (for example Tasker or home-screen shortcuts that were
  created from the launcher, which store the **launcher** component) cannot be
  excluded from here. A home-screen shortcut created before the change stores
  `TabBrowserActivity` as its component; making it non-exported would break
  such an existing shortcut. That is a user-visible risk of
  `exported="false"` that the repo cannot rule out [reasoned]. Built
  instead: `TabBrowserActivity` stays exported without filters, and its
  `onResume` redirects when the setting says `new`.
- Other activities declare `LAUNCHER` without `MAIN`
  (`ImageViewerActivity` `:93-100`, `MusicPlayerActivity` `:133-140`,
  `AVTransportPlayerActivity` `:144-151`, `MultiContentPlayerActivity`
  `:155-162`; all `VIEW` + `DEFAULT` + `LAUNCHER`). They do not appear in launchers (no
  `MAIN`), but the Group 6 Verify wording "exactly one exported launcher
  component" must be read as "exactly one `MAIN` + `LAUNCHER` component", or
  the check fails on the existing manifest.
- `SettingsActivity` is declared without `exported` (`:105-107`) and has no
  filter, so it is not exported; fine.

### How other code finds the "current" browser

It doesn't. [code]

- No `static` `Activity`/`Fragment` reference exists
  (`grep -rn "static.*Activity"` finds only constants). `UpnpClient` is built
  with the `Application` context (`Yaacc.java` `new UpnpClient(this)`),
  `PlayerService` holds a `UpnpClient`/service context, not an activity.
- Fragments reach their host only through `getActivity()`/`requireActivity()`,
  adapters through the `Context` they were built with
  (`BrowseDeviceAdapter.java:327-328` casts it to `Activity` for dialogs).
- The only global back-references to UI were leaks, since fixed: the
  single-slot `Yaacc.shutdownTimerListener` (fragment) and
  `MediaProjectionHelper.stopCallback` (fragment lambda), both described in
  (d), now multi-listener lists whose owners remove them; and the static
  `TabBrowserActivity.leftSettings` of (c), now `SettingsActivity.consumeLeftSettings()`.
- `Yaacc.exit()` finishes every app task (`getAppTasks().finishAndRemoveTask()`,
  `Yaacc.java:144`) then kills the process, so it works regardless of which
  browser class is on top.

### Where the plan and the code differed

Findings of this research that changed the plan (decisions.md 2026-10-24,
"Phase 2 plan amended by the Group 5 research"), and how each ended up:

1. **"Classic back flow goes content folder -> content root -> servers ->
   exit"** (tasks.md Group 7). Not true of the classic code: three of four back
   handlers were dead because `getParent()` is `null`; only folder pop and the
   Server-tab exit work. The new activity implements the intended flow itself
   (`BrowserBackNavigation`); the dead branches were removed, not turned on.
2. **"Classic behavior is unchanged" vs. "no `instanceof TabBrowserActivity`
   remains"**: wiring the four dead sites to `BrowserHost` would have changed
   classic, so only the live sites use it.
3. **"MediaProjection permission result is handled by the activity"**: the
   adapter is hosted by a fragment and the result routes to that fragment; no
   move was needed (`StreamingPermissionHelper`).
4. **Shutdown timer**: the timer already lived in `Yaacc`; what was missing was
   a running/remaining query and a detach path, not a move of timer logic.
5. **"Exactly one exported launcher component"**: four other activities declare
   `category.LAUNCHER` without `MAIN`; the check is `MAIN` + `LAUNCHER` /
   `LEANBACK_LAUNCHER`.
6. **`TabBrowserActivity` `exported="false"`** is safe inside this repo but could
   break home-screen shortcuts that stored its component; it stays exported
   without filters.
7. **"Clearing the old activity from the back stack" on a layout change**: the
   check-on-resume does it with `finish()`; `CLEAR_TASK` would kill the playing
   task's other activities.
8. **"Action allowlist MAIN and SEND only"** (tasks.md Group 6): built as "SEND
   `text/plain` with a capped text is forwarded, every other intent is a plain
   launch", which forwards exactly the same data.

## Sharing local files

What another app can share into yaacc (a link, or ONE audio, video or image file), how a share is served to the
receivers, and how the receiver is picked per action. Spec `2026-10-23-unified-device-sheet`, Phase 3 (Groups 12-16).
The "As built" subsections describe the code at `e3e0a247` on branch `feat/unified-device-sheet` (Groups 12-15 passed
the general and the security review); they cite classes, not line numbers. The Group 12 research the build started
from follows under "Research notes (Group 12)", kept for its citations; where it differs, the as-built text wins
("Where the build differs from the research" lists the differences). Nothing here was run on a device: "Not verified
on a device" lists what only a device can confirm.

### As built: overview

| Class | Role |
|---|---|
| `de.yaacc.LauncherActivity` | Exported entry. Forwards a text share or ONE file to the browser of the effective layout with a fresh intent. The SEND filters for `audio/*`, `video/*` and `image/*` are its only manifest change of Phase 3. |
| `de.yaacc.share.LaunchNonces` | Single-use launch token, minted by the launcher after its checks, required for a file at the browsers. |
| `browser.ShareIntentHandler`, `browser.ShareIntakeGate`, `share.StreamIntake`, `share.SharedContentPolicy`, `share.PendingShare` | Intake in the browser: repeats every check, keeps ONE pending share (text only), confirm/cancel by token. |
| `upnp.server.share.SharedFileService`, `SharedFileOpener`, `HeldFileSource`, `share.WatchedCopy`, `share.IncomingCache` | After the confirm: open (hold) or copy the file, register it, build the item. |
| `share.ShareRegistry`, `share.UnclaimedShares`, `share.ShareKeyTracker`, `player.ShareLifetimeListener` | The in-memory registry (random keys, allowed addresses, expiry), the claim window and the release when a player ends. |
| `upnp.server.http.YaaccUpnpServerContentHttpHandler`, `ProxyShareResponder` | Serve `/proxy/<key>` before the preference gate. |
| `share.ProxyAccessPolicy`, `share.ShareAddresses`, `upnp.server.share.ShareAddressResolver` | Which remote addresses may fetch a share. |
| `share.ByteRangeResolver`, `share.RangeStreamer`, `share.PositionalSource`, `share.ShareConcurrencyLimiter` | Range maths, streaming from the held file, per-share response cap. |
| `share.LinkTargetPolicy`, `share.HttpLinkTarget`, `share.LinkContentType`, `UpnpClient#createPlayableItem` | The link path (http/https). |
| `share.ProviderCallGate`, `share.BoundedCall`, `share.AbandonableCall`, `upnp.server.share.ProviderKeys` | Bound what a content provider can pin. |
| `browser.ReceiverPickController`, `browser.DeviceSheetFragment` (pick mode), `share.PendingPick`, `share.PickSheetResult`, `share.ActionFlight`, `share.AfterStartSelection`, `share.ReceiverPickResult` | The "Play on..." picker. |
| `browser.ReceiverBarLabel`, `browser.ReceiverBarController` | Receiver bar: the receivers that play. |

### As built: from the share menu to a pending share

1. **Manifest.** `LauncherActivity` has four filters: `MAIN`+`VIEW`/`LAUNCHER` (unchanged), `MAIN`/`LEANBACK_LAUNCHER`,
   `SEND`+`DEFAULT` with `text/plain`, and `SEND`+`DEFAULT` with three `<data>` elements `audio/*`, `video/*`,
   `image/*`. `git diff 071fc9d2 -- yaacc/src/main/AndroidManifest.xml` is exactly that one filter (7 lines).
   `TabBrowserActivity` stays exported without a filter, `BrowserActivity` stays `exported="false"`. No `SEND_MULTIPLE`.
2. **Launcher forward** (`LauncherForwardPolicy.decideStream`, `LauncherActivity#buildStreamForward`). The filter only
   looks at the sender's declared type, so the launcher checks: action `SEND`; a stream type (`audio/`, `video/`,
   `image/`); `EXTRA_STREAM` equals ClipData item 0; the incoming flags carry `FLAG_GRANT_READ_URI_PERMISSION`;
   `SharedContentPolicy` accepts the URI (a `content:` URI, not `file:`/`http:`, no own authority, length cap);
   `OwnProviders.isOwnAuthority` finds no provider of this app behind the authority (the app has more than one);
   on Android 15+ `SenderGrantCheck.mayForward` asks `getInitialCaller().checkContentUriPermission(...)` whether the real
   sender holds read access. Then it forwards a FRESH intent: action `SEND`, the declared type, ONE ClipData item
   (`ClipData.newRawUri`), `FLAG_GRANT_READ_URI_PERMISSION` only (the system checks the grant for `data` and
   ClipData, never for `EXTRA_STREAM`, so the URI is not repeated there), and the launch token
   (`LaunchNonces.EXTRA_NAME`). Any failure, or a `SecurityException` from `startActivity`, falls back to a plain launch.
   Text shares are forwarded exactly as before.
3. **Launch token.** The browsers are exported (classic) or reachable by internal intents, so any app can send them a file
   and skip the launcher's checks (including the Android 15 sender check). `LaunchNonces.mint()` returns 128 bits from a
   `SecureRandom`, kept only in the `Yaacc` application object: valid 30 s (`ShareLimits.LAUNCH_NONCE_TTL_MILLIS`), single
   use, at most 8 unused (oldest dropped), constant-time compare, monotonic clock. `ShareIntentHandler` accepts a FILE only if
   `consume(token)` succeeds; otherwise the intent is consumed and the share dropped with a payload-free log, before any
   provider call. Text needs no token. A re-delivery after process death carries a dead token and is dropped.
4. **Layout gate.** `ShareIntakeGate.admitOrDiscard(own, effective, intent)` runs in `onCreate` and `onNewIntent` of both
   browsers before the handler: an activity takes the payload only if it renders the effective layout (the same rule as
   the relaunch check), else the payload (text, clip data, `EXTRA_STREAM`, token) is removed unread. The launcher always
   starts the activity of the effective layout, so a launcher share is admitted in both layouts. A share that races a
   layout switch (about 2 s) is lost.
5. **Intake checks in the handler** (`ShareIntentHandler`, `StreamIntake.validate`). Repeated in full because the activities
   can be started directly: action `SEND`; exactly ONE ClipData item (only item 0 is read, never `EXTRA_STREAM`); no `data`;
   the read flag in the intent's own flags; content policy; not an own provider. The intent is consumed once. On a worker
   thread (`BoundedCall`, 3 s) the provider is asked for its type (`ContentResolver.getType`) and display name; the MIME is
   `SharedContentPolicy.chooseMimeType(providerType, intentType)` output only (concrete `audio/`, `video/`, `image/`,
   else the share is dropped); the name goes through `DisplayName` (control, bidi and other Unicode format characters removed,
   whitespace collapsed, 200 chars, fixed fallback text `share_unnamed_file`).
6. **Pending share** (`PendingShare`). At most ONE, holding only the validated URI text, the cleaned name, the type and the kind
   (`FILE` or `LINK`): no descriptor, no copy, no registry slot. A second share while one is pending or a probe runs is dropped
   (never replaces), with the fixed toast `receiver_pick_busy`. It is bound to a random token and times out after 2 minutes
   (`ShareLimits.PENDING_SHARE_TIMEOUT_MILLIS`). `BrowserHost#onPendingShare(token, name)` tells the activity, which opens the
   picker (`ReceiverPickController#offerShare`).
7. **Provider gate** (`ProviderCallGate`, `BoundedCall`, `ProviderKeys`). A binder call into a provider cannot be
   interrupted. One provider (the package that owns the authority, from `PackageManager.resolveContentProvider`) gets at most
   ONE call in flight, for as long as that call is outstanding (no expiry); at most 3 providers have a call outstanding
   (`PROVIDER_MAX_TRACKED`); each pool (intake probe, confirmed open) has 4 threads (`PROVIDER_POOL_THREADS`). Providers whose
   package cannot be resolved all share ONE key (`ProviderKeys.UNRESOLVED`). At intake a call the gate or pool refuses
   gives the toast `share_busy` (`ShareFailureMessage`); a timeout or a failing provider at intake is only logged; after the
   confirm a failure gives `share_open_failed` (or `share_busy` when the gate or the copy slot was full).

### As built: after the confirm

`ShareIntentHandler#confirm(token, ids, afterStarted, finished)` takes the pending share by token and runs `SharedFileService#prepare`
on the file executor:

1. **Open** (`SharedFileOpener.openDescriptor`, through `BoundedCall`, 20 s): `openAssetFileDescriptor(uri, "r")`. A descriptor
   is "seekable" if `fstat` says regular file and `lseek` works. A seekable one becomes a `HeldFileSource` (below). Otherwise
   the copy fallback runs.
2. **`HeldFileSource`** (`PositionalSource`): the provider's descriptor is `dup`ed and the provider's closed, so the provider
   process is not pinned by a stable reference; the provider's start offset and length are clamped to the real file size
   (`DescriptorWindow`); reads are `Os.pread` (no shared file offset, so ranges run concurrently, 64 KiB chunks); an in-flight
   counter defers the close to the last reader, so a descriptor number is never reused under a running read, and close never
   blocks the caller.
3. **Copy fallback** (`SharedFileOpener.copyAndHold`, `WatchedCopy`, `BudgetedCopier`, `CopyBudget`, `IncomingCache`): to
   `getCacheDir()/incoming/` (never `cache/shared/`, which `FileProvider` exposes), random file names, at most 512 MiB per file
   (`SharedContentPolicy.MAX_COPY_BYTES`), at most 1 GiB in the directory, 64 MiB of free space must remain. One copy at a time,
   on its own daemon thread, caller waits at most 5 minutes (`COPY_MAX_MILLIS`); at the deadline the caller closes the input and
   output, deletes the partial file and returns, the worker is abandoned (at most 3 abandoned workers at once, then "busy").
   The copy is deleted when its source is closed (`DeleteOnCloseSource`), and `Yaacc` sweeps `incoming/` at start (the registry is
   memory-only, so any file found is an orphan).
4. **Register.** After the local HTTP server answers (`HttpServerProbe`, a loopback connect, polled for up to 3 s) and the phone
   has an address, `ShareRegistrar.register` puts a `LOCAL_FILE` entry with the allowed addresses of exactly the chosen
   receivers into the `ShareRegistry`. When the registry is full, `evictOldestReleased()` runs once and `register` is retried;
   still full gives the toast `share_registry_full`. On any failure the source is closed, the copy deleted and the key removed
   before `prepare` returns null.
5. **Item.** `SharedItemFactory.create(mime, cleanedName, proxyUri, size, 0, 0)`: the provider type, size and cleaned name; no
   metadata is read from the shared file, so no duration is announced (the research planned a `MediaDataSource`; it was not built).
   The item is played on the receivers; the selection is cleared (new layout) or restored (classic) afterwards.
6. **Claim window.** `UnclaimedShares.registered` is called after `register`; a key that no player claimed within 2 minutes
   (`CLAIM_WINDOW_MILLIS`) is removed by the sweeper, which closes the descriptor.

### As built: the registry

`ShareRegistry` (plain Java, thread safe, injectable clock and random source):

- Key: 16 bytes from a `SecureRandom`, unpadded base64url, 22 characters of `[A-Za-z0-9_-]`
  (`ShareRegistry.isWellFormedKey`). Never reused while the registry lives (the last 256 retired keys are remembered, 8 draws per
  register). Nothing is persisted.
- Entry: kind (`HTTP_LINK` with an `HttpLinkTarget`, `LOCAL_FILE` with an `AutoCloseable` `PositionalSource`), MIME type, the
  allowed address set (normalized, fixed at registration), grace and absolute maximum.
- Lifetime: 10 minutes after the first `release` (`IDLE_GRACE_MILLIS`), at most 6 hours after registration
  (`ABSOLUTE_MAX_MILLIS`), at most 8 entries. An expired entry is never returned and its target is closed exactly once, outside the
  lock. `Yaacc` runs a sweeper every 60 s (`share-sweeper`: expiry, unclaimed shares, idle responses) and `removeAll()` on exit.
- Release: `ShareLifetimeListener` and `ShareKeyTracker` remember the keys of each player and release them when the player is
  removed (`PlayerListChangeNotifier.Listener#onPlayerRemoving` runs before `onDestroy()` empties the item list); a key is released
  only when no other player still uses it. Cancel, a failed start and a timeout remove or release the share they created.
- The old `proxy_link_*` preferences are retired: `Yaacc#removeLegacyProxyLinks` deletes them at start, nothing reads or writes them.

### As built: serving

`YaaccUpnpServerContentHttpHandler` routes every path whose first segment is exactly `proxy` to `ProxyShareResponder` BEFORE the
handler's preference gate, so a share is served whatever `settings_local_server_chkbx`, the provider or the proxy preference say. The HTTP
server listens whenever the app process lives (`Yaacc.onCreate` starts `YaaccUpnpServerService`, which creates the server
unconditionally); a share starts and stops nothing and waits only until the server answers. Order of decisions:

1. Path `/proxy/<key>` or `/proxy/<deviceId>/<key>` with a well-formed key, a registry entry that is live: else the same constant
   404 page (no path, key or link echoed, nothing logged). Other segment counts: 404.
2. `ProxyAccessPolicy.isAllowed(restrictToReceivers, entry addresses, remote address)`; the remote address comes only from the
   connection's endpoint details, never from a header; a missing or unparsable address is denied. Failure: 403 and a log line
   "Media proxy refused a request from <address>" (no key). The flag is read on every request:
   `settings_proxy_restrict_receivers_chkbx` (stored key `proxy_restrict_receivers_chkbx`), default `true` in code, in
   `preference.xml` and when the read fails. With the flag off every address passes this step; key and expiry still apply.
3. `deviceId` (3-segment form), only now: `RendererIdPolicy.isValid` (`[A-Za-z0-9:._-]`, 1-200), else 404.
4. `LOCAL_FILE`: `ByteRangeResolver.parseHeader` + `resolve` against the source size: no header, an invalid header or more than one
   range is 200 whole; one satisfiable range is 206 with `Content-Range`; an unsatisfiable one is 416 with `bytes */<len>`; suffix
   ranges are handled. `HEAD` opens and reads nothing. The body streams from the held file by `Os.pread` through `RangeStreamer`.
5. `HTTP_LINK`: the existing upstream relay (see "As built: links"); an upstream `Range` is rebuilt from the parsed numbers, never
   passed through.
6. Limits: at most 4 concurrent responses per share (`ShareConcurrencyLimiter`, 503 beyond); a response that makes less than
   16 KiB of progress in 60 s loses its slot (not its stream: if the reader resumes it asks for a slot again and aborts only when the
   share is full). Every share response carries `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox`;
   `Content-Type` is the entry's MIME type.

The proxy key and the proxy URL are not logged: `ShareLogRedactor` (case- and percent-insensitive `/proxy/...`) covers the SOAP,
AV-transport, image and download traces that can carry them.

**3-segment form and server-side seeking.** `/proxy/<deviceId>/<key>` IS produced: `AVTransportPlayer#modifyProxyUrlWithDeviceId`
rewrites a proxy URL of this server into it, but only for a remote renderer whose per-renderer preference
`manage_external_seeking_<deviceId>` ("enable server-side seeking for external URLs", `SettingsFragment.MANAGE_EXTERNAL_SEEKING`)
is on. It is served for `HTTP_LINK` entries only (`ServerSideSeeking`, `SeekPositionCalculator`, `RendererStateMap`: at most 8
renderer states per share, least recently used first, dropped with the share). The device id is validated after key and address
and is only used for the preference reads and that bounded map. The research claim that this form is never produced no longer holds.

### As built: links (http and https)

- Intake: text of at most 2048 characters (`ShareUriPolicy`, scheme `http`/`https` only; the check looks at scheme and length, see
  "As built: share handler"). In the new layout (`BrowserHost#providesReceiverBar`) a link is not played at once: it becomes the one
  pending share (kind `LINK`, shown as the fixed "Shared link" text, never as the link), the picker opens, and `confirm` stores the
  chosen receivers, builds the item and plays it; a refused link or a start without a player gives the share back
  (`UpnpClient#discardShareOf`) and shows `share_link_failed`. A link never falls back to this device in the new layout. Classic and a
  missing host keep the old behavior: wait up to 3 s (+3 s) for receivers, else play on `UpnpClient.LOCAL_UID`.
- `UpnpClient#createPlayableItem(Uri, Set<String> receiverIds)` (worker thread): reads metadata with `MediaMetadataRetriever`; if
  that fails, the type comes from a bounded `HEAD` (`LinkContentType`, redirects followed by hand, at most 3, every hop checked); only
  if that fails too the link is assumed to be an image, with a payload-free log line. The link is never logged.
- With the media proxy option (`settings_local_server_proxy_chkbx`) ON the link is registered (`HTTP_LINK`, allowed addresses
  from `receiverIds` captured at confirm time through `ShareAddressResolver`, never from the stored selection) and the receiver gets
  `http://<phone ip>:49157/proxy/<key>`. With it OFF the receiver gets the link itself and no registry entry exists. If the phone has
  no address, the server does not answer in 3 s or the registry is full (`share_registry_full`), the direct link is used.
- `LinkTargetPolicy.isAllowedTarget` (plain Java, nothing is resolved) runs only when the proxy option is on: it refuses literal
  loopback, unspecified and link-local addresses (including IPv4 hidden in IPv6 and non-dotted-decimal IPv4 spellings), `localhost`,
  `*.localhost` and every address of the phone itself; private LAN ranges stay allowed (NAS links). Known gaps are listed under
  "Known limitations".

### As built: the receiver picker

- Pick mode is `DeviceSheetFragment#showPicker` (tag `DeviceSheetPicker`; own layouts `fragment_device_sheet_pick.xml`,
  `device_sheet_pick_item.xml`): title "Play on..." (`receiver_pick_title`, accessibility heading), for a share the cleaned name
  below it (`PickTitle`), one `CheckBox` row per receiver including this device, hint `receiver_pick_none_hint`, Cancel and OK.
  The ticks live in the adapter, never in `UpnpClient`'s stored selection (the normal sheet writes through, a cancel could not be
  silent). `filterTouchesWhenObscured` is set on the rows and both buttons. The list refreshes when a device appears or leaves.
- The answer is delivered once through `FragmentManager.setFragmentResult` (`PickSheetResult` gates it): confirm from OK, cancel from
  `onDismiss` (back, outside tap, swipe, Cancel). `PendingPick` (in the activity, via `ReceiverPickController`) holds the one open pick:
  a new request never replaces it; a confirm with receivers runs the action once; a confirm with none keeps the sheet open
  (`ReceiverPickResult.decide`); a cancel runs `onCancelled` once and never hands out the ticks; a stale id changes nothing. The
  pending action lives in the activity: a rotation or process death with the picker open cancels it and releases a pending share.
- `ActionFlight`/`PickedAction`/`GuardedPickedAction`: from the confirm until the action reports `done`, no second picked action may
  begin (the stored selection is global and is read back by `PlayerService.createPlayer`); the picker refuses to open meanwhile
  (toast `receiver_pick_busy`). A flight nobody ends expires after the copy maximum plus 1 minute.
- New layout: `ContentListFragment#startPlayAction` (play, play-all, item tap, add to playlist): confirm sets the chosen ids
  (`UpnpClient#setReceiverDeviceIds`), runs `ContentItemPlayTask`, and `UpnpClient#clearReceiverDevices()` clears the selection in
  `finally`; cancel changes nothing. A share's confirm calls `ShareIntentHandler#confirm` with the ids; its after-start hook applies
  `AfterStartSelection` (new layout clear, classic restore the selection read at confirm time). Add to playlist
  (`UpnpClient#addToPlaylist`, unchanged by Phase 3): if none of the chosen receivers has a player, `initializePlayers` starts a
  new player on each chosen receiver; otherwise the item is added only to the playlists of the chosen receivers that already have
  one, and a chosen receiver without a player gets nothing (open owner question, decisions.md).
- Classic: normal browsing never shows the sheet and `startPlayAction` starts at once; the static footer stays. A pending SHARED FILE
  opens the same picker, with the classic selection as the initial ticks (see "Known limitations").
- Receiver bar: `ReceiverBarLabel` names the receivers that have a player in `PlayerService.getCurrentPlayers()` (names as in the
  mini-player: friendly name, else the player's name, cleaned by `MiniPlayerPresenter.cleanText`, duplicates merged, sorted, joined
  with "; "), else `receiver_bar_none_playing`. It refreshes from the player-list listener and the receiver events.
  `receiver_bar_none_selected` and `ContentListFragment#openDeviceSheetIfNoReceiverSelected()` no longer exist. The normal sheet has
  no checkboxes (`ReceiverRowBinder#bind(holder, device)`), its title is `device_sheet_title` ("Receivers").

### Threat model

Entry: the launcher is exported and accepts `SEND` from every app; `TabBrowserActivity` is exported too. Everything below is a
decision of the reviewed design (design.md "Phase 3", decisions.md).

- **Own data.** `file:` URIs and any `content:` URI of a provider of this app (not only the file provider) are rejected at the
  launcher and again at the browser. Only audio, video and image content of other apps is accepted, by the provider's own type.
- **Permission forwarding.** Android honors the read flag only if the forwarding app holds the access; the launcher forwards one
  URI, read flag only, nothing else of the incoming intent, behind the launch token. The grant lasts as long as the receiving
  activity record; a held descriptor outlives it, so the descriptor is opened only after the user confirmed, while the activity is
  alive.
- **Exposure on the LAN.** A share is reachable only by a 128-bit random key (22 characters), from the chosen receivers' addresses
  while the restriction setting is on (default), for at most the player's life plus 10 minutes (6 hours at most), at most 8 at once,
  4 responses each. The HTTP server itself runs whenever the app runs (existing behavior); without registry entries `/proxy/` answers
  404.
- **The key is not a secret from the LAN.** The proxy URL, key included, is what the receiver is told to play, so any UPnP control
  point that can query that receiver (for example `GetMediaInfo` or `GetPositionInfo` on its AVTransport service) can read it. With the
  restriction ON such a device is still refused, because its address is not one of the chosen receivers' addresses. With the
  restriction OFF the key is NOT a protection against anybody on the network: it only stops blind guessing. The setting's
  texts and docs say so.
- **Local apps.** When this device is a chosen receiver, loopback and the phone's own addresses are allowed (the in-app player fetches
  from them), so a hostile app on the phone is stopped only by the key. The key is not published by the app: the notification and
  the legacy `MediaSession` metadata carry title and name only; Media3 does not send `MediaItem.localConfiguration` to controllers
  (reasoned from the media3 1.11.1 bytecode, not observed on a device). Exception: see "Known limitations" (local video).
- **Confused deputy.** Android checks only that yaacc can read the URI. Yaacc holds broad media permissions and SAF grants, so a
  hostile sender could name MediaStore or SAF content that it cannot read itself. Mitigations: the audio/video/image type check; a
  human in the loop (every share needs a receiver pick that shows the cleaned file name); the address and expiry limits; on Android 15+
  the launcher's check that the sender holds the access. Below Android 15 that sender check does not exist; the residual is accepted by the owner. A browser activity started directly
  cannot bypass the launcher's checks for a file (launch token).
- **Link fetch (SSRF).** With the proxy option on, the phone fetches the shared link for the receivers. `LinkTargetPolicy` narrows
  what it will fetch (see "As built: links"); the answer goes to the chosen receivers only, not back to the sender.
- **Resources.** Provider calls are bounded as described above; the copy is capped and watched; descriptors are closed on expiry,
  release, eviction, `removeAll()` and exit; nothing is logged that holds a URI, name, key or proxy URL (a refused request logs the
  remote address only).

### Known limitations

The share feature has known, accepted limitations; the full technical list is kept with the project specification
(`.claude/specs/2026-10-23-unified-device-sheet/known-risks.md`), not in the published docs. In summary:

- The link policy is a best-effort filter, not a complete protection: it is enforced only when the media proxy option is on and
  it does not resolve host names.
- A share is protected by a random key and, by default, by the receivers' addresses (setting "Restrict the media proxy to the
  chosen receivers"). With that setting off, or on a device that is a chosen receiver, the key is the only protection.
- When "this device" plays a shared video, the video app that opens it receives the proxy address.
- Providers are called one at a time per app with timeouts; a provider that never answers can make later shares fail with a
  "busy" message until its call returns.
- The picker has no cool-down after a cancel; in the classic layout a shared file opens the picker with the saved receivers ticked.
- A rotation or process death while the picker is open drops the share.

### Not verified on a device

Everything in this section is code, tests and reading of platform sources; no device or emulator run exists. Still open (owner
checklist, decisions.md "Group 15 as built"): the whole chain sender - launcher - browser with a real grant on Android 11-16 and real
senders (gallery, files, messengers); `getInitialCaller()` for the system chooser on Android 15+; the launcher's `SecurityException`
fallback; whether `resolveContentProvider` sees a freshly forwarded provider on Android 11+; whether closing a provider stream wakes a
blocked read; whether a descriptor keeps working after the grant ends and after the sender app dies; which providers return pipes;
the address real renderers fetch from compared with their SSDP location; the picker with 10+ receivers, on a small screen, in
landscape, with TalkBack and D-pad, on rotation; the classic share path; a share arriving while the browser is in the background; the
proxy URL not appearing on the notification or lock screen of the local player.

### Where the build differs from the research

- Finding 1 (the server always runs) held and is the design: nothing is started or stopped, the share waits for the server.
- Finding 3 (`createPlayableItem` for `content:` URIs): files do not go through it; `SharedFileService` builds the item with the
  provider type and no duration. Links go through the rewritten `createPlayableItem`.
- Finding 4 (add to playlist breaks when the selection is cleared): add to playlist opens the picker too.
- Finding 5, 6, 7 (`Os.pread`, no close under a running read, `dup`): as researched.
- Finding 8 (the old range and proxy-path code): replaced for registry entries by `ByteRangeResolver` and `ProxyShareResponder`.
- Finding 9: `UpnpClient#clearReceiverDevices()` clears and fires the removal events; `getReceiverDevices()` no longer mutates the set
  returned by `SharedPreferences`.
- Section (c): the 3-segment form is produced for server-side seeking (see "As built: serving"); the preference-based proxy path is gone.
- Section (f): "Share flow in the new layout" and the pick-mode sketch are as built; the share is confirmed through the same picker
  in classic.
- Section (g): the API sketch is superseded by the code and tests; the order of the Group 13/14/15 work followed the plan, with the
  SEND filters added at the end of Group 15.

## Research notes (Group 12)

Research for spec `2026-10-23-unified-device-sheet`, Phase 3 (Group 12, first task). Verified
2026-10-25 against the checkout at `d8696971` on branch `feat/unified-device-sheet`, BEFORE the build; read it as history.
Nothing was run on a
device and the container had no Android SDK, so every platform statement rests on documentation or on
platform source. No code was written.

Versions that matter: `targetSdkVersion 33`, `minSdkVersion 27`, `compileSdk 36`
(`yaacc/build.gradle:75,110,111`); `httpcore5:5.3.6`, `httpcore5-h2:5.3.6`, `httpclient5:5.3.1`
(`yaacc/build.gradle:47-49`); `androidx.core:1.16.0` (see "Classic and new layout").

How each claim is marked:

- **[code]** read in this repo, cited `file:line` (paths relative to
  `yaacc/src/main/java/de/yaacc/` unless they start with `yaacc/`).
- **[docs]** official documentation, URL given. The `android-docs` and `context7` MCP servers were not
  needed; developer.android.com was fetched directly.
- **[aosp]** platform source. `frameworks/base` at tag `android-14.0.0_r1`, from
  `raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/...`. `libcore` is
  from the LineageOS mirror `LineageOS/android_libcore`, branch `lineage-21.0` (Android 14 based;
  `aosp-mirror/platform_libcore` and `android.googlesource.com` were not reachable). Newer Android
  releases (15, 16) were not read and may differ.
- **[httpcore]** source jars `httpcore5-5.3.6-sources.jar` and `httpcore5-h2-5.3.6-sources.jar` from Maven
  Central, the exact versions the app builds with.
- **[reasoned]** inferred from the above, not run.

### Findings that change the plan (read this first)

1. **The local HTTP server is already running whenever the app process is, regardless of
   `settings_local_server_chkbx`.** `Yaacc.onCreate` always calls `startService(YaaccUpnpServerService)`
   (`Yaacc.java:106`), `UpnpClient` binds it with `BIND_AUTO_CREATE` for the life of the process
   (`UpnpClient.java:150,173`), `onStartCommand` never reads the preference (`YaaccUpnpServerService.java:222-270`)
   and `initialize()` calls `createHttpServer()` unconditionally (`:476-477`), which listens on the wildcard
   address `new InetSocketAddress(PORT)` (`:820`, port 49157). The preference only decides which UPnP
   services the local `LocalDevice` publishes (`createUpnpDevice`, `:588-641`; with it off the device still
   exists with the two core services) and whether `LocalServerLifecycle` keeps the service started in the
   foreground (see (e)). So design.md Decision 1 ("starts the local server for that share only and stops it
   afterwards if it started it") and the threat-model line "the server is only up while a share is active
   unless the user runs it anyway" do not match the code. Group 13's Accept ("the server is started for the
   share if it is not running and stopped afterwards") reduces to: wait until the HTTP server answers, do not
   start or stop anything, serve registry entries before the preference gate (recorded in decisions.md, 2026-10-25; built that way).
2. **Another app's `content:` URI can still be one that yaacc may read on its own.** The manifest requests
   `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_*` (`yaacc/src/main/AndroidManifest.xml:20-31`) and the app keeps
   SAF tree grants. The forward check in the system (see (a)) passes when the *caller* holds access by its own
   permissions, not only by a grant from the sender. A malicious sender can therefore name e.g. a MediaStore
   URI it cannot read itself; the only gates left are the audio/video/image MIME check and the user choosing a
   receiver. Android offers no way to tell "granted by the sender" from "readable by us anyway" (see (a)).
   Residual risk to accept or discuss with the owner; design.md "Threat model" lists only `file:` and the own
   authority.
3. **`UpnpClient.createPlayableItem(Uri)` cannot be used unchanged for `content:` URIs.** It calls
   `MediaMetadataRetriever.setDataSource(String)` (`UpnpClient.java:1621`), whose documentation names a path
   or an http URI; a failure there is a `RuntimeException`, which the method turns into "assume shared image,
   `image/*`" (`:1624-1636`). A shared MP3 would be announced as an image. For a held descriptor use
   `setDataSource(FileDescriptor, long, long)` or a `MediaDataSource` (see (b)) and take the MIME type from the
   provider.
4. **Add-to-playlist breaks in the new layout once the selection is cleared after every action.**
   `UpnpClient.addToPlaylist` (`:1593-1607`) uses `getReceiverDevices()`; with an empty selection it ends in
   `PlayerService.createPlayer`, which shows "no receiver selected" and does nothing
   (`PlayerService.java:322-329`). The button is `BrowseContentItemAdapter.java:593`. design.md lists play,
   play-all and share only. Owner decision needed (pick too, or target the receivers of the running players). **Decided and built:** add to playlist opens the picker too.
5. **`FileChannel` is a poor fit for a long-lived shared descriptor; `Os.pread` is the better primitive**
   (tasks.md Group 13 says "seek via `FileChannel`/offset"). Reasons in (b). Both are safe for concurrent
   positional reads.
6. **Closing a descriptor that another thread is still reading is a security bug, not just an error**: the
   number can be reused at once by an unrelated socket or file. The shared source needs a read/close lock or
   reference count (see (b)).
7. **Holding a descriptor from `ContentResolver` holds a stable reference to the provider's process** until the
   descriptor is closed; if the sender app dies, the platform may kill yaacc. Duplicating the descriptor and
   closing the original releases that reference (see (b)).
8. **The existing range and proxy-path code is not reusable as is** and was replaced for registry entries (details of that
   research are kept with the project specification).
9. **Group 13 and the receiver picker must also clear the selection through a new method that fires the
   listener events**, because `ReceiverBarController` only refreshes on `receiverDeviceAdded/Removed`
   (see (f)).

### (a) How `ACTION_SEND` delivers one file, and what the trampoline may forward

**Delivery.**

- `ACTION_SEND`: `getType()` is the MIME type of the data, `EXTRA_STREAM` carries the data; "if you don't set a
  ClipData, it will be copied there for you when calling `Context.startActivity(Intent)`"; "use `*/*` if the
  MIME type is unknown (this will only allow senders that can handle generic data streams)" [docs:
  `https://developer.android.com/reference/android/content/Intent#ACTION_SEND`]. With
  `CATEGORY_TYPED_OPENABLE` the URIs "may be openable only as asset typed files" (same page); the framework's
  own `openAssetFileDescriptor(uri, "r")` already goes through `openTypedAssetFileDescriptor(uri, "*/*")`
  [aosp: `core/java/android/content/ContentResolver.java:1829-1862`], so use that call, not
  `openInputStream`.
- The copy happens in the **sender's** process: `Instrumentation.execStartActivity` calls
  `intent.migrateExtraStreamToClipData(who)` [aosp: `core/java/android/app/Instrumentation.java:1871` and five
  more sites]. `Intent.migrateExtraStreamToClipData` for `ACTION_SEND` builds
  `ClipData(null, {getType()}, Item(text, html, null, stream))` and, **if there is a stream, adds
  `FLAG_GRANT_READ_URI_PERMISSION`** [aosp: `core/java/android/content/Intent.java:12306-12360`, the `SEND` branch adds the flag at `:12353`]. It does
  nothing if a ClipData is already set ("Bail when someone already gave us ClipData", `:12310`) or if the extras
  are already parcelled. So: a plain sender delivers ClipData item 0 = the stream URI, plus the read flag; a
  sender that sets its own ClipData and flag may have `EXTRA_STREAM` and the ClipData name *different* URIs.
- `ACTION_SEND_MULTIPLE` is a different action and is not matched by a SEND filter; Phase 3 stays with one
  file (design.md decision 5).
- Reading the extra: `Intent.getParcelableExtra(String, Class)` is API 33; the app has `minSdk 27`, so use
  `IntentCompat.getParcelableExtra(intent, name, Uri.class)` (androidx.core 1.10.0+, the app has 1.16.0)
  [docs: `https://developer.android.com/reference/androidx/core/content/IntentCompat`]. It type-checks after
  deserialization on SDK 33 and below. Wrap it: a crafted extra can throw (the existing launcher already
  catches `RuntimeException`, `LauncherActivity.java:55-58`).

**MIME type filtering.**

- `<data android:mimeType="audio/*"/>` etc.: "the subtype can be the asterisk wildcard"; "MIME type matching in
  the Android framework is case-sensitive ... always specify MIME types using lowercase letters" [docs:
  `https://developer.android.com/guide/topics/manifest/data-element`]. Three `<data>` elements with
  `audio/*`, `video/*`, `image/*` in the one `SEND` + `DEFAULT` filter on `LauncherActivity` match any of them.
  A sender that uses `*/*` or `Audio/MP3` does not match. That is acceptable (declare only these three).
- The filter only looks at the **sender's `getType()`**, which is untrusted and may be a wildcard (`image/*` for
  a mixed selection). The real type must be checked on the content: `ContentResolver.getType(uri)` "returns a
  MIME type for the content, or null if the URL is invalid or the type is unknown" [docs:
  `https://developer.android.com/reference/android/content/ContentResolver#getType(android.net.Uri)`]. It is a
  Binder call into the provider (not on the main thread) and its answer is the provider's, so it is also
  untrusted text: run it through `SharedContentPolicy.isAcceptableMimeType` (concrete `audio/x`, `video/x`,
  `image/x`). Recommended order: provider type if acceptable, else the intent's type if it is concrete and
  acceptable, else reject.

**Who may grant, and what Android honors.** All from `services/core/java/com/android/server/uri/` [aosp]:

- Only `content:` URIs are granted at all; any other scheme returns "nothing to do" and the receiver gets no
  access (`UriGrantsManagerService.java:1081-1100`). (`ContentResolver` itself also opens `file:` and
  `android.resource:` URIs without any grant, `ContentResolver.java:1523,1852`, and the docs list `file` as an
  accepted scheme: that is why the policy must reject them.)
- A grant is checked on every start (`ActivityStarter.java:600-608`, `checkGrantUriPermissionFromIntent`) for
  the intent's `data` **and every ClipData item URI** (`UriGrantsManagerService.java:587-660`, recursing into a
  ClipData item's nested `Intent`). `EXTRA_STREAM` and other extras are never looked at. The flags used are the
  top-level intent's only [docs: `Intent#FLAG_GRANT_READ_URI_PERMISSION`: "the recipient of this Intent will
  be granted permission to perform read operations on the URI in the Intent's data and any URIs specified in
  its ClipData ... only the grant flags of the top-level Intent are used"].
- The check for each URI (`checkGrantUriPermissionUnlocked`, `:1081-1266`): (1) if the target already holds
  access by manifest permissions (provider in its own uid, or exported without a permission) nothing is
  recorded; (2) the provider must have `grantUriPermissions` (and match `uriPermissionPatterns`), else
  `SecurityException "does not allow granting of Uri permissions"` (`:1212-1236`); (3) **the caller must hold
  access itself**, either by manifest permission (`checkHoldingPermissionsUnlocked`) or by a strong enough
  URI grant, else `SecurityException "UID n does not have permission to <uri>"` (`:1243-1262`). Step 3 is the
  rule "Android honors the flag only if the sender holds the access". The target-already-has-it shortcut
  (`:1137`) looks at manifest permissions only, not at URI grants, so a same-uid forward is not exempt
  (`:913-1000`).
- A grant is recorded per **target uid and URI**, with the receiving **`ActivityRecord` as owner**
  (`ActivityStarter.java:1750` -> `ActivityRecord.getUriPermissionsLocked()`, `ActivityRecord.java:4719`; also
  `deliverNewIntentLocked`, `:4862-4868`, for an activity that gets `onNewIntent`). It lasts until the last
  owner is removed: `ActivityRecord.removeFromHistory` -> `removeUriPermissionsLocked` (`:3967`, `:5164`) ->
  `UriPermissionOwner.removeUriPermissions` -> `UriPermission.removeReadOwner` (`UriPermission.java`: when the
  owner set is empty `ownedModeFlags` loses read). The docs' wording is "expire automatically when the receiving
  app's task stack is finished" [docs: `https://developer.android.com/training/secure-file-sharing/share-file`];
  the source is finer: it is the lifetime of the **activity record(s)** that received the intent, and because the
  key is the uid, every component of yaacc (services, other threads) can use it meanwhile. Process death does
  not end it; the record does.
- No code in `UriGrantsManagerService`, `UriPermission` or `UriPermissionOwner` refers to a
  `FileDescriptor` (grep count 0): ending a grant never reaches descriptors already handed out (see (b)).

**What happens when the trampoline forwards.**

1. The share reaches `LauncherActivity` (`Theme.NoDisplay`, `LauncherActivity.java:49-65`): the system records
   the sender's grant on the launcher's `ActivityRecord`.
2. In `onCreate` the launcher calls `startActivity(forward)` for `TabBrowserActivity`/`BrowserActivity`. The
   system runs the check above with `callingUid` = yaacc, for every ClipData URI of the *forward*. The launcher
   holds a strong URI grant at this moment (its record is alive until after `onCreate` returns and the
   asynchronous finish/destroy), so step 3 passes **only for a URI that really reached it**, and the provider
   must still allow grants.
3. The system records a second grant on the **target's** record (`ActivityStarter.java:1750`). Finishing the
   trampoline removes only the trampoline's owner entry; the target's entry keeps the `UriPermission` alive.
   Therefore the grant "transfers" in the only sense that matters: it is valid from the moment the browser
   starts until the browser record is removed (user finishes the browser, `Yaacc.exit()`, or the system
   destroys the record). A rotation recreates the activity inside the same record and keeps it [reasoned].
4. If the forward names a URI the launcher cannot access, `startActivity` throws `SecurityException` in the
   launcher (the check runs in the system server and is rethrown to the caller) [reasoned from
   `ActivityStarter.java:605`]. The current `LauncherActivity` catches `RuntimeException`, logs the class name and
   starts nothing (`:61-63`): the user would see the launcher vanish. Recommended: on that exception, log
   payload-free and fall back to the plain launch.
5. `getIntent().setClipData(null)` or removing extras in the browser (as the text path does) does not revoke
   anything; only the record's removal does.

**What the narrowest safe forward is.**

- Explicit `Intent(this, targetClass)`, `setAction(ACTION_SEND)`, `setType(<validated concrete type>)`,
  `setClipData(ClipData.newRawUri("", uri))` with **exactly one item and no `Intent` item**, and
  `addFlags(FLAG_GRANT_READ_URI_PERMISSION)` only [docs: `Intent#setClipData`: "FLAG_GRANT_READ_URI_PERMISSION
  and FLAG_GRANT_WRITE_URI_PERMISSION will operate on any URI items included in the clip data"]. Leave
  `EXTRA_STREAM` out, so the forward carries one URI in one place (the system checks the ClipData, not the
  extra). Equivalent alternative: only `putExtra(EXTRA_STREAM, uri)` and let `Instrumentation` migrate it and
  add the flag (`Intent.java:12353`); the explicit form is easier to test.
- Never forward: `FLAG_GRANT_WRITE_URI_PERMISSION`, `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`,
  `FLAG_GRANT_PREFIX_URI_PERMISSION` (a prefix grant would cover sibling files), `data`, more than one ClipData
  item, an `Intent` clip item, other extras, categories, selector, the incoming flags.
- Accept the URI in the launcher only if all of these hold: action `SEND`; `SharedContentPolicy` accepts the
  string (content scheme, not our authority, no `@`, no `%` in the authority); the incoming ClipData has the
  same URI in item 0 (so a mismatch between `EXTRA_STREAM` and the clip, which no grant covers, is dropped);
  the incoming flags include `FLAG_GRANT_READ_URI_PERMISSION`. The authority check should also reject any
  authority that resolves to a provider of our own package, not only the FileProvider: compare
  `PackageManager.resolveContentProvider(authority, 0).packageName` with `getPackageName()` (the FileProvider's
  authority is `${applicationId}.fileprovider`, manifest `:93`; its paths expose only
  `cache/shared/`, `yaacc/src/main/res/xml/file_paths.xml`).
- None of this proves that the **sender** holds the access (finding 2). `getCallingPackage()` is null for a
  plain `startActivity`, `Activity.getReferrer()` honors a sender-supplied `EXTRA_REFERRER`, and
  `checkUriPermission(uri, pid, uid, flags)` for our own uid is true for both grants and our own permissions.
  There is no public API to ask "was this URI granted by the sender". Accepted as residual risk, mitigated by
  the user-chosen receiver, the type check, no persistence and the short lifetime.
- The browser side must treat the forwarded intent as untrusted too: it is `exported="false"` for
  `BrowserActivity`, but `TabBrowserActivity` stays exported without filters (see "Classic and new layout",
  (g)), so any app can send it an intent. Read the URI only from ClipData item 0 when `getItemCount() == 1`,
  run `SharedContentPolicy` again, and keep only a PENDING share (URI, cleaned name, token); the descriptor is opened after the user's confirmation, see (f).
- `MediaStore` hint: a `content://media/...` URI is a legitimate share from several gallery apps, so do not
  reject the `media` authority.

### (b) Descriptor lifetime, seekability, serving ranges, copy fallback

**An open descriptor outlives the grant.**

- Access is checked when the descriptor is **opened**: `ContentProvider.Transport.openAssetFile` and
  `openTypedAssetFile` call `enforceFilePermission` and then the provider [aosp:
  `core/java/android/content/ContentProvider.java:599-604, 656-662, 794-808`]. The result is a
  `ParcelFileDescriptor` sent over Binder, which the kernel duplicates into our process; from then on reads
  hit the file, not the provider's permission check. Ending a grant edits a table only (see (a), grep count 0),
  so no code path closes or invalidates a descriptor [aosp + reasoned]. `ContentResolver#openFileDescriptor`:
  "You own this descriptor and are responsible for closing it when done" [docs:
  `https://developer.android.com/reference/android/content/ContentResolver#openFileDescriptor(android.net.Uri,%20java.lang.String)`].
- It is still **not verified on a device** with real senders (see the last section). Things that do end the
  usefulness of a descriptor: the sender deleting or truncating the file while we stream (POSIX keeps an
  unlinked inode readable; truncation shows up as short reads), a provider that returns a pipe, and the
  provider process dying (next bullet).
- **Stable provider reference.** `openAssetFileDescriptor` returns an `AssetFileDescriptor` whose
  `ParcelFileDescriptor` is a `ParcelFileDescriptorInner` holding `acquireProvider(uri)` (a stable reference)
  until it is closed [aosp: `ContentResolver.java:1900-1910, 3907-3917`]. The documentation of the unstable
  variant says the stable kind "turns on ... the mechanism in the platform clean up processes that are
  dependent on a content provider if that content provider's process goes away" [docs:
  `ContentResolver#acquireUnstableContentProviderClient(Uri)`]; i.e. if the sender app crashes or is killed
  while we hold the descriptor, yaacc can be killed too [reasoned]. Cheap mitigation: take
  `ParcelFileDescriptor.dup(...)` of the descriptor (a plain descriptor, not an `Inner`; `dup` is
  `fcntl(F_DUPFD_CLOEXEC)`, `ParcelFileDescriptor.java:361-382`) and close the original at once; this releases
  the provider reference and keeps a working kernel descriptor [reasoned; needs a device test: kill the sender
  while streaming]. The dup shares the file offset with the original, which does not matter with positional
  reads.
- At most one descriptor per share and at most `ShareRegistry.DEFAULT_MAX_ACTIVE` (8) shares: no descriptor
  pressure.

**Detecting seekability.**

- `ContentResolver#openFileDescriptor`/`openAssetFileDescriptor` with mode `"r"` "could be a pipe or socket
  pair to enable streaming of data. Opening with the "rw" mode implies a file on disk that supports seeking"
  [docs, same page]. Do not use `"rw"`: it needs write access and fails for a read grant.
- Prefer `openAssetFileDescriptor(uri, "r")` over `openFileDescriptor`: the latter throws
  `FileNotFoundException("Not a whole file")` when the provider returns a sub-section of a file
  (`ContentResolver.java:1680-1710`; docs: "so will not work with providers that return sub-sections of
  files"). Use `getStartOffset()` and `getDeclaredLength()` (`-1` = to the end, `UNKNOWN_LENGTH`);
  `getLength()` returns the declared length or else `getParcelFileDescriptor().getStatSize()`, and the
  constructor requires `startOffset == 0` when the length is unknown (`AssetFileDescriptor.java:84,135-141`).
  Every read position is `startOffset + position`, and every read is capped by the length.
- `ParcelFileDescriptor#getStatSize()` "returns the total size of the file ... as determined by `stat()`. Returns
  -1 if the fd is not a file" [docs; aosp `ParcelFileDescriptor.java:703-721`: `fstat`, size only for
  `S_ISREG`/`S_ISLNK`]. So `-1` means pipe, socket or device. A provider may still declare a length for a
  pipe, so the declared length does not prove seekability.
- Recommended rule (public API only): a descriptor is seekable if `Os.fstat(fd)` reports a regular file
  (`OsConstants.S_ISREG(st.st_mode)`) **and** `Os.lseek(fd, 0, OsConstants.SEEK_CUR)` succeeds (ESPIPE for
  pipes and sockets). `android.system.Os` is public API since 21 [docs:
  `https://developer.android.com/reference/android/system/Os`: `fstat`, `lseek`, `pread` "See pread(2)", all
  "Added in API level 21"]. Proxy descriptors from `DocumentsProvider`s (FUSE-backed) are regular files to
  `fstat` and seek [reasoned; may be slow, see the last section].
- Not seekable, unknown size (<0) or a zero length: use the copy fallback or reject.

**Serving `Range` from the shared descriptor without closing it.**

- Use **positional reads only**. `Os.pread(FileDescriptor, byte[], int, int, long)` ("Added in API level 21",
  `throws ErrnoException, InterruptedIOException`) does not touch the file offset, which a `dup` shares with the
  original (`ParcelFileDescriptor.dup` javadoc, `ParcelFileDescriptor.java:355-360`: "shared state such as file
  position"). `FileChannel.read(ByteBuffer, long)` is also positional: "This method does not modify this
  channel's position" [docs: `https://developer.android.com/reference/java/nio/channels/FileChannel`], and "File
  channels are safe for use by multiple concurrent threads ... Other operations, in particular those that take an
  explicit position, may proceed concurrently; whether they in fact do so is dependent upon the underlying
  implementation and is therefore unspecified" [libcore: `ojluni/.../java/nio/channels/FileChannel.java:84-92`].
  On Android: `FileChannelImpl.read(dst, position)` -> `readInternal` -> `IOUtil.read(fd, dst, position, nd)`
  -> `FileDispatcherImpl.pread` -> native `pread0` [libcore: `sun/nio/ch/FileChannelImpl.java:763-798`,
  `FileDispatcherImpl.java:52-57,147`]; `needsPositionLock()` is not overridden there [reasoned: the native
  call is `pread64`]. So concurrent range requests on one descriptor are safe at the kernel level.
- Why `Os.pread` over a cached `FileChannel`: (1) `new FileInputStream(fd)` is not the descriptor owner on
  Android (`FileInputStream(FileDescriptor)` -> `isFdOwner = false`, libcore `FileInputStream.java:230-255,
  577-579`), so closing it does not close the descriptor, but (2) a `FileChannel` is an
  `AbstractInterruptibleChannel`: interrupting a thread that is inside a read closes the channel for everyone
  (`FileChannelImpl.readInternal` uses `begin()/end()`), and a closed channel stays closed. HttpCore's I/O
  threads can be interrupted on shutdown. A per-request `FileInputStream(...).getChannel()` that is never
  closed would work but still leaks the interrupt behavior into a shared object. `Os.pread` has no state.
- **Never close the shared descriptor from a request.** In an `AbstractBinAsyncEntityProducer`, keep only the
  request's own counters (next position, bytes left) and make `releaseResources()`/`failed()` touch nothing
  shared. Closing belongs to the registry (`AutoCloseable` target), on expiry, release or `removeAll()`.
- **Close vs. read race.** If the registry closes the descriptor while a request thread is in `pread`, the number
  can be reused immediately by another open (a socket, another file) and the read returns foreign data or fails
  [reasoned; standard POSIX hazard]. The shared source class should guard it: a `ReentrantReadWriteLock`
  (`readAt` takes the read lock, checks `closed`, reads; `close()` takes the write lock, sets `closed`, closes
  the descriptor, deletes a copy file) or an in-flight counter with deferred close. `readAt` after close
  returns -1 / throws `IOException`, the response ends.
- For metadata, do not hand the shared descriptor to `MediaMetadataRetriever.setDataSource(FileDescriptor, ...)`:
  the native side seeks and reads it, which moves the shared offset (harmless for `pread` users, but also the
  documentation says "it is safe to close as soon as this call returns" only for a descriptor you own) [docs:
  `https://developer.android.com/reference/android/media/MediaMetadataRetriever#setDataSource(java.io.FileDescriptor,%20long,%20long)`].
  Better (**not built**: a shared file's item has no duration): a small `MediaDataSource` over the shared source (`readAt(position, buffer, offset, size)` is
  exactly positional; `getSize()`; `close()` a no-op), set with `setDataSource(MediaDataSource)` (API 23,
  minSdk is 27).
- Threading: the handler runs on the reactor's I/O threads (`setIoThreadCount(8)`,
  `YaaccUpnpServerService.java:763-769`; `produceData` is called from them [httpcore]). A `pread` of a local file
  is fine; a FUSE-proxy descriptor from a cloud provider can block a reactor thread. Read in chunks of 16-64
  KiB per `produceData` call and let `availableData()` return a positive number while bytes remain (the
  existing streaming producer returns 8192 while `bytesRead < rangeLength`, `:1541-1546`).
- HEAD: HttpCore ends the stream after the headers for a `HEAD` request even when the handler supplies an entity
  (`ServerHttp1StreamHandler.java:185-187` [httpcore]). The handler must not open or read anything for `HEAD`
  and must set the length headers from the known size.

**Copy fallback (option B).**

- When: descriptor not seekable, or length unknown/negative. Never copy from a seekable one.
- Where: `new File(context.getCacheDir(), "incoming")` (app-private, "may be deleted by the system when low on
  space"). **Not** `cache/shared/`: that directory is exposed by `FileProvider` (`file_paths.xml`) and used by
  `util/ShareFileTask.java:76`. Name each file with `File.createTempFile("in", ".bin", dir)` (random name; never
  the provider's display name or the URI), mode private, no extension from the sender.
- Size cap: `SharedContentPolicy.MAX_COPY_BYTES` = 512 MiB in the skeleton, a sound default [reasoned]. Count
  bytes while copying and stop at the cap (delete the partial file), also stop when
  `getCacheDir().getUsableSpace()` is below the remaining size plus a margin (e.g. 64 MiB). Sequential copy from
  the already open descriptor (`new FileInputStream(fd)` is not the owner, so close it freely), on a worker
  thread; show feedback because 512 MiB takes seconds to tens of seconds. A total budget across shares is
  advisable (e.g. at most two copies at once) because `maxActive` 8 times 512 MiB is 4 GiB [reasoned].
- Cleanup: the copy source is an `AutoCloseable` registry target that deletes the file on `close()`; on
  `ShareRegistry.removeAll()` (service stop, `Yaacc.exit()`); and a sweep of `cache/incoming/` at process start
  (the registry is memory-only, so any file found there is an orphan).

### (c) The proxy HTTP path, and the remote address of a request

The details of the pre-share media proxy research (the handler's old request flow, content holders and the preference-based
link storage) are kept with the project specification. What changed: the `/proxy/` path is served from the in-memory registry
before the handler's preference gate, with its own key check, address check and range resolution, see "As built: serving"; the
preference-based storage is retired, see "As built: the registry".

**The remote address of a request.** `handle(Message<HttpRequest, byte[]>, ResponseTrigger, HttpContext)`
(`:198-200`) receives a `HttpContext` that the stream handlers create as `HttpCoreContext.create()` and fill with
`setEndpointDetails(getEndpointDetails())` for HTTP/1.1 (`ServerHttp1StreamDuplexer.java:321-323, 355-357`) and for
HTTP/2 (`ServerH2StreamMultiplexer.java:107-109`) [httpcore 5.3.6]. `EndpointDetails#getRemoteAddress()` returns a
`java.net.SocketAddress` (`BasicEndpointDetails` built from `ioSession.getRemoteAddress()`,
`AbstractHttp1StreamDuplexer.java:578-587`). Usage:
`EndpointDetails d = HttpCoreContext.cast(context).getEndpointDetails();`
`if (d != null && d.getRemoteAddress() instanceof InetSocketAddress) { String ip = ((InetSocketAddress) d.getRemoteAddress()).getAddress().getHostAddress(); }`
(`HttpCoreContext.cast` exists since 5.3, `HttpCoreContext.java:100`). Never use `getHostName()` (reverse DNS on
the I/O thread). Missing details or another address type -> deny (fail closed). The concrete type being
`InetSocketAddress` for a TCP session is [reasoned] (NIO socket channel); no cast failure is possible with the
`instanceof` guard. IPv6 addresses carry a scope after `%` in `getHostAddress()`; `ProxyAccessPolicy.normalize`
strips it. None of the code in the repo reads the endpoint details today (`grep` finds no
`EndpointDetails`/`HttpCoreContext`). The test source of `HttpCoreContext` cannot be exercised on the JVM without
a reactor, so the glue stays a four-line function and the decision lives in `ProxyAccessPolicy`.

### (d) A receiver's IP address; who resolves the receivers for a playback

**From the registered device [code].**

- Remote UPnP device: `RemoteDevice#getRoot().getIdentity().getDescriptorURL()` is a `java.net.URL` (the SSDP
  `LOCATION`), `getHost()` its host. `RemoteDeviceIdentity` is `org/fourthline/cling/model/meta/RemoteDeviceIdentity.java`
  (vendored cling; `getDescriptorURL()`, `getDiscoveredOnLocalAddress()`). Embedded devices are built with
  `new RemoteDeviceIdentity(udn, getIdentity())`, which copies the root's descriptor URL
  (`RemoteDevice.java:140-146`), so the root and its children share the address; use `getRoot()` anyway
  (`RemoteDevice.java:198-205`). `UpnpClient.getDevice(id)` may return an embedded device
  (`getRegistry().getDevice(udn, false)`, `UpnpClient.java:524`).
- Second candidate: `device.getDetails().getBaseURL()` (`<URLBase>`), which cling prefers for resolving
  relative service URLs (`RemoteDevice.normalizeURI`, `:128-134`). If its host differs from the descriptor
  host, include both.
- `URL.getHost()` of an IPv6 literal keeps the brackets (`[fe80::1]`); `ProxyAccessPolicy.normalize` rejects
  brackets by contract, so the glue must strip exactly one pair first. A **host name** (allowed by SSDP, rare)
  must not be resolved on the main thread (`NetworkOnMainThreadException`); resolve with
  `InetAddress.getAllByName` on a worker and add every result, or leave it out (then the IP check would deny; the
  setting to switch the check off is the escape).
- Multi-address devices (Wi-Fi + Ethernet, IPv4 + IPv6): cling keeps one `RemoteDeviceIdentity` per root, so
  yaacc only ever knows the address of the last announcement and (maybe) the `URLBase` host. A renderer may
  fetch from another address. That is the documented risk; the "Restrict the media proxy to the chosen
  receivers" setting is the owner's chosen answer. A payload-free log line with the denied address helps users.
- IPv4-mapped IPv6: whether a dual-stack accept reports `::ffff:a.b.c.d` or `Inet4Address` is not verified
  [reasoned: the JDK maps to `Inet4Address`]; `normalize` maps both to the IPv4 text, so either way works.
- This phone: the `UpnpClient.LOCAL_UID` dummy device (`UpnpClient.LocalDummyDevice`, `:1517`; it has no
  `RemoteDeviceIdentity`) and, if the server publishes a renderer, a `LocalDevice` with
  `DeviceIdentity` (no URL). The code already treats both alike (`device instanceof LocalDevice || device
  instanceof UpnpClient.LocalDummyDevice`, e.g. `UpnpClient.java` `getMute`/`hasActionGetVolume`). Allowed
  addresses for them: `127.0.0.1`, `::1`, `InterfaceResolutionHelper.getIpAddress(context)` (first non-loopback
  IPv4 of the first non-blacklisted or the selected interface; falls back to `127.0.0.1`, never `0.0.0.0` in
  practice, `util/InterfaceResolutionHelper.java:83-152`) and every address of
  `InterfaceResolutionHelper.getBindAddresses(context)` (all addresses of that interface incl. IPv6, `:53-71`).
  The in-app player fetches the proxy URL whose host is `getIpAddress`, so the source address of that
  connection is that same address [reasoned] (`usesCleartextTraffic="true"`, manifest `:44`).
- Address of **our** side for the URL: today `getIpAddress` (first IPv4 of the selected interface). A receiver on
  another interface of a multi-homed phone would be better served by
  `RemoteDeviceIdentity#getDiscoveredOnLocalAddress()`; optional improvement, not needed for Phase 3.

**Who resolves the receiver set for a playback [code].**

- Selection storage: `UpnpClient.getReceiverDeviceIds()` (`:1025-1030`, preference
  `settings_selected_receivers_title`, default `{LOCAL_UID}` **only when the key is absent**),
  `setReceiverDeviceIds` (`:1036`), `addReceiverDevice` (`:1099`), `removeReceiverDevice` (`:1112`), both of which
  also fire `receiverDeviceAdded/Removed` (`:188-198`, private methods).
- `UpnpClient.getReceiverDevices()` (`:1049-1066`) resolves ids with `getDevice(id)`, **removes unknown ids from the
  stored set and writes it back**. It mutates the `Set` returned by `SharedPreferences.getStringSet` (`:1062`),
  which the API forbids ("you must not modify the set instance returned by this call", `SharedPreferences.java:275-277`
  [aosp]); copy it first. `getReceiverDevicesReadyCount()` `:1067`.
- Playback: `UpnpClient.initializePlayers(...)` (`:724,734`) and `initializePlayersWithPlayableItems(...)` (`:1258`)
  call `PlayerService.createPlayer(UpnpClient, List<PlayableItem>)` (`PlayerService.java:296-341`), which reads
  `upnpClient.getReceiverDevices()` twice (`:322` empty check with the "no receiver selected" toast, `:331` loop)
  and creates one player per receiver. The players keep the device id, so clearing the selection afterwards does
  not stop them. `createPlayerForLocalDevice` (`:345`) is used by the inbound renderer path
  (`AvTransportMediaRendererPlaying.java:88`) and does not read the selection.
- Callers: `ContentItemPlayTask` (`browser/ContentItemPlayTask.java:35-46`, AsyncTask, `PLAY_CURRENT`,
  `PLAY_ALL`, `ADD_TO_PLAYLIST`) -> `ContentListFragment.playItem` (`:774`), `playAllChildsOfParentFrom` (`:795`),
  `addToPlayList` (`:875`) -> `initializePlayers` -> private `play(List<Player>)` (`:865-871`);
  `ShareIntentHandler.prepareAndPlay`/`play` (`browser/ShareIntentHandler.java:154-186`, waits for receivers 3 s
  and falls back to `LOCAL_UID`).
- Proxy URLs are built **before** the receivers are known today (`createPlayableItem`, inside
  `prepareAndPlay`, after the waits). With per-share IP sets the order must be: pick receivers -> compute
  allowed addresses -> register -> build the URL -> create players.

### (e) Starting and stopping the local server for one share

**What is there [code].**

- `LocalServerLifecycle.apply(Context, boolean restart)` (`upnp/server/LocalServerLifecycle.java:44-59`): reads
  `settings_local_server_chkbx`; on: (optional `stopService` first when `restart`) then
  `startForegroundService`; off: `stopService`. Callers: `onResume` of both browsers
  (`BrowserActivity.java:163`, `TabBrowserActivity.java:207`). Other starters: `Yaacc.java:106` (plain
  `startService`, always), `ServerAutostart.java:44` (boot, if autostart), `YaaccUpnpServerControlActivity`
  `start()/stop()` (`:327-345`, which also write the preference).
- `YaaccUpnpServerService`: `onCreate` registers the preference listener (`:164-179`); `onStartCommand`
  (`:222-270`) creates the registry and `NetworkDeviceListener`, starts the `initialize()` thread (HTTP server,
  retries, then `createUpnpDevice`) and calls `showNotification()` -> `startForeground(UPNP_SERVER, ...)`
  (`:425`) every time; returns `START_STICKY`. `onDestroy` (`:272-320`) shuts the HTTP server down (and would
  throw a `NullPointerException` at `networkDeviceListener.disable()` if `onStartCommand` never ran).
  `restartServer()` (`:1377-1411`) is for interface changes.
- The preference listener (`onSharedPreferenceChanged`, `:659-728`) reacts to `settings_local_server_chkbx`,
  `..._provider_chkbx`, `..._receiver_chkbx` with `createUpnpDevice()` (+ notification), to the selected
  interface with `restartServer()`, and to the serve-media toggles. `settings_local_server_proxy_chkbx` is not in
  the list: the proxy flag is read per request (handler `:232`) and for the notification text (`:377`).
- Therefore: **the preference does not bind the HTTP listener**; it binds the published UPnP services, the
  foreground notification (via `LocalServerLifecycle`) and the browsers' `onResume` re-application. With the
  preference off, `LocalServerLifecycle` calls `stopService`, but a bound `BIND_AUTO_CREATE` connection (the
  `UpnpClient`) keeps the service alive: "if a stopped service still has ServiceConnection objects bound to it
  with the BIND_AUTO_CREATE set, it will not be destroyed until all of these bindings are removed" and "If the
  service is running as a foreground service when it is stopped, its associated notification will be removed"
  [docs: `https://developer.android.com/reference/android/content/Context#stopService(android.content.Intent)`].
  So with the preference off the server runs without its notification.
- **Recommendation: do not start or stop the service for a share.** What the share needs is readiness:
  `UpnpClient.isInitialized()` is true when the service is bound and `registry != null &&
  networkDeviceListener.isInitalized()` (`YaaccUpnpServerService.java:1248-1250`), which does **not** mean the
  HTTP server is listening (the private `isInitialized` field is set after the server attempt; the private
  `isHttpServerHealthy()` is not exposed). Two options: expose a small public `isHttpServerRunning()` on the
  service, or probe `http://127.0.0.1:49157/health` (answered 200 for everyone, handler `:220`). If the port is
  taken after 3 attempts (`HTTP_SERVER_MAX_RETRIES`, `:147`) there is no server; the share must fail with
  feedback instead of waiting forever.
- Knowing whether "the share started the server" is moot. If the owner nevertheless wants an explicit
  start/stop, the only supported pattern is `startForegroundService(new Intent(ctx,
  YaaccUpnpServerService.class))` while the app is visible, and `stopService` afterwards **only if the
  preference is off and no other share/player needs it**; the user's preference stays untouched. But
  `stopService` will not destroy the bound service anyway, so it would only remove the notification.

**Android 12+/14 constraints [docs].**

- Android 12 (API 31) and higher: "can't start foreground services while the app is running in the background,
  except for a few special cases ... the system throws a `ForegroundServiceStartNotAllowedException`"; exemption:
  "your app transitions from a user-visible state, such as an activity"
  [`https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start`]. A share arrives by
  an activity start from a visible sender, so a start from `BrowserActivity`/`LauncherActivity` is allowed; a start
  from a background callback (the share handler's worker thread after 3 s waits, a timer) is not guaranteed.
  Since the service is already running this constraint is avoided entirely.
- Android 14 (API 34): foreground services must declare a type and, for `mediaProjection`, hold a MediaProjection
  grant; "If your app targets Android 14, it must specify appropriate foreground service types"
  [`https://developer.android.com/about/versions/14/changes/fgs-types-required`]. The manifest declares
  `android:foregroundServiceType="mediaProjection"` on the server service (`AndroidManifest.xml:87-90`) and
  `FOREGROUND_SERVICE_MEDIA_PROJECTION`; with `targetSdkVersion 33` this is not enforced. Raising the target to 34
  would make `showNotification()`'s `startForeground` fail without a projection (out of scope; note for the
  owner).
- The notification: `showNotification()` posts the "Yaacc UPnP Service" ongoing notification with status text,
  a close action, group `Yaacc` (`:380-425`); `POST_NOTIFICATIONS` is declared (`AndroidManifest.xml:12`), the
  runtime permission is requested by `BrowserActivitySupport.requestMissingPermissions` (code 101). A share
  that only registers an entry changes none of this.

### (f) The receiver picker

**How the sheet selects receivers today [code].**

- `DeviceSheetFragment` (`browser/DeviceSheetFragment.java`, a `BottomSheetDialogFragment`, opened only through
  `show(FragmentManager)` `:96-101`; `BrowserActivity.showReceivers()` `:227-229`) builds a
  `DeviceSheetAdapter(requireContext(), upnpClient)` (`:145`). The adapter lists `getDevicesProvidingAvTransportService()`
  (this device first, `UpnpClient.java:496-506`) as receiver rows plus player-session rows.
- Receiver rows are bound by `ReceiverRowBinder.bind(RowViews, device, selected, SelectionListener)`
  (`ReceiverRowBinder.java`); the checkbox click handler `:183-195` **writes through immediately**:
  `upnpClient.removeReceiverDevice(device)` or `addReceiverDevice(device)`, then calls the listener. The initial
  checked state is read in `DeviceSheetAdapter.onBindViewHolder`: `upnpClient.getReceiverDeviceIds()
  .contains(deviceId)` (`:306-307`). The adapter's `selectionListener` is a no-op (`:97-98`) because the client
  already fires the events.
- A row tap opens the player activity for that device (`DeviceSheetAdapter.java:269-272`,
  `ReceiverRowBinder.openPlayerActivity`); transport buttons, volume and mute live in the same row.
- `ReceiverBarController` (new layout only) shows `upnpClient.getReceiverDevices()` names
  (`ReceiverBarController.java:111-121`) and refreshes **only** on `receiverDeviceAdded/Removed`
  (`:149-158`); the bar opens the sheet. The classic `ContentListFragment` footer shows the same list and does
  not open the sheet.

**What a pick mode needs.**

1. *Entry point and result.* `BrowserHost` (`BrowserHost.java`) gets a method such as
   `void pickReceivers(ReceiverPicker.Callback callback)`; `BrowserActivity` implements it by opening the sheet in
   pick mode; `TabBrowserActivity` reports "not supported" (classic keeps its selection, so `ContentListFragment`
   calls the host only when `providesReceiverBar()`). Deliver the result with the fragment-result API:
   `FragmentManager.setFragmentResult(requestKey, Bundle)` from the sheet and
   `setFragmentResultListener(requestKey, lifecycleOwner, listener)` in the activity; "if you set a result without
   a corresponding listener, the result is stored in the FragmentManager until you set a listener with the same
   key" [docs: `https://developer.android.com/guide/fragments/communicate`]; `fragment:1.5.4` has it
   (`tech.md` "Classic and new layout"). The bundle carries `confirmed` and the chosen ids as
   `ArrayList<String>`; the pending action (a lambda over the item/URI) cannot be parceled, so keep it in an
   activity field or `ViewModel`: after a rotation it survives, after process death it is dropped and a result
   without a pending action is ignored.
2. *Sheet.* Argument `pick=true` + request key; a title view "Play on..." (new string, 7 locales, heading for
   accessibility), a confirm button (accessible name, D-pad focusable, enabled or not by the "no receiver"
   rule), cancel = back / outside tap / swipe. Report cancel from `onDismiss`/`onCancel` unless a confirm was
   sent (`DialogFragment.onDismiss` runs in every case). In pick mode: session rows hidden, row tap toggles the
   checkbox (it must not start another activity, which would stop the sheet), no `RendererStatusMonitor` change
   (reuse the shared monitor, tasks.md constraint "no second status poller").
3. *Selection must not write through.* `ReceiverRowBinder`'s checkbox click calls `UpnpClient` directly; a
   cancelled pick must leave the stored selection as it was, so pick mode needs a sink: either a constructor
   flag / second `bind` overload whose click handler only calls the `SelectionListener`, with the adapter keeping
   a local `Set<String> picked` (initially empty, since the selection is cleared after every action) and
   answering `selected` from it. The alternative (snapshot the stored ids when the pick opens and restore on
   cancel) is worse: the bar flickers and a crash in between leaves a changed selection.
4. *Confirm.* `ReceiverPickResult.decide(confirmed, ids)` (skeleton) says whether to start, to clear afterwards, or
   to keep the picker open when nothing is ticked.
5. *Starting the action.* Option 1 (design): `upnpClient.setReceiverDeviceIds(ids)`, start, then clear. Option 2
   (cleaner, optional): an overload `PlayerService.createPlayer(upnpClient, items, Collection<Device>)` and
   `UpnpClient.initializePlayers(..., receivers)` that take the receivers explicitly, so the global selection is
   never written and "clearing" is free; the cost is touching `createPlayer`'s two `getReceiverDevices()` reads
   (`PlayerService.java:322,331`). Either way, the per-receiver IP set for the share is computed from the same
   device list.

**Where the actions start playback and read the receivers** (state before the build; the `openDeviceSheetIfNoReceiverSelected()` guard was replaced by `ContentListFragment#startPlayAction`, which opens the picker in the new layout):

| Entry | Code | Receivers read at |
|---|---|---|
| Row tap on an item | `ContentListClickListener.onClick` (`:73-90`): `openDeviceSheetIfNoReceiverSelected()` guard, then `ContentItemPlayTask` with `PLAY_CURRENT` (video) or `PLAY_ALL` | `PlayerService.createPlayer` (`:322,331`) |
| Play button | `BrowseContentItemAdapter.java:587-590` (same guard) -> `PLAY_CURRENT` | same |
| Play-all button | `BrowseContentItemAdapter.java:581-584` -> `PLAY_ALL` | same |
| Add to playlist | `BrowseContentItemAdapter.java:593-594` -> `UpnpClient.addToPlaylist` (`:1593`), no guard | `getReceiverDevices()` |
| Share | `ShareIntentHandler.handle`/`prepareAndPlay`/`play` | `getReceiverDevicesReadyCount()` (`:110,156`), then `createPlayer` |

`ContentListFragment.openDeviceSheetIfNoReceiverSelected()` (`:435-444`) only fires when the selection is empty and
`providesReceiverBar()`; with "clear after every action" the selection is always empty in the new layout, so the
guard becomes the pick call itself (three call sites). All play variants end in `ContentListFragment.play(List<Player>)`
(`:865-871`), so it is the single place to run "after the players have started".

**Where to clear the selection.** After `player.play()` has been called for all players: in
`ContentListFragment.play` (on the `AsyncTask` background thread; the preference write is thread-safe) and in
`ShareIntentHandler.play` (main thread, after the loop at `:174-177`). `UpnpClient.setReceiverDeviceIds(emptySet)`
alone is not enough:

- an empty stored set is returned as is by `getStringSet` (`SharedPreferencesImpl.getStringSet`: `v != null ? v :
  defValues`, `SharedPreferencesImpl.java:309-314` [aosp]; `putStringSet` stores a copy of an empty set,
  `:422-427`), so `getReceiverDeviceIds()` becomes empty and the default `{LOCAL_UID}` does **not** come back
  (it applies only if the key is absent);
- no event fires, so `ReceiverBarController` keeps showing the old names. Add a public
  `UpnpClient.clearReceiverDevices()` that stores the empty set and calls `fireReceiverDeviceRemoved` for each
  previously selected device (or a new `receiverSelectionCleared()` listener method; the listener interface has
  five methods today, `UpnpClientListener.java:29-40`);
- `ShareIntentHandler.prepareAndPlay`'s "no receiver ready -> set `LOCAL_UID`" fallback (`:156-159`) is replaced in
  the new layout by the picker; classic keeps it.

**Share flow** (as amended by the Group 12 security review; as built in both layouts, see "As built: from the share menu to a pending
share" and "As built: the receiver picker"): `handle` validates and keeps a
PENDING share that holds only the validated URI, the cleaned display name, a token and the intent's read grant;
no descriptor, no copy and no registry slot exist yet. There is at most one pending share, it times out, and a new
share while one is pending is dropped. The host shows the picker. Only on confirm are the descriptor opened (the
grant is valid while the receiving activity record lives), the addresses of the chosen devices computed, the
registry entry created and the item built and played; then the selection is cleared. On cancel or timeout the
pending share is simply released (nothing was opened). In classic the same pending share is confirmed through the
same pick-mode sheet (cleaned file name in the title; the classic selection is the initial tick state and is restored after the
playback has started). `getType` and the display
name are read on a worker thread with a timeout (a hostile provider must not hang the main thread before the
user has confirmed anything).

### (g) Recommended API for the Group 12 red tests (package `de.yaacc.share`, plain Java)

> **Status (2026-10-25, review of Group 12; superseded by the as-built code, see "As built" above):** the red tests were written against a slightly different API than the sketch below, and the tests are the contract: `ByteRangeResolver.parseHeader(String)` + `resolve(List<Range>, long)` returning `Result` (`status()`, `start()`, `endInclusive()`, `length()`, `contentRangeHeader()`) instead of `HttpRange`/`Resolution`; more than one range is served as 200 whole (not "first range only"); the launcher glue is `LauncherForwardPolicy.decideStream(...)` returning a `StreamDecision` instead of an 8-argument `decide`; `ReceiverPickResult.Decision` has an extra `keepPickerOpen()`. Where the sketch and the tests differ, follow the tests.

The four classes **already exist as red stubs** in `yaacc/src/main/java/de/yaacc/share/` with tests in
`yaacc/src/test/java/de/yaacc/share/` (committed before this research finished; not touched here). The API below
is what the research supports; where it differs from the stubs it is marked **change**.

```java
public final class ShareRegistry {
    public static final int DEFAULT_MAX_ACTIVE = 8;          // ok
    public static final int KEY_BYTES = 16;                  // ok: 128 bit, 22 chars base64url without padding
    public enum Kind { HTTP_LINK, LOCAL_FILE }               // ok
    public static final class Entry {                        // ok, immutable
        public Entry(Kind kind, Object target, String mimeType, Set<String> allowedAddresses,
                     long idleGraceMillis, long absoluteMaxMillis) // IllegalArgumentException on null kind/target, grace<0, max<=0
        // getKind(), getTarget(), getMimeType(), getAllowedAddresses() (unmodifiable copy), getIdleGraceMillis(), getAbsoluteMaxMillis()
    }
    public ShareRegistry(LongSupplier nowMillis, Consumer<byte[]> randomBytes, int maxActive);
    public ShareRegistry(LongSupplier nowMillis, Consumer<byte[]> randomBytes);
    public static ShareRegistry createDefault();             // SystemClock.elapsedRealtime-like monotonic clock + SecureRandom
    public String register(Entry entry);                     // null when full / no free key; caller keeps ownership of the target then
    public Entry lookup(String key);                         // null for unknown, garbage, expired, released+grace over
    public boolean markActive(String key);
    public void release(String key);                         // first release starts the grace period
    public boolean remove(String key);                       // closes an AutoCloseable target once
    public int expire();
    public void removeAll();
    public int size();
    // change (add): format check used by the handler before lookup, no registry state needed
    public static boolean isWellFormedKey(String key);       // exactly 22 chars of [A-Za-z0-9_-]
}
```

- Keep `createDefault()` out of JVM tests (a monotonic clock that does not use `android.*`:
  `System.nanoTime() / 1_000_000` is JVM and Android safe and monotonic; `SystemClock.elapsedRealtime()` would
  break the "no `android.*`" constraint).
- The LOCAL_FILE target on the Android side is a class outside these four (e.g. `SharedFileSource implements
  AutoCloseable`: `long length()`, `int readAt(long position, byte[] buf, int off, int len)`, `close()` with the
  read/close lock described in (b), deleting a copy file on close). It needs no JVM test beyond a fake backed by
  a byte array.

```java
public final class ProxyAccessPolicy {
    public static boolean isAllowed(boolean restrictToReceivers, Set<String> allowedAddresses, String remoteAddress);
    public static String normalize(String address);          // see the stub's Javadoc: zone stripped, IPv4-mapped -> IPv4, IPv6 canonical
    // change (clarify): isAllowed normalizes BOTH the remote address and every allowed element before comparing,
    //   so the glue can add raw strings; elements that do not normalize are ignored.
    // change (add): for the glue that builds the allowed set from a URL host or getHostAddress()
    public static Set<String> normalizeAll(Collection<String> rawAddresses);   // drops nulls/invalid, strips one pair of [ ]
}
```

```java
public final class SharedContentPolicy {
    public static final int MAX_URI_LENGTH = 2048;
    public static final int MAX_MIME_LENGTH = 255;
    public static final long MAX_COPY_BYTES = 512L * 1024L * 1024L;
    public static boolean isAcceptableUri(String uriString, String ownAuthority);
    public static boolean isAcceptableMimeType(String mime);
    public static long maxCopyBytes();
    public static boolean isWithinCopyLimit(long sizeBytes);
    // change (add): the order from (a): provider type if acceptable, else a concrete intent type, else null
    public static String chooseMimeType(String providerType, String intentType);
}
```

```java
public final class ReceiverPickResult {
    public static Decision decide(boolean confirmed, Set<String> chosenReceiverIds);
    // Decision: startPlayback(), clearSelectionAfterStart(), keepPickerOpen(), receiversToUse()  (as in the stub)
}
```

Recommended **additional** pure classes for the red tests (the riskiest logic of Groups 13 and 14 is not covered by
the four above):

```java
// Group 13: one source of truth for status, Content-Range header and the bytes to send
public final class ByteRangeResolver {                       // package de.yaacc.share or de.yaacc.util; HttpRange is pure Java
    public static final class Resolution {                   // immutable
        public int status();            // 200, 206 or 416
        public long start();            // inclusive, valid for 200 and 206
        public long endInclusive();     // valid for 200 and 206
        public long length();           // bytes to send
        public String contentRange();   // "bytes s-e/len" for 206, "bytes */len" for 416, else null
    }
    public static Resolution resolve(List<HttpRange> ranges, long totalLength); // never throws; null/empty list -> 200 whole; first range only
}

// Group 14: extends the existing launcher decision without changing the three-argument decide(...)
public final class LauncherForwardPolicy {                   // existing class, de.yaacc.browser
    public static Decision decide(String action, String type, String text);   // unchanged
    public static Decision decide(String action, String type, String text,
                                  String streamUri, String clipItemZeroUri, int clipItemCount,
                                  boolean hasReadGrantFlag, String ownAuthority);
    // Decision gains: isSendStream(), getStreamUri(), getStreamType()
    //   stream forward only if: action SEND, type is audio/*, video/*, image/* (or concrete), count == 1,
    //   clip item 0 equals the stream URI, the read flag was present, SharedContentPolicy.isAcceptableUri(...).
}
```

Test cases the research adds beyond the stubs: `ProxyAccessPolicy` with a bracketed IPv6 and a zone; the
`ByteRangeResolver` table (no header, `0-`, `0-0`, `10-5`, `-5`, `-0`, `-9999`, `start == length`, multi-range,
length 0); `ShareRegistry.isWellFormedKey` against `"../x"`, 21/23 chars, `+/=`; `SharedContentPolicy.chooseMimeType`
(provider wins, provider `application/octet-stream` falls back, wildcard intent type rejected).

### What could NOT be verified without a device

1. The whole chain sender -> `LauncherActivity` -> `TabBrowserActivity`/`BrowserActivity` with a real grant on
   Android 12-16, with real senders (Google Photos, Files, Gallery, VLC, a messenger): that the forward is not
   rejected, that the grant is usable for the whole browser lifetime, and what a `SecurityException` at the
   forward looks like for providers that forbid re-granting. Only Android 14 source was read.
2. Whether a descriptor opened under a grant keeps working after the grant ends, after the sender task ends and
   after the sender deletes the file, for MediaStore, FileProvider, Google Photos/Drive, SAF providers and
   FUSE-passthrough storage (the source argument in (b) is reasoned).
3. Which providers return pipes or sockets for mode `"r"`, which return sub-sections (`startOffset != 0`), and
   what `fstat`/`lseek` say for `DocumentsProvider` proxy descriptors; speed and blocking of `pread` on those.
4. The stable-provider-reference kill behavior and the dup-then-close mitigation: kill the sender while a share
   streams.
5. The type and form of `EndpointDetails#getRemoteAddress()` on a real device (`InetSocketAddress`; IPv4 shown as
   IPv4 or IPv4-mapped; IPv6 scope), and the loopback/own-address cases for the in-app player.
6. The address real renderers fetch from (Samsung/LG/Sony TVs, Kodi, BubbleUPnP, multi-homed devices) compared with
   the SSDP `LOCATION` host; whether `HEAD`, 206, 416 and `Connection: close` are handled by them.
7. The effect of the `stopService` + bound service on the notification and process priority when the local-server
   preference is off, and whether the process survives screen-off during a long local-file stream (WifiLock,
   Doze); the reactor's behavior with eight I/O threads.
8. Android 15 and 16 changes to URI grants, foreground-service starts and `MediaProvider`; only Android 14 sources
   and current developer.android.com pages were read.
9. Picker UX: rotation with the sheet open, TalkBack and D-pad on TV, cancel by back, and the pending-action
   handling across process death.
10. `MediaDataSource` metadata extraction on API 27 for the formats users share; the copy fallback's real speed
    and the cache-eviction behavior under low storage.

### Sources

- developer.android.com: `reference/android/content/Intent` (`ACTION_SEND`, `FLAG_GRANT_READ_URI_PERMISSION`,
  `setClipData`), `reference/androidx/core/content/IntentCompat`, `guide/topics/manifest/data-element`,
  `training/secure-file-sharing/share-file`, `reference/android/content/ContentResolver`
  (`openFileDescriptor`, `getType`, `acquireUnstableContentProviderClient`), `reference/android/os/ParcelFileDescriptor`,
  `reference/android/system/Os`, `reference/java/nio/channels/FileChannel`, `reference/android/content/Context#stopService`,
  `reference/android/media/MediaMetadataRetriever`, `guide/fragments/communicate`,
  `develop/background-work/services/fgs/restrictions-bg-start`, `about/versions/14/changes/fgs-types-required`.
- AOSP `platform/frameworks/base` `android-14.0.0_r1`: `services/core/java/com/android/server/uri/{UriGrantsManagerService,UriPermission,UriPermissionOwner}.java`,
  `services/core/java/com/android/server/wm/{ActivityStarter,ActivityRecord}.java`,
  `core/java/android/content/{Intent,ContentResolver,ContentProvider,SharedPreferences}.java`,
  `core/java/android/content/res/AssetFileDescriptor.java`, `core/java/android/os/ParcelFileDescriptor.java`,
  `core/java/android/app/{Instrumentation,SharedPreferencesImpl}.java`.
- libcore (LineageOS `android_libcore`, `lineage-21.0`): `ojluni/.../java/io/FileInputStream.java`,
  `ojluni/.../java/nio/channels/FileChannel.java`, `ojluni/.../sun/nio/ch/{FileChannelImpl,IOUtil,FileDispatcherImpl}.java`,
  `luni/.../android/system/Os.java`.
- Apache HttpComponents Core 5.3.6 sources (Maven Central): `HttpCoreContext`, `EndpointDetails`,
  `BasicEndpointDetails`, `ServerHttp1StreamDuplexer`, `ServerHttp1StreamHandler`, `AbstractHttp1StreamDuplexer`,
  `AbstractBinAsyncEntityProducer`, `ServerH2StreamMultiplexer`.
