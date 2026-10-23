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

Research notes for spec `2026-10-23-unified-device-sheet` (Group 1).
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

Usage pattern (to be used by `DeviceSheetFragment`; not written yet):

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
in night mode. Fix it in Group 3 in one of two ways:

- Override `getTheme()` in `DeviceSheetFragment` to return
  `com.google.android.material.R.style.ThemeOverlay_MaterialComponents_BottomSheetDialog`.
- Add `<item name="bottomSheetDialogTheme">@style/ThemeOverlay.MaterialComponents.BottomSheetDialog</item>`
  to `AppTheme`.

Check both day and night on device.

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
  RecyclerView's own bounds. Mitigation for Group 3: when the device isn't
  in touch mode (`!view.isInTouchMode()`), or when it is a leanback/TV
  device, open in `STATE_EXPANDED`. Alternatively, expand when focus first
  lands on a row. Confirm this during Group 3's manual TV check.
- **Initial focus.** design.md wants focus on the first receiver's
  checkbox. Request it after the first layout, for example with
  `recyclerView.post(...)` once the adapter has rows. Nothing in Material
  sets initial focus.

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
- **To add (later group, not done here):** a player-set listener on
  `PlayerService`, for example
  `addPlayerListChangeListener(Runnable)` / `removePlayerListChangeListener(Runnable)`.
  It would fire at the end of `addPlayer`, `removePlayer` and
  `shutdown(Player)`, after the map has changed. Those methods are reached
  from `onServiceConnected`/`onServiceDisconnected`, which run on the main
  thread, and from `createPlayer...`, whose thread depends on the caller.
  Listeners should therefore post to the main thread before touching
  views, and the sheet must unregister in `onDestroyView` or on dismiss.
  Because `getCurrentPlayers()` is a live view of a `HashMap`, the sheet
  should copy it, `new ArrayList<>(...)`, on the main thread before
  building `DeviceSessionGrouper.PlayerRef`s.
- **Repeat calls in both directions. The future listener must ignore
  them.**
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
  - So the hook fires **"added" only when `currentActivePlayer.put(...)`
    returns null**, meaning the player is new to the map. It fires
    **"removed" only when `currentActivePlayer.remove(...)` returns
    non-null**, meaning the player was actually still in the map.
    Otherwise one player start or stop would notify twice.
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

### 7. `DeviceSessionGrouper`: Group 1 state

- `yaacc/src/main/java/de/yaacc/browser/DeviceSessionGrouper.java` is a
  **stub** with the final public API from design.md. `group` returns an
  empty list and `primarySession` returns null. Group 2 implements it.
  `PlayerRef` exposes record-style accessors `playerId()`, `deviceId()`
  and `playing()`, matching `Row`'s `kind()`, `deviceId()` and
  `playerId()`. These are plain final classes, not records, with no
  `android.*` imports.
- `yaacc/src/test/java/de/yaacc/browser/DeviceSessionGrouperTest.java`
  (JUnit 4.13.2, plain JVM) states two readings of design.md that it
  leaves open:
  1. Sessions on a device that isn't a receiver get **no RECEIVER row**.
     A receiver row means bound device controls, which a vanished device
     can't have. The adapter labels the trailing group from the session
     row's `deviceId()`. Those sessions stay contiguous per device, in
     ascending playerId order, after all receiver groups.
  2. With no receivers at all, every session is still listed.
