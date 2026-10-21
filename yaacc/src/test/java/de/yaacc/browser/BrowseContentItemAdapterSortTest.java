/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ProgressBar;

import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;

import de.yaacc.upnp.UpnpClient;

/**
 * Red-phase (TDD) tests for issue #252 "Sort by Date / Recently Added".
 * <p>
 * These tests are written against the sort-mode API that
 * {@link BrowseContentItemAdapter} does NOT yet expose (Group 2 of the
 * spec implements it). Until then this class is expected to fail to
 * <b>compile</b> — that is the point of the red phase.
 * </p>
 * <p>
 * Assumed interface (documented here for Group 2 to implement exactly):
 * <ul>
 *     <li>{@code BrowseContentItemAdapter.SortMode} — a nested enum with
 *     constants {@code NAME} and {@code DATE}.</li>
 *     <li>{@code BrowseContentItemAdapter#getSortMode()} — returns the
 *     current {@code SortMode}, defaulting to {@code NAME}.</li>
 *     <li>{@code BrowseContentItemAdapter#setSortMode(SortMode)} — changes
 *     the sort mode. Per design.md/tasks.md this is expected to internally
 *     trigger a {@code clear()} + reload when the mode actually changes;
 *     that reload path (adapter {@code loadMore()}) already short-circuits
 *     safely when {@code contentListFragment.getNavigator()} is {@code
 *     null} (see {@code BrowseContentItemAdapter.loadMore(Long, Integer)}),
 *     which is what makes this setter safe to call against a mocked
 *     {@link ContentListFragment} in a plain JVM unit test — no Robolectric
 *     needed. Tests below therefore call {@code setSortMode(...)} once,
 *     before any {@code addAll(...)}, and populate/inspect the list
 *     manually afterwards to exercise only the adapter's own bookkeeping
 *     (not the network/AsyncTask reload path).</li>
 *     <li>{@code BrowseContentItemAdapter#isDateSortAvailable()} — {@code
 *     true} iff at least one currently-loaded {@link DIDLObject} has a
 *     non-null {@code dc:date} property
 *     ({@link DIDLObject.Property.DC.DATE}); recomputed as chunks arrive.</li>
 *     <li>Re-sorting: once {@code setAllItemsFetched(true)} is called,
 *     the adapter's backing list is reordered in place according to the
 *     current {@code SortMode}:
 *     <ul>
 *         <li>{@code NAME} — {@link Container}s before {@link Item}s,
 *         alphabetical (by title) within each group (existing/regression
 *         behavior).</li>
 *         <li>{@code DATE} — containers and items fully interleaved,
 *         sorted by {@code dc:date} descending (newest first).</li>
 *     </ul>
 *     Before {@code allItemsFetched} becomes {@code true}, no reordering
 *     happens — {@code addAll(...)} calls keep arrival order, in either
 *     mode.</li>
 * </ul>
 */
public class BrowseContentItemAdapterSortTest {

    private ContentListFragment contentListFragment;
    private UpnpClient upnpClient;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;

