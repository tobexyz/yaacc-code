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

Two browser activities share one app: the **classic** layout
(`TabBrowserActivity`: four tabs) and the **new** single-screen layout
(`BrowserActivity`: server list, then a server's content, with a receiver bar
and a mini-player on every level). The "Layout" setting picks one. This section
records the layout as built for spec `2026-10-23-unified-device-sheet`, Phase 2
(Groups 5-9), verified 2026-10-24 against the checkout at `e312ee8b` on branch
`feat/unified-device-sheet`, followed by the Group 5 research it was built
from (kept for its citations). "As built" subsections cite classes, not line
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
| `ContentListFragment` | Content of one server for both layouts. |
| `BrowserBackNavigation` | Plain Java: back decision of the new layout. |
| `ShareIntentHandler`, `ShareUriPolicy` | Shared handler for a link shared into yaacc. |
| `ShutdownTimerController`, `ShutdownTimerToggle` | Shutdown timer UI logic and the toolbar tap decision. |
| `StreamingPermissionHelper` | MediaProjection permission flow of the yaacc server row. |
| `BackgroundIdleTimer`, `StartedBrowserCounter` | One process-wide idle exit. |
| `ReceiverBarController`, `ReceiverBarLabel` | Receiver bar binding and label. |
| `MiniPlayerBar`, `MiniPlayerPresenter`, `MiniPlayerPicker` | Mini-player. |
| `de.yaacc.upnp.server.LocalServerLifecycle` | Starts or stops `YaaccUpnpServerService` from the preferences; both browsers call it from `onResume`. |

### As built: `BrowserHost`

```java
public interface BrowserHost {
    void showServers();
    void showContent();
    void showReceivers();
    void showPlayers();
    default boolean providesShutdownTimer()  { return false; }  // toolbar owns the timer
    default boolean providesReceiverBar()    { return false; }  // activity owns the receiver bar
    default boolean showsBackAtContentRoot() { return false; }  // back arrow also at a server's root
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
`LEANBACK_LAUNCHER`, and `SEND` / `DEFAULT` with `text/plain`. `onCreate`
builds a **fresh** `Intent(this, BrowserLauncher.targetClass(this))`, applies
the forward policy, calls `startActivity` and `finish()` in `finally`
(`Theme.NoDisplay` requires `finish()` before `onResume` completes). Any
`RuntimeException` while reading the incoming intent is caught, logged by
class name only, and the launch is plain.

**Forward policy** (`LauncherForwardPolicy.decide(action, type, text)`, plain
Java, compared exactly and case-sensitively):

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
listener) and again in `onResume`. Consequence of the order: a share that
reaches a `TabBrowserActivity` whose layout is no longer the effective one is
handled by its `onCreate` handler and still plays (the graceful shutdown lets it
finish), while the same share reaching a stale `BrowserActivity` is dropped.
The launcher normally starts the right class, so this only affects stale
explicit launches. A switch does not touch players, `PlayerService` or the UPnP
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
  and its title is the remaining time), overflow "Set shutdown timer"
  (`menu_shutdown_timer_set`, opens the picker), settings, log, about, and an
  always-visible exit item. The item is updated in place (the timer ticks every
  second), not through `invalidateOptionsMenu()`. The controller lives in the
  activity (attach in `onStart`, detach in `onStop`) so the item keeps updating
  on every level.
- **Timer tap** (`ShutdownTimerToggle.decide(running, configuredMillis)`):
  running: stop; not running and a duration is configured: start; otherwise open
  the picker (starting with 0 ms would call `Yaacc.exit()` at once). The picker
  stores the duration; it restarts a running timer.
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
  `Yaacc`, the classic Server tab shows a running timer after rotation or tab
  re-creation (it showed blank before).
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

- Only `ACTION_SEND`. Text source: `EXTRA_TEXT`, else the text of the last
  `ClipData` item that has text and is at most 2048 chars. A clip item's URI is
  never used (before Phase 2 it was).
- The text is removed from the intent (`removeExtra(EXTRA_TEXT)`,
  `setClipData(null)`) so a recreated activity does not replay it. After
  process death the system re-delivers the original intent and the link plays
  again (as in classic before).
- **Scheme policy.** `ShareUriPolicy.isAllowed(text)`: non-empty, at most 2048
  chars, and the part before the first `':'` equals `http` or `https`
  (ASCII, case-insensitive). Nothing is trimmed. Reason: with the proxy option on,
  a `file://` URI or bare path would be served to the whole LAN through
  `/proxy/<hash>`. The check looks only at the scheme and length, so `http:` and
  `http://a\nfile:///x` pass (pinned by `ShareUriPolicyTest#onlyTheSchemeAndLengthAreChecked`;
  tightening is Backlog 1 item 8). A rejected text is only logged, with no
  payload. The text is never used to build an intent, PendingIntent or file path.
- **Waits and fallback** unchanged: 0 s; +3 s when no receiver is ready; +3 s
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

### As built: receiver bar

