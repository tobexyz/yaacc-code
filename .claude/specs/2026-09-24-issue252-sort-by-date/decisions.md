# Decisions: Sort by Date / Recently Added for UPnP folders (issue #252)

## 2026-09-25 — Local Android SDK setup for this session

**Context**: This cloud environment ships no Android SDK and its default
network policy blocked `dl.google.com` (Android Gradle Plugin / Google's
Maven repo) and `jitpack.io` (the `com.github.chrisbanes:PhotoView`
dependency), so Gradle could not even configure, let alone compile or run
tests.

**Decision**: with the user's help, opened both hosts in the environment's
network settings, then installed a minimal Android SDK for this session:
command-line tools unzipped to `/home/user/android-sdk/cmdline-tools/latest`,
`platform-tools`, `platforms;android-34`, and `build-tools;34.0.0`/`35.0.0`
via `sdkmanager`, and pointed Gradle at it via
`/home/user/yaacc-code/local.properties` (`sdk.dir=/home/user/android-sdk`).

**Rationale**: this SDK install and `local.properties` live outside the git
repo (not committed — `local.properties` is a per-machine file, standard
Gradle/Android convention, already gitignored) and only persist for this
container's lifetime. Later groups' subagents in this same session reuse
this setup rather than re-installing.

Maven Central (`repo.maven.apache.org`) also intermittently returned 429
(Too Many Requests) through the proxy during dependency resolution — this
resolved on retry with a short backoff (~15-20s), not a persistent block.

## 2026-09-25 — Group 2: reflection workaround in `BrowseContentItemAdapterSortTest`

**Context**: after implementing the Group 2 sort-mode API, all five tests
in the Group 1 red-phase test class compiled but NPE'd inside androidx
`RecyclerView.Adapter`'s real `notifyItemRangeInserted`/`notifyDataSetChanged`
(final methods, unrelated to our sort logic). Root-caused with a throwaway
sanity test (`new RecyclerView.Adapter<...>(){...}.notifyItemRangeInserted(0,1)`
alone NPEs the same way): `RecyclerView.Adapter`'s internal
`AdapterDataObservable` extends the **platform** class
`android.database.Observable`, and this project's unit tests run with
`unitTests.returnDefaultValues = true` and no Robolectric shadow layer.
AGP's mockable `android.jar` strips real constructor/field-initializer
bytecode from `android.*` classes, so `Observable`'s `final ArrayList
mObservers` field is left `null` — any real adapter's notify* call NPEs
inside platform code, regardless of what `BrowseContentItemAdapter` does.
This has nothing to do with sort behavior and is not something Group 2's
task scope (or a single subagent, without a spec-approved new test
dependency like Robolectric) can fix in the adapter or app code.

**Decision**: fixed the test, not the app: `BrowseContentItemAdapterSortTest`'s
`newAdapter()` helper now reflectively initializes that one platform field
(`RecyclerView.Adapter#mObservable` → its inherited `mObservers` field) to
an empty `ArrayList` right after construction, via a documented helper
method (`fixUpAdapterDataObservable`). No test assertion, expectation, or
adapter production behavior changed — this only makes the *existing* test
setup able to construct a real, observable `RecyclerView.Adapter` on a
plain JVM the way this project's other unit tests already assume for
non-RecyclerView Android objects.

**Rationale**: adding Robolectric (or `mockito-inline`, needed to stub the
otherwise-`final` `notify*` methods) is a new test dependency requiring
spec approval, out of scope for a single Group 2 task. Silently swallowing
or no-op'ing `notifyItemRangeInserted`/`clear()`'s `notifyDataSetChanged()`
inside `BrowseContentItemAdapter` itself would change real production
behavior (RecyclerView would stop being told about data changes) just to
route around a test-environment quirk — not acceptable. The reflection
fix is scoped entirely to the test file, touches no shipped code, and is
a well-known, narrow workaround for this exact AGP-unit-test limitation.

## 2026-09-25 — Group 3: manual live-server verification deferred to user

**Context**: Group 3's first task calls for browsing a real DLNA server
with `dc:date` content to confirm end-to-end behavior (Name/Date toggle,
no mid-load reshuffle, persistence across restart, Date disabled when no
`dc:date` present). This cloud sandbox has neither an Android
emulator/device nor a real UPnP network, so this can't be exercised here.

**Decision**: offered the user a choice between skipping this step
(manual testing on their own device before merge) and attempting a
heavier emulator + yaacc's-own-embedded-server smoke test with synthetic
`dc:date` content. User chose to skip and test it themselves — see
`tasks.md` Group 3, marked `[!]`.

**Rationale**: general review (2 cycles, PASS) and security review (PASS)
both cover the code and `BrowseContentItemAdapterSortTest` unit-tests the
adapter's sort/availability logic in isolation, but none of that
substitutes for a real third-party DLNA server smoke test, which the
issue itself was filed against (the reporter uses UAPP). Documented here
so it isn't silently missed as "done" — it is explicitly the user's
follow-up before this branch is considered ready to merge.

## 2026-09-26 — Post-ship UI bug: sort toggle overlapped the content list on the root/main folder

**Context**: the user tested the built app and reported the sort toggle
buttons overlapping the top rows of the content list specifically on the
"main browser page" (the root/device-level folder listing, before
navigating into any subfolder).

**Root cause**: `ContentListFragment.removeFolderNavigation()` — called
whenever the current folder is the root (`showMainFolder()`,
`onBackPressed()` at root) — hides the back button, folder-name text, and
separator, and force-pins `contentList` to the very top of the parent
(`RelativeLayout.ALIGN_PARENT_TOP`), since there's no breadcrumb path to
show at the root. This logic predates the sort toggle and never accounted
for it: `contentListSortToggle` stays visible and positioned in the same
top-right spot regardless, so once `contentList` snaps to y=0 it renders
directly underneath the still-visible toggle buttons.

**Fix**: wrapped the back button, sort toggle, and folder-name text in a
single `contentListHeaderRow` `RelativeLayout` container
(`fragment_content_list.xml`, portrait only — the `layout-land` variant
puts `contentList` in an entirely separate weighted column unaffected by
this collapse, confirmed by inspection, no change needed there).
`contentListTopSeperator` (and transitively `contentList`, which is
`layout_below` it) now sits below this container instead of below the
folder-name text directly. Because a `GONE` view contributes zero size to
a `wrap_content` parent, the header row now naturally shrinks to just the
sort toggle's height when the back button and folder name are hidden at
root — `contentList` moves up to reclaim that space without ever
overlapping the toggle. This also incidentally fixes a latent, narrower
version of the same class of bug for nested folders: previously
`contentListTopSeperator` was only positioned below the folder-name
text's height, not accounting for the sort toggle row's height, so a
short folder name could theoretically sit shorter than the toggle row too
— now both are correctly captured by the wrapping container.

Simplified `ContentListFragment.removeFolderNavigation()`/
`showFolderNavigation()` to drop the now-unnecessary
`contentList.getLayoutParams()` / `RelativeLayout.ALIGN_PARENT_TOP`
add/remove-rule dance entirely (and the now-unused `RelativeLayout`
import) — the header row's own wrap_content sizing does the work instead.