    @Before
    public void setUp() {
        Context context = mock(Context.class);
        SharedPreferences sharedPreferences = mock(SharedPreferences.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);
        // Echo back each call's own default value rather than a hardcoded
        // constant, so per-key defaults (e.g. nameAscending=true vs
        // dateAscending=false) are each respected instead of collapsing to
        // one shared stubbed value.
        when(sharedPreferences.getBoolean(any(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
        when(sharedPreferences.getString(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        SharedPreferences.Editor sharedPreferencesEditor = mock(SharedPreferences.Editor.class);
        when(sharedPreferences.edit()).thenReturn(sharedPreferencesEditor);
        // context.getString(int) is unstubbed here (returns null), so the
        // key argument these edits pass is null -- match with any() rather
        // than anyString(), which does not match null.
        when(sharedPreferencesEditor.putBoolean(any(), anyBoolean())).thenReturn(sharedPreferencesEditor);
        when(sharedPreferencesEditor.putString(any(), any())).thenReturn(sharedPreferencesEditor);

        contentListFragment = mock(ContentListFragment.class);
        when(contentListFragment.getContext()).thenReturn(context);
        // getNavigator() is intentionally left unstubbed (returns null), so that
        // any reload triggered by setSortMode(...) short-circuits safely inside
        // BrowseContentItemAdapter#loadMore(Long, Integer) instead of touching
        // the network/AsyncTask machinery.

        upnpClient = mock(UpnpClient.class);
        recyclerView = mock(RecyclerView.class);
        progressBar = mock(ProgressBar.class);
    }

    private BrowseContentItemAdapter newAdapter() {
        return newAdapter(contentListFragment, recyclerView, upnpClient, progressBar);
    }

    /**
     * Static, parameterized variant of the workaround-wrapped adapter
     * constructor, reusable from other test classes in this package (e.g.
     * {@link BrowseItemLoadTaskTest}) that need a real
     * {@link BrowseContentItemAdapter} instance with the same plain-JVM
     * {@code RecyclerView.Adapter} notify-safety fix applied. See {@link
     * #fixUpAdapterDataObservable(BrowseContentItemAdapter)} for why this is
     * needed.
     */
    static BrowseContentItemAdapter newAdapter(ContentListFragment contentListFragment, RecyclerView recyclerView,
                                                UpnpClient upnpClient, ProgressBar progressBar) {
        BrowseContentItemAdapter adapter = new BrowseContentItemAdapter(contentListFragment, recyclerView, upnpClient, progressBar);
        fixUpAdapterDataObservable(adapter);
        return adapter;
    }

    /**
     * Environment workaround, not a test-behavior change: this project's
     * unit tests run on a plain JVM with {@code unitTests.returnDefaultValues
     * = true} and no Robolectric shadow layer. Under that setup, AGP's
     * "mockable android.jar" strips the real constructor body of the
     * platform class {@code android.database.Observable} (the superclass,
     * two levels up, of {@code RecyclerView.Adapter}'s internal
     * {@code AdapterDataObservable}), so its {@code final ArrayList
     * mObservers} field is left {@code null} instead of being initialized.
     * Calling any real {@code RecyclerView.Adapter} notify* method (which
     * this test must do, via {@code addAll}/{@code clear}/{@code
     * setAllItemsFetched}, to exercise the adapter's real bookkeeping)
     * would otherwise NPE deep inside androidx/platform code, unrelated to
     * anything this test or {@link BrowseContentItemAdapter} does. This
     * reflectively initializes that one platform field so the real
     * notify* calls are harmless no-op observer notifications, exactly as
     * they would be against a real, unobserved adapter.
     */
    private static void fixUpAdapterDataObservable(BrowseContentItemAdapter adapter) {
        try {
            Field mObservableField = RecyclerView.Adapter.class.getDeclaredField("mObservable");
            mObservableField.setAccessible(true);
            Object adapterDataObservable = mObservableField.get(adapter);

            Field mObserversField = adapterDataObservable.getClass().getSuperclass().getDeclaredField("mObservers");
            mObserversField.setAccessible(true);
            mObserversField.set(adapterDataObservable, new ArrayList<>());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not work around the android.database.Observable "
                    + "stub-field limitation of this plain-JVM test environment", e);
        }
    }

    private static Container container(String id, String title) {
        return new Container(id, "0", title, "creator", (DIDLObject.Class) null, 0);
    }

    private static Item item(String id, String title, String dcDate) {
        Item result = new Item(id, "0", title, "creator", (DIDLObject.Class) null);
        if (dcDate != null) {
            result.addProperty(new DIDLObject.Property.DC.DATE(dcDate));
        }
        return result;
    }

    // 1. Name mode: containers before items, alphabetical within each group
    // (regression guard for current behavior).
    @Test
    public void nameModeKeepsContainersBeforeItemsAlphabeticalWithinGroup() {
        BrowseContentItemAdapter adapter = newAdapter();
        assertEquals(BrowseContentItemAdapter.SortMode.NAME, adapter.getSortMode());

        // Arrival order deliberately unsorted / interleaved.
        adapter.addAll(java.util.List.of(
                item("i2", "Zebra Song", "2020-01-01"),
                container("c2", "Zebra Folder"),
                item("i1", "Apple Song", "2024-01-01"),
                container("c1", "Apple Folder")
        ));
        adapter.setAllItemsFetched(true);

        assertEquals(4, adapter.getItemCount());
        assertEquals("Apple Folder", adapter.getFolder(0).getTitle());
        assertEquals("Zebra Folder", adapter.getFolder(1).getTitle());
        assertEquals("Apple Song", adapter.getFolder(2).getTitle());
        assertEquals("Zebra Song", adapter.getFolder(3).getTitle());
        assertTrue(adapter.getFolder(0) instanceof Container);
        assertTrue(adapter.getFolder(1) instanceof Container);
        assertTrue(adapter.getFolder(2) instanceof Item);
        assertTrue(adapter.getFolder(3) instanceof Item);
    }

    // 2. Date mode: containers and items fully interleaved, sorted by
    // dc:date descending (newest first), once allItemsFetched is true.
    @Test
    public void dateModeInterleavesContainersAndItemsByDateDescendingOnceFullyLoaded() {
        BrowseContentItemAdapter adapter = newAdapter();
        adapter.setSortMode(BrowseContentItemAdapter.SortMode.DATE);
        assertEquals(BrowseContentItemAdapter.SortMode.DATE, adapter.getSortMode());

        Container oldFolder = container("c1", "Old Folder");
        oldFolder.addProperty(new DIDLObject.Property.DC.DATE("2020-06-01"));
        Container newFolder = container("c2", "New Folder");
        newFolder.addProperty(new DIDLObject.Property.DC.DATE("2025-06-01"));
        Item midItem = item("i1", "Mid Song", "2022-06-01");
        Item newestItem = item("i2", "Newest Song", "2026-01-01");

        adapter.addAll(java.util.List.of(oldFolder, midItem, newFolder, newestItem));
        adapter.setAllItemsFetched(true);

        assertEquals(4, adapter.getItemCount());
        assertEquals("Newest Song", adapter.getFolder(0).getTitle());
        assertEquals("New Folder", adapter.getFolder(1).getTitle());
        assertEquals("Mid Song", adapter.getFolder(2).getTitle());
        assertEquals("Old Folder", adapter.getFolder(3).getTitle());
    }

    // 3. No re-sort/reshuffle while allItemsFetched is false: mid-load
    // order matches arrival order, even in Date mode.
    @Test
    public void noReshuffleWhileStillLoading() {
        BrowseContentItemAdapter adapter = newAdapter();
        adapter.setSortMode(BrowseContentItemAdapter.SortMode.DATE);

        Item newestItem = item("i2", "Newest Song", "2026-01-01");
        Item oldItem = item("i1", "Old Song", "2020-01-01");
        Container folder = container("c1", "Some Folder");

        // Arrival order: newest item, then old item, then folder -- NOT
        // date order and NOT container-first order.
        adapter.addAll(java.util.List.of(newestItem, oldItem, folder));
        // allItemsFetched left at its default (false): no reorder expected.

        assertEquals(3, adapter.getItemCount());
        assertEquals("Newest Song", adapter.getFolder(0).getTitle());
        assertEquals("Old Song", adapter.getFolder(1).getTitle());
        assertEquals("Some Folder", adapter.getFolder(2).getTitle());
    }

    // 4a. When no loaded item has a non-null dc:date, date-sort is
    // reported as unavailable.
    @Test
    public void isDateSortAvailableFalseWhenNoLoadedItemHasDate() {
        BrowseContentItemAdapter adapter = newAdapter();

        adapter.addAll(java.util.List.of(
                container("c1", "Folder"),
                item("i1", "Song One", null),
                item("i2", "Song Two", null)
        ));

        assertFalse(adapter.isDateSortAvailable());
    }

    // 4b. As soon as at least one loaded item carries a dc:date, date-sort
    // becomes available (recomputed live as chunks arrive).
    @Test
    public void isDateSortAvailableTrueWhenAtLeastOneLoadedItemHasDate() {
        BrowseContentItemAdapter adapter = newAdapter();

        adapter.addAll(java.util.List.of(
                container("c1", "Folder"),
                item("i1", "Song One", null)
        ));
        assertFalse(adapter.isDateSortAvailable());

        adapter.addAll(java.util.List.of(item("i2", "Song Two", "2024-05-01")));
        assertTrue(adapter.isDateSortAvailable());
    }

    // ------------------------------------------------------------------
    // Group 4: ascending/descending direction toggle, red-phase tests.
    // Name defaults ascending (A-Z, matching current/existing behavior);
    // Date defaults descending (newest-first, matching current/existing
    // behavior) -- both preserve behavior for existing users until they
    // explicitly toggle a direction.
    // ------------------------------------------------------------------

    // 5a. Direction defaults: name=true (A-Z), date=false (newest-first).
    @Test
    public void directionDefaults() {
        BrowseContentItemAdapter adapter = newAdapter();

        assertTrue(adapter.isNameAscending());
        assertFalse(adapter.isDateAscending());
    }

    // 5b. toggleDirection() flips only the currently-selected mode's own
    // direction; the other mode's direction is untouched. Switching mode
    // and back shows each mode's direction preserved independently.
    @Test
    public void toggleDirectionFlipsOnlyCurrentModesDirectionIndependently() {
        BrowseContentItemAdapter adapter = newAdapter();
        assertEquals(BrowseContentItemAdapter.SortMode.NAME, adapter.getSortMode());
        assertTrue(adapter.isNameAscending());
        assertFalse(adapter.isDateAscending());

        // Toggling while in NAME mode flips only nameAscending.
        adapter.toggleDirection();
        assertFalse(adapter.isNameAscending());
        assertFalse(adapter.isDateAscending());

        // Switching to DATE mode must not itself change either direction.
        adapter.setSortMode(BrowseContentItemAdapter.SortMode.DATE);
        assertFalse(adapter.isNameAscending());
        assertFalse(adapter.isDateAscending());

        // Toggling while in DATE mode flips only dateAscending -- NAME's
        // (already-flipped) direction stays untouched.
        adapter.toggleDirection();
        assertFalse(adapter.isNameAscending());
        assertTrue(adapter.isDateAscending());

        // Switching back to NAME mode still shows NAME's own direction
        // exactly as it was left, unaffected by DATE's toggle.
        adapter.setSortMode(BrowseContentItemAdapter.SortMode.NAME);
        assertFalse(adapter.isNameAscending());
        assertTrue(adapter.isDateAscending());
    }

    // 5c. Name-mode sort respects nameAscending: Z-A when false, but
    // containers are still always grouped before items regardless of
    // direction -- only the alphabetical comparison within each group
    // reverses.
    @Test
    public void nameModeDescendingReversesAlphabeticalOrderWithinGroupsOnly() {
        BrowseContentItemAdapter adapter = newAdapter();
        adapter.toggleDirection(); // NAME mode is selected by default -> flips nameAscending to false (Z-A)
        assertFalse(adapter.isNameAscending());

        adapter.addAll(java.util.List.of(
                item("i2", "Zebra Song", "2020-01-01"),
                container("c2", "Zebra Folder"),
                item("i1", "Apple Song", "2024-01-01"),
                container("c1", "Apple Folder")
        ));
        adapter.setAllItemsFetched(true);

        assertEquals(4, adapter.getItemCount());
        // Containers still before items, but Z-A within each group.
        assertEquals("Zebra Folder", adapter.getFolder(0).getTitle());
        assertEquals("Apple Folder", adapter.getFolder(1).getTitle());
        assertEquals("Zebra Song", adapter.getFolder(2).getTitle());
        assertEquals("Apple Song", adapter.getFolder(3).getTitle());
        assertTrue(adapter.getFolder(0) instanceof Container);
        assertTrue(adapter.getFolder(1) instanceof Container);
        assertTrue(adapter.getFolder(2) instanceof Item);
        assertTrue(adapter.getFolder(3) instanceof Item);
    }

    // 5d. Date-mode sort respects dateAscending: oldest-first when true,
    // but missing/unparseable dates still sort last regardless of
    // direction (existing guarantee, must not break).
    @Test
    public void dateModeAscendingSortsOldestFirstButMissingDatesStillSortLast() {
        BrowseContentItemAdapter adapter = newAdapter();
        adapter.setSortMode(BrowseContentItemAdapter.SortMode.DATE);
        adapter.toggleDirection(); // flips dateAscending true -> oldest-first
        assertTrue(adapter.isDateAscending());

        Item oldItem = item("i1", "Old Song", "2020-01-01");
        Item newItem = item("i2", "New Song", "2025-01-01");
        Item noDateItem = item("i3", "No Date Song", null);

        adapter.addAll(java.util.List.of(newItem, noDateItem, oldItem));
        adapter.setAllItemsFetched(true);

        assertEquals(3, adapter.getItemCount());
        assertEquals("Old Song", adapter.getFolder(0).getTitle());
        assertEquals("New Song", adapter.getFolder(1).getTitle());
        // Missing dc:date still sorts last, even though direction is
        // ascending (oldest-first) rather than the default descending.
        assertEquals("No Date Song", adapter.getFolder(2).getTitle());
    }
}