`ReceiverBarController(TextView bar, UpnpClient, Runnable openSheet)` is shared
by the classic `ContentListFragment` and `BrowserActivity`. `bind()` tints the
compound drawables, sets the click listener (tap or D-pad select opens the
sheet), an accessibility delegate (click action "Choose receivers") and adds
itself as `UpnpClientListener`; `unbind()` removes all of it. It refreshes
through `receiverDeviceAdded` / `receiverDeviceRemoved` (hopping to the main
thread) and once on every `bind()`; late events after `unbind()` are ignored.
The label is `ReceiverBarLabel.build` (trimmed friendly names, case-insensitive
sort, joined with `"; "`, else `receiver_bar_none_selected`), set with
`setText`. In the new layout `BrowserActivity` owns one bar below the container
(`activity_browser.xml`: separator, mini-player, receiver bar in a vertical
`LinearLayout`, so a hidden mini-player cannot move an anchor) and binds it in
`onStart`/unbinds in `onStop` (and `onDestroy` as a backstop). Its
`ContentListFragment` hides its own bar when `providesReceiverBar()` is true and
opens the sheet through `showReceivers()`. Classic's portrait content layout
wraps the separator and the bar in a never-`GONE` `contentListBottomBar` that
the list anchors to. The no-receiver prompt
(`openDeviceSheetIfNoReceiverSelected`) works from both layouts and is called
only from item/play taps.

### As built: mini-player

`MiniPlayerBar` (`mini_player_bar.xml`, a `MaterialCardView` included above the
receiver bar, `GONE` while no player exists) is bound by `BrowserActivity`
between `onStart` and `onStop`.

- **Featured session.** `MiniPlayerPicker.pick(List<PlayerRef>)`: a playing
  session beats a non-playing one; then the higher creation sequence
  (`UpnpClient#getPlayerCreationSequence`, assigned by `PlayerService` per added
  player); then the higher player id; null entries are skipped; the list is not
  modified. Same ordering as `DeviceSessionGrouper.primarySession`, without the
  device filter. `refresh()` hands it `PlayerRef` copies built from an
  `ArrayList` copy of `getCurrentPlayers()` and catches `RuntimeException` (a
  player mutating under the poll; retried on the next poll).
- **Updates.** While bound: a `PlayerListChangeNotifier.Listener` registered
  through `UpnpClient#addPlayerListChangeListener` (main thread) plus a 2 s
  `Handler` poll (`POLL_INTERVAL_MS = 2000`) for title and play state, plus one
  extra refresh 500 ms after a command. `unbind()` removes the listener and
  both callbacks; nothing polls while the activity is stopped. The poll does
  in-memory reads only (title, play state, registry lookup), no network.
- **Text.** Title: the current item's title, else the player name
  (`MiniPlayerPresenter.title`). Receiver: the device name resolved with
  `UpnpClient#getDevice(deviceId)` (also answers `LOCAL_UID` with this device),
  cached per device id once resolved until `unbind()`, else the player name
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
- **Album art.** Dedup key `"M:" + playerId + ":" + albumArtUri`.
  `Player#getIcon()` is read **at most once per key change**, never on the poll
  path: `AVTransportPlayer#getIcon()` queues a new download on every call while
  the 512x512 art is not cached, so calling it each poll would request a broken or
  hostile art URL every 2 s. Then the art loads only through
  `IconDownloadTask(art, key)`, which drops its result unless `art.getTag()` still
  equals the key; `unbind()` clears the tag. While uncached this costs two
  downloads of the same URL per key change (the `getIcon()` one and the task's).
  A flip between two featured players with uncached art repeats that once per
  flip (at most one flip per poll).
- **Commands.** Previous, play/pause (`MiniPlayerPresenter.toggleAction(isPlaying)`),
  next and stop run on a single-thread executor created in `bind()` and shut
  down in `unbind()` (commands already queued still run). A command goes to the
  player featured at that moment. The tap on the card sends the player's own
  `getNotificationIntent()` through `ReceiverRowBinder.openPlayer` with an empty
  fill-in `Intent` (no extras, data or action).
- **Accessibility / D-pad.** The card is focusable before its descendants; each
  button has a content description (`mini_player_*` strings), the card has a
  click action label (`mini_player_open`), the art is
  `importantForAccessibility=no`.

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

- `LauncherActivity`: exported, `Theme.NoDisplay`, the three filters above. The
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
- `android:allowBackup` is not set (default `true`); the merged manifest does not
  set it either.
- The source manifest was changed by Group 6 (`LauncherActivity` added, filters
  moved off `TabBrowserActivity`) and Group 7 (`BrowserActivity` added); Groups
  8 and 9 did not touch it. `yaacc/build.gradle` has no Phase 2 change and no
  dependency was added.

### Known limitations / owner decisions

- **`allowBackup` (decision pending).** It defaults to `true`, so Auto Backup
  restores the default preferences file (layout, receiver selection,
  `proxy_link_*`, SAF cache entries). A restored or tampered `new` only routes to
  our own non-exported `BrowserActivity` through `relaunchIfOtherLayout`; the
  effect is a layout flip. Whether to set `android:allowBackup="false"` or exclude
  that file is the owner's call and is outside this spec.
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
  AsyncTask executor. The mini-player makes the exposure continuous (the featured
  player's art is fetched on every browser screen). A scheme allowlist
  (http/https), timeouts and a size cap are follow-ups.
- **Names from the LAN.** `ReceiverBarLabel` (receiver bar) and the device-sheet
  and server-list name bindings show raw friendly names (trim only): bidi controls
  and a name such as `Kitchen; Bedroom` are not neutralised, while the
  mini-player's text goes through `cleanText`.
- **Share, Backlog 1** (waiting for a discussion with the owner): which share
  (receiving into yaacc, or also the per-item share out of yaacc); whether files
  or `content://` URIs may become playable without opening the proxy hole; more MIME
  types; picking the receiver in the sheet; localized toasts and feedback for a
  rejected link; replacing the blind 3 s waits; what the new layout shows when a
  share starts; rotation/process death; tightening `ShareUriPolicy` (require
  `://`, reject whitespace and control characters).
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
  mini-player buttons and the receiver sheet at every level.

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
