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
  and the adapter posts `checkBox.requestFocus()` when it binds the first
  receiver row. Stable ids (`R:<udn>` / `P:<playerId>` keys) and `DiffUtil`
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

- **Receiver bar.** `contentListCurrentProvider` is gone from both
  `fragment_content_list.xml` layouts; the server name now only starts the
  path label. `contentListCurrentReceivers` is the receiver bar: clickable,
  D-pad focusable, never `GONE` (views above anchor to it). Its text comes
  from `ReceiverBarLabel.build` (selected receivers' friendly names, trimmed,
  sorted case-insensitively, joined with `"; "`, or
  `receiver_bar_none_selected`), refreshed whenever `UpnpClient` reports a
  receiver selection change, including changes made in the sheet. A tap calls
  `ContentListFragment#showDeviceSheet()`.
- **No-receiver prompt.** Play, play-all (`BrowseContentItemAdapter`) and an
  item tap (`ContentListClickListener`) call
  `ContentListFragment#openDeviceSheetIfNoReceiverSelected()` first; with no
  receiver selected it opens the sheet and playback doesn't start, so the
  user picks a receiver and taps play again. "Add to playlist" isn't gated.
  The `error_no_receiver_selected` toast in `PlayerService.createPlayer`
  stays for non-UI callers.
