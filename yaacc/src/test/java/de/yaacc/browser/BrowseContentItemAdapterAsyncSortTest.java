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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ProgressBar;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.item.Item;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import de.yaacc.upnp.UpnpClient;

/**
 * The client-side sort of a finished folder runs on a worker and is swapped in on the main thread: here both are
 * queues the test drives by hand, to place a change between the start of the sort and its result.
 */
public class BrowseContentItemAdapterAsyncSortTest {

    private final Queue<Runnable> worker = new ArrayDeque<>();
    private final Queue<Runnable> mainThread = new ArrayDeque<>();
    private RecyclerView recyclerView;
    private LinearLayoutManager layoutManager;
    private BrowseContentItemAdapter adapter;
    private RecyclerView.AdapterDataObserver observer;

    @Before
    public void setUp() {
        Context context = mock(Context.class);
        SharedPreferences sharedPreferences = mock(SharedPreferences.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);
        when(sharedPreferences.getBoolean(any(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
        when(sharedPreferences.getString(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(sharedPreferences.edit()).thenReturn(editor);
        when(editor.putBoolean(any(), anyBoolean())).thenReturn(editor);
        when(editor.putString(any(), any())).thenReturn(editor);
        ContentListFragment fragment = mock(ContentListFragment.class);
        when(fragment.getContext()).thenReturn(context);
        recyclerView = mock(RecyclerView.class);
        layoutManager = mock(LinearLayoutManager.class);
        when(recyclerView.getLayoutManager()).thenReturn(layoutManager);
        adapter = BrowseContentItemAdapterSortTest.newAdapter(fragment, recyclerView, mock(UpnpClient.class), mock(ProgressBar.class));
        adapter.setSortExecutors(worker::add, mainThread::add);
        observer = mock(RecyclerView.AdapterDataObserver.class);
        registerObserverDirectly(adapter, observer);
    }

    @SuppressWarnings("unchecked")
    private static void registerObserverDirectly(BrowseContentItemAdapter adapter, RecyclerView.AdapterDataObserver observer) {
        try {
            Field mObservableField = RecyclerView.Adapter.class.getDeclaredField("mObservable");
            mObservableField.setAccessible(true);
            Object adapterDataObservable = mObservableField.get(adapter);
            Field mObserversField = adapterDataObservable.getClass().getSuperclass().getDeclaredField("mObservers");
            mObserversField.setAccessible(true);
            ((List<RecyclerView.AdapterDataObserver>) mObserversField.get(adapterDataObservable)).add(observer);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Item item(String title) {
        return new Item(title, "0", title, "creator", (DIDLObject.Class) null);
    }

    private static void runAll(Queue<Runnable> queue) {
        Runnable next;
        while ((next = queue.poll()) != null) {
            next.run();
        }
    }

    private void loadUnsortedFolder() {
        adapter.addAll(List.of(item("c"), item("a"), item("b")));
        adapter.setAllItemsFetched(true);
    }

    @Test
    public void theListIsSortedOnlyAfterTheWorkerAndTheMainThreadRan() {
        loadUnsortedFolder();
        assertEquals("nothing sorted on the calling thread", "c", adapter.getFolder(0).getTitle());
        assertEquals(1, worker.size());
        assertTrue(mainThread.isEmpty());

        runAll(worker);
        assertEquals("the swap waits for the main thread", "c", adapter.getFolder(0).getTitle());

        runAll(mainThread);
        assertEquals("a", adapter.getFolder(0).getTitle());
        assertEquals("b", adapter.getFolder(1).getTitle());
        assertEquals("c", adapter.getFolder(2).getTitle());
    }

    @Test
    public void onlyTheChangedRangeIsNotified() {
        adapter.addAll(List.of(item("a"), item("c"), item("b"), item("d")));
        adapter.setAllItemsFetched(true);
        runAll(worker);
        runAll(mainThread);

        verify(observer).onItemRangeChanged(eq(1), eq(2), any());
        verify(observer, never()).onChanged();
    }

    @Test
    public void anAlreadySortedFolderNotifiesNothing() {
        adapter.addAll(List.of(item("a"), item("b"), item("c")));
        adapter.setAllItemsFetched(true);
        runAll(worker);
        runAll(mainThread);

        verify(observer, never()).onItemRangeChanged(anyInt(), anyInt(), any());
        verify(observer, never()).onChanged();
    }

    @Test
    public void clearBetweenStartAndResultDropsTheResult() {
        loadUnsortedFolder();
        runAll(worker);
        adapter.clear();
        adapter.addAll(List.of(item("z"), item("y")));
        runAll(mainThread);

        assertEquals("the new folder keeps its arrival order", "z", adapter.getFolder(0).getTitle());
        assertEquals(2, adapter.getItemCount());
    }

    @Test
    public void changingTheSortModeBetweenStartAndResultDropsTheResult() {
        loadUnsortedFolder();
        runAll(worker);
        adapter.setSortMode(BrowseContentItemAdapter.SortMode.DATE); // cancels, clears (the reload has no navigator)
        runAll(mainThread);

        assertEquals(0, adapter.getItemCount());
    }

    @Test
    public void toggleDirectionAndCancelAlsoDropTheResult() {
        loadUnsortedFolder();
        runAll(worker);
        adapter.cancelRunningTasks();
        runAll(mainThread);
        assertEquals("c", adapter.getFolder(0).getTitle());
    }

    @Test
    public void objectsAddedWhileSortingAreSortedInAsWell() {
        loadUnsortedFolder();
        runAll(worker);
        adapter.addAll(List.of(item("0")));
        runAll(mainThread); // the first result is stale
        assertEquals("c", adapter.getFolder(0).getTitle());
        runAll(worker);
        runAll(mainThread);

        assertEquals("0", adapter.getFolder(0).getTitle());
        assertEquals(4, adapter.getItemCount());
    }

    @Test
    public void aScrollTargetWaitsForTheSortedList() {
        loadUnsortedFolder();
        adapter.scrollToPositionId(2);
        verify(layoutManager, never()).scrollToPositionWithOffset(anyInt(), anyInt());

        runAll(worker);
        runAll(mainThread);
        verify(layoutManager).scrollToPositionWithOffset(2, 0);
    }

    @Test
    public void aScrollTargetWithoutASortRunsAtOnce() {
        adapter.scrollToPositionId(1);
        verify(layoutManager).scrollToPositionWithOffset(1, 0);
    }

    @Test
    public void releaseDropsAPendingResultAndTheAdapterStaysUsable() {
        loadUnsortedFolder();
        runAll(worker);
        adapter.release();
        runAll(mainThread);
        assertEquals("c", adapter.getFolder(0).getTitle());

        // a later load sorts again
        adapter.clear();
        adapter.addAll(List.of(item("y"), item("x")));
        adapter.setAllItemsFetched(true);
        runAll(worker);
        runAll(mainThread);
        assertEquals("x", adapter.getFolder(0).getTitle());
    }

    @Test
    public void fewerThanTwoObjectsStartNoWork() {
        adapter.addAll(List.of(item("a")));
        adapter.setAllItemsFetched(true);
        assertTrue(worker.isEmpty());
    }
}
