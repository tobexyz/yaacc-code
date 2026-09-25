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