- **Sheet contents.** `DeviceSheetFragment.show(FragmentManager)` does
  nothing if the sheet is already showing or the state is saved.
  `DeviceSheetAdapter#refresh()` takes the receivers from
  `getDevicesProvidingAvTransportService()` (the Receiver tab's source
  order: local device first, then by name), resolves each by id through
  `UpnpClient.getDevice(id)`, and lays the rows out with
  `DeviceSessionGrouper.group`. Receiver rows are bound by
  `ReceiverRowBinder`, the same binder the Receiver tab's
  `BrowseReceiverDeviceAdapter` uses. Session rows
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
- **Known limitations (follow-ups in the spec's decisions.md).** The sheet
  doesn't add or remove receivers that appear or vanish on the network while
  it is open (known receivers' status does update). After rotation it returns
  to its initial half/full position. `RendererStatusMonitor` polls on a
  single thread, so one slow renderer delays status for every receiver in
  both the tab and the sheet; transport controls are unaffected.

## Classic and new layout

Research notes for spec `2026-10-23-unified-device-sheet`, Phase 2 (Group 5,
first task). No code was written for this section. Verified 2026-10-24
against the checkout on branch `feat/unified-device-sheet`. Line numbers are
from that checkout and will move once Group 6 starts.

Versions (nothing added): `yaacc/build.gradle:109-111` has `targetSdkVersion
33`, `minSdkVersion 27`. `appcompat:1.8.0` (`yaacc/build.gradle:53`) resolves
`androidx.activity:activity:1.8.0`, `androidx.fragment:fragment:1.5.4` and
`androidx.core:core:1.16.0` (`./gradlew --offline :yaacc:dependencies
--configuration debugRuntimeClasspath`). `androidx.preference:preference:1.2.1`
and `material:1.14.0` are unchanged.

Sources, and how each claim is marked:

- **[code]**: read in this repo, cited as `file:line`.
- **[javap]**: `javap -public` on `activity-1.8.0.aar` from
  `~/.gradle/caches/modules-2/files-2.1/androidx.activity/activity/1.8.0/`.
- **[docs]**: official documentation, fetched through the `android-docs` MCP
  (it worked in this session) or `curl` on developer.android.com. URL given.
- **[aosp]**: AOSP source, tag `android-14.0.0_r1`, fetched from
  `raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/`
  (`android.googlesource.com` was not reachable). Platform behavior that the
  public docs do not state. Newer releases may differ.
- **[reasoned]**: inferred from code or the sources above, not run on a device.
  Section "On-device checks" lists what the owner should confirm.

### Summary of recommendations

| Topic | Recommendation |
|---|---|
| `BrowserHost` | Four methods: `showServers()`, `showContent()`, `showReceivers()`, `showPlayers()`. Optional `default` capability methods for Group 8. Section (a). |
| `getParent()` | Always `null` here. Four of the five fragment sites are **dead code** today. Section (a). |
| Back in the new activity | `OnBackPressedCallback` on `getOnBackPressedDispatcher()` (verified in activity 1.8.0). Not an `onBackPressed()` override. Section (b). |
| Layout change relaunch | Check-on-resume: each browser activity compares the stored layout with its own in `onResume()` and, on mismatch, starts the other and finishes. Section (c). |
| Shutdown timer | Extract `ShutdownTimerController`; give `Yaacc` an `isShutdownTimerRunning()` and remaining-millis getter. Section (d). |
| Share handler | Extract `ShareIntentHandler` with a `Runnable onPlayersStarted`; classic passes `showPlayers()`, new passes nothing. Section (e). |
| Trampoline | Works. `LauncherActivity extends android.app.Activity` (not AppCompat), `Theme.NoDisplay`, forward a copy with `setComponent`, call `finish()` in `onCreate`. Section (f). |
| Manifest | MAIN filters, LEANBACK and SEND move to `LauncherActivity`. Section (g). |

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

| # | Site | Trigger | Wants from host | Live today? |
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

#### Proposed `BrowserHost`

```java
public interface BrowserHost {
    void showServers();    // sites 3 (and the new layout's own up-navigation)
    void showContent();    // sites 2, 5 (and 4 if ever kept)
    void showReceivers();  // site 1
    void showPlayers();    // site 8
}
```

Fragments find the host with `getActivity() instanceof BrowserHost`;
`ServerListClickListener` with `parent instanceof BrowserHost`. Both have to
tolerate "no host" (as today: nothing happens). Calls can come from non-UI
threads (`deviceAdded` is a `UpnpClientListener` callback), so every
implementation must hop to the main thread itself.

| Method | Classic (`TabBrowserActivity`) | New (`BrowserActivity`) |
|---|---|---|
| `showServers()` | `setCurrentTab(SERVER)` | pop the back stack down to the server-list fragment; no-op if already there |
| `showContent()` | `setCurrentTab(CONTENT)` | push `ContentListFragment` for the current provider (`addToBackStack`); no-op if it is already on top. The provider is already stored by `UpnpClient.setProviderDevice` (writes the preference, `UpnpClient.java:1127-1131`), and `ContentListFragment` loads it itself in `onViewCreated` and `onResume` (`ContentListFragment.java:91-108, 110-146`), so no argument is needed. |
| `showReceivers()` | `setCurrentTab(RECEIVER)` | open `DeviceSheetFragment.show(getSupportFragmentManager())` (`DeviceSheetFragment.show` already ignores a second call or a saved state) |
| `showPlayers()` | `setCurrentTab(PLAYER)` | no-op. The mini-player updates itself from `PlayerListChangeNotifier`. Optionally open the featured player screen later. |

Optional `default` methods for Group 8, so classic needs no override:
`default boolean providesReceiverBar() { return false; }` (new returns
`true`, `ContentListFragment` then hides its own bar) and
`default boolean providesShutdownTimer() { return false; }` (new returns
`true`, `ServerListFragment` hides its timer row).

New-layout fragment transactions must run on the main thread and not after
`onSaveInstanceState`: guard with `isStateSaved()` (fragment 1.5.4) or skip.

#### Decision for Group 6: the four dead sites

The Group 6 Accept says "classic behavior is unchanged (same tab
switches)". Wiring sites 1-4 to `BrowserHost` would make them **live** and
change classic: back on the Player tab would go to Receiver, Receiver to
Content, Content root to Server (today all three are swallowed no-ops), and
site 4 would yank the user to the Content tab whenever the selected provider
announces itself again. Recommendation:

- Sites 5 and 8 go through `BrowserHost` (they are live).
- Sites 1-3: keep `return true` and drop the dead `getParent()` branch, or
  leave them exactly as is. Do **not** wire them, so classic stays unchanged.
  Making classic back work properly is a behavior change for the owner to
  decide separately (CHANGELOG "Fixed").
- Site 4: delete the dead branch. Do not wire it; the new layout starts at the
  server list and must not auto-jump.
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

#### What `BrowserActivity` must do

- **API is available in appcompat 1.8.0 / activity 1.8.0 [javap]**:
  `ComponentActivity.getOnBackPressedDispatcher()` is `public final`;
  `OnBackPressedDispatcher.addCallback(LifecycleOwner, OnBackPressedCallback)`,
  `addCallback(OnBackPressedCallback)`, `hasEnabledCallbacks()` exist;
  `OnBackPressedCallback(boolean)`, `setEnabled`, `isEnabled`, `remove`,
  `handleOnBackPressed()` (abstract) exist. API reference:
  `https://developer.android.com/reference/androidx/activity/OnBackPressedDispatcher`
  (that page documents the latest release; the signatures used here are
  "Added in 1.0.0" and were checked against the 1.8.0 jar).
- Use `getOnBackPressedDispatcher().addCallback(this, callback)` once in
  `onCreate`. The docs say a callback registered with a `LifecycleOwner`
  follows its lifecycle and is removed on `DESTROYED`, so nothing leaks.
  Register it once and toggle `setEnabled(...)` rather than add/remove.
- Do not override `onBackPressed()`. The docs state that from API 33 back
  handling moves to an ahead-of-time model and `Activity.onBackPressed()`
  "should not be used" [docs:
  `https://developer.android.com/reference/android/app/Activity#onBackPressed()`].
  The classic override still works for this app because `targetSdkVersion` is
  33 and the manifest has no `android:enableOnBackInvokedCallback`
  [code: `build.gradle:110`, `AndroidManifest.xml`]; the new activity should
  not depend on that. Do not set `enableOnBackInvokedCallback` as part of this
  work (not researched, and it would change classic).
- Required behavior (callback body), in order:
  1. If the top fragment is `ContentListFragment` and it is **not** at its
     root: let it pop one folder (`onBackPressed()`).
  2. If it is at its root: `showServers()` (pop the back stack).
  3. If the top fragment is the server list: same as classic,
     `Yaacc.exit()` then `finish()`.
- `ContentListFragment` needs a new read-only `isAtRoot()` (derive from
  `navigator`, null-safe, same expression as `updateFolderNavigationUi`
  `:475-476`). It cannot be inferred from `onBackPressed()`'s return value:
  that always returns `true`, and changing it would change classic.
- New layout wants the in-screen back arrow **visible at the content root**
  (design.md mock-up "<- Gerbera > Musik"), where classic hides it. Gate that
  on `BrowserHost` (e.g. a default method `showsUpAtContentRoot()`), and have
  the arrow call `showServers()` at the root.
- Do not re-use the `getFragments().get(tabPosition)` indexing.
- D-pad / TV: system Back arrives as the same back event; nothing special.

### (c) The `leftSettings` round trip

#### What happens today [code]

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

#### What the new activity needs

- The `onResume()` server-service logic (`:361-374`) must run in both
  activities. Extract it, e.g. `LocalServerLifecycle.apply(Context,
  boolean restart)`; both `onResume`s call it with the consumed flag.
- The flag must be shared. Move it (or a `consumeLeftSettings()` accessor) to
  `SettingsActivity` so neither browser depends on the other; keep it a
  main-thread static (all writers and readers are lifecycle callbacks).
- The activity-level pieces that classic keeps in `TabBrowserActivity` and
  that the new activity also needs (shared helper or small base class):
  permission request (`:157-171, 210-214`, request code 101, result never
  handled), battery-optimization prompt (`:236-249`, started on **every**
  `onCreate`, and when already exempt it starts an empty `Intent()` and
  swallows `ActivityNotFoundException`), the 5-minute background idle exit
  (`:378-401`), the options menu actions (`:435-466`: exit, settings, about,
  log).
- **Latent bug the relaunch makes more visible**: the idle timer is a
  per-instance `Handler` + `Runnable` (`:378-395`). `onStop()` posts the
  5-minute exit (`:398-401`); `onResume()` cancels only **this instance's**
  callbacks (`:357`). After any recreation (rotation) or a relaunch, the old
  instance's runnable is still queued and, if there are no players and no
  local server/renderer/proxy enabled, calls `Yaacc.exit()` five minutes later
  even though the user is active in the new instance [reasoned]. The shared
  helper should keep one process-wide timer (static `Handler`, or a field on
  `Yaacc`) so any browser `onResume()` cancels it. Out of scope to fix in
  classic unless the owner asks; required for the new activity's design.

#### Relaunch when the Layout preference changes: recommendation

**Check on resume.** In both browser activities, at the very top of
`onResume()`, after `super.onResume()`:

```java
if (BrowserLayoutChoice.resolve(prefs.getString(layoutKey, null)) != THIS_LAYOUT) {
    startActivity(LaunchHelper.browserIntent(this));   // other layout's class
    overridePendingTransition(0, 0);
    finish();
    return;                                            // skip the rest of onResume
}
```

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
  `Yaacc.java:74` calls `PreferenceManager.setDefaultValues(this,
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

#### Shutdown timer [code]

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

Recommended extraction, `ShutdownTimerController` (name free; plain class
owned by whoever shows the UI, not a singleton):

```java
final class ShutdownTimerController implements ShutdownTimerListener {
    interface Display { void showRemaining(long millis, boolean running); }

    ShutdownTimerController(Yaacc app, SharedPreferences prefs, String prefKey);
    void attach(Display d);          // sets itself as Yaacc's listener, pushes current state
    void detach();                   // Yaacc.setShutdownTimerListener(null) if still ours
    boolean isRunning();             // from Yaacc (new)
    long configuredMillis();         // prefs.getLong(prefKey, 0L)
    void setConfiguredMillis(long);  // prefs.edit().putLong(...).apply(); restart if running
    void start();                    // app.startShutdownTimer(configuredMillis())
    void stop();                     // app.stopShutdownTimer()
    void showPicker(FragmentManager fm);  // MaterialTimePicker built from millis, not from a string
    @Override public void onTick(long remaining);  // -> display.showRemaining
}
```

and two additions to `Yaacc`: `boolean isShutdownTimerRunning()` and
`long getShutdownTimerRemainingMillis()` (set in `onTick`, cleared in
`stopShutdownTimer()`/`onFinish`). `attach`/`detach` pair with
`onStart`/`onStop` (or `onViewCreated`/`onDestroyView` for the classic
fragment) so the `Application` never holds a destroyed UI.

- Classic: `ServerListFragment` keeps its four views and delegates to the
  controller, with a `Display` that writes the text and the switch.
- New (`BrowserActivity`): a toolbar menu item (`app:showAsAction="ifRoom"`,
  checkable) whose title/tooltip shows the remaining time via a `Display`;
  click toggles start/stop like the switch; an overflow entry (or long press)
  calls `showPicker`. `ServerListFragment`/the new server fragment hides the
  timer row when `BrowserHost.providesShutdownTimer()` is true. The controller
  has to live in the activity (not the fragment) so the item keeps updating on
  every level.
- `MaterialTimePicker` is already used here (`ServerListFragment.java:20,278`)
  and `material:1.14.0` is unchanged.

#### MediaProjection permission flow [code]

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

What must move: **nothing has to move to the toolbar or the activity** for
streaming. The buttons stay on the yaacc row, and the row only exists in the
fragment that hosts `BrowseDeviceAdapter`; the permission result is delivered
to the fragment that started it. This contradicts Group 7's "MediaProjection
permission result is handled by the activity". Recommendation: the new
top-level server fragment handles it itself, sharing the code with classic
through an extracted helper:

```java
final class StreamingPermissionHelper {            // name free
    StreamingPermissionHelper(Context appContext, Runnable refreshAdapter);
    Intent createRequestIntent(Context c);          // MediaProjectionHelper.createPermissionIntent
    void onPermissionResult(int resultCode, Intent data);   // body of onActivityResult
    void registerStopCallback(Activity owner);      // runOnUiThread + prefs reset + refreshAdapter
    void unregisterStopCallback();                  // MediaProjectionHelper.setStopCallback(null)
}
```

Both fragments call `registerStopCallback` in `onStart` and
`unregisterStopCallback` in `onStop` (fixes the static leak). The static
`pending*` flags stay in `BrowseDeviceAdapter`; do not change their semantics.
Group 7 Accept should say "handled by the fragment that hosts the yaacc row".

### (e) Share-intent handler: `checkIfReceivedShareIntent`

`TabBrowserActivity.java:257-331`, called from `onCreate` with `null`
(`:232`, after the `upnpClient == null` early return at `:220-223`) and from
`onNewIntent` (`:251-255`).

Behavior [code]:

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

Recommended helper (behavior-preserving, keep both 3 s waits and the fallback
text exactly):

```java
final class ShareIntentHandler {
    ShareIntentHandler(Context appContext, UpnpClient upnpClient);
    /** Reads and clears ACTION_SEND extras/clip; no-op for other actions. */
    void handle(Intent intent, Runnable onPlayersStarted);
    void shutdown();     // shuts the executor down; call from onDestroy
}
```

- Classic: `handle(intent, () -> setCurrentTab(BrowserTabs.PLAYER))`, i.e.
  `BrowserHost.showPlayers()`.
- New: `handle(intent, null)`. The mini-player appears from
  `PlayerListChangeNotifier` events, so nothing else is required. Optionally
  `() -> showMiniPlayerHint()`; not needed for Group 6/7.
- Keep one executor per handler instance and `shutdown()` it (small leak fix
  that does not change visible behavior). The `IOException` -> `RuntimeException`
  crash is out of scope; note it for the follow-ups.
- The untrusted `EXTRA_TEXT` / clip text is only `Uri.parse`d and passed to
  `createPlayableItem`; never executed or used to build an `Intent`. Keep that.
- `onNewIntent` is effectively unreachable in classic: `TabBrowserActivity`
  has no `launchMode`, so a second `ACTION_SEND` starts a **new instance**
  rather than delivering to the running one (for a standard-mode activity
  whose component equals the task root, `ActivityStarter` takes the
  "same component" branch but only calls `deliverNewIntent` for
  `singleTop`/`FLAG_ACTIVITY_SINGLE_TOP`, otherwise it adds an instance when
  the intent filter differs from the task's base intent, [aosp:
  `ActivityStarter.java` `complyActivityFlags`, ~lines 2466-2492]
  [reasoned]). Keep the `onNewIntent` call for the explicit `SINGLE_TOP` case.

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
   carries the grant exactly like the original. Prefer the copy so the
   trampoline's own `getIntent()` is not mutated.

Recommended trampoline body (behavior only; Group 6 writes the code):

```java
public class LauncherActivity extends android.app.Activity {   // NOT AppCompatActivity
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Intent forward = new Intent(getIntent());      // copy: keeps action, extras, ClipData, flags
            forward.setComponent(new ComponentName(this, BrowserLayoutChoice.targetClass(this)));
            startActivity(forward);                        // add NO flags, invent NO extras
        } finally {
            finish();                                      // required before onResume completes
        }
    }
}
```

- `extends android.app.Activity`, not `AppCompatActivity`: `Theme.NoDisplay`
  is a framework theme, not an AppCompat theme, and the trampoline never
  inflates anything.
- Do not add or remove `FLAG_GRANT_*`. Keep the launcher's own
  `FLAG_ACTIVITY_*` bits as they arrive (the original flags are copied).
  Do not call `setSelector`/`setPackage`.
- Because the forward is explicit, `BrowserActivity` and `TabBrowserActivity`
  need no `<intent-filter>` and can be `exported="false"`.
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
and must be the **only** `MAIN`/`LAUNCHER` and `LEANBACK_LAUNCHER` component.
`TabBrowserActivity` and `BrowserActivity` then carry no filters and can be
`exported="false"`.

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
- Group 6 Accept ("`TabBrowserActivity` is `exported="false"` only if Group 5
  (g) shows nothing external needs it") is therefore satisfied: nothing
  in-repo needs it exported. Third-party launchers/automation that hard-code
  the component name (for example Tasker or home-screen shortcuts that were
  created from the launcher, which store the **launcher** component) cannot be
  excluded from here. A home-screen shortcut created before the change stores
  `TabBrowserActivity` as its component; making it non-exported would break
  such an existing shortcut. That is a user-visible risk of
  `exported="false"` that the repo cannot rule out [reasoned]. Safer option:
  leave `TabBrowserActivity` exported without filters for one release, and
  have its `onCreate`/`onResume` redirect when the setting says `new`.
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
- The only global back-references to UI are leaks to fix when extracting:
  `Yaacc.shutdownTimerListener` (fragment) and
  `MediaProjectionHelper.stopCallback` (fragment lambda), both described in
  (d), and the static `TabBrowserActivity.leftSettings` in (c).
- `Yaacc.exit()` finishes every app task (`getAppTasks().finishAndRemoveTask()`,
  `Yaacc.java:144`) then kills the process, so it works regardless of which
  browser class is on top.

### Contradictions between this research and the spec

1. **"Classic back flow goes content folder -> content root -> servers ->
   exit"** (tasks.md Group 7). Not true of the code: three of four back
   handlers are dead because `getParent()` is `null`. Only folder pop and
   server-tab exit work. Classic back on Content root, Receiver, Player does
   nothing. The new activity must implement the intended flow itself, and
   Group 6 must not turn the dead branches on (changes classic).
2. **"Classic behavior is unchanged" (Group 6) vs. "no `instanceof
   TabBrowserActivity` remains and each fragment asks its host"**. Wiring the
   four dead `getParent()` sites to `BrowserHost` would change classic. See
   the Group 6 decision in (a).
3. **"MediaProjection permission result is handled by the activity"**
   (Group 7). The adapter is hosted by a fragment and results route to the
   fragment; no move is needed. See (d).
4. **"`ShutdownTimerListener` wiring ... what must move to the toolbar"**:
   the timer UI moves to the toolbar, but the timer itself already lives in
   `Yaacc`; the missing piece is a running/remaining query on `Yaacc` and a
   detach path, not a move of timer logic.
5. **"Exactly one exported launcher component"** (Group 6 Verify): four other
   activities declare `category.LAUNCHER` without `MAIN`; read the check as
   `MAIN` + `LAUNCHER`/`LEANBACK_LAUNCHER`.
6. **`TabBrowserActivity` `exported="false"`** is safe inside this repo but may
   break pre-existing home-screen shortcuts that stored its component (g).
7. **"Layout change relaunch ... clearing the old one from the back stack"**
   (Group 9): the check-on-resume mechanism does this with `finish()`; no
   `CLEAR_TASK` flags are needed, and they would kill the playing task's
   other activities.

### Shared pieces the new activity needs (not listed in the spec)

Both browsers need the same, so extract once (Group 6/7):

- local-server restart in `onResume` and the shared `leftSettings` flag (c);
- one process-wide background idle timer (c);
- permission request + battery-optimization prompt from `onCreate` (c),
  ideally not repeated when the activity is only being relaunched for a layout
  change;
- options menu actions (exit, settings, about, log) from
  `R.menu.activity_main` (`res/menu/activity_main.xml`, ids `menu_exit`,
  `menu_settings`, `yaacc_about`, `yaacc_log`); the new menu adds the shutdown
  timer item;
- `ShareIntentHandler` (e), `ShutdownTimerController` and
  `StreamingPermissionHelper` (d).

### On-device checks (things only reasoned, not run)

1. Share a URL (`text/plain`, `EXTRA_TEXT`) and a `content://` text file to
   yaacc through the trampoline: playback starts, no `SecurityException`
   in logcat for the forwarded URI.
2. Tap the launcher icon while the app is already running: the existing task
   comes forward, no second browser instance.
3. Android TV launcher shows the app via `LEANBACK_LAUNCHER` on the trampoline
   with the same label and icon.
4. Change Layout in Settings, press back out of Settings: the other layout
   appears, players keep playing.
5. Tap an old notification after a layout change: lands in the chosen layout.
6. After a rotation, wait 5 minutes with no players and the local server off:
   confirm whether the app exits (latent idle-timer bug in (c)).
