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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ProgressBar;

import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpFailure;
import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseResult;

/**
 * {@link BrowseItemLoadTask} with the {@link BrowseRetryPolicy}: a network trouble is retried on the
 * worker thread, a failure that survives the retries keeps what the list shows (and is remembered on
 * the adapter), an answer of the server or a real empty folder behaves as before. The waits between
 * the tries are recorded, not slept.
 */
public class BrowseItemLoadTaskRetryTest {

    private ContentListFragment contentListFragment;
    private UpnpClient upnpClient;
    private BrowseContentItemAdapter adapter;
    private final List<Long> sleeps = new ArrayList<>();

    @Before
    public void setUp() {
        Context context = mock(Context.class);
        SharedPreferences sharedPreferences = mock(SharedPreferences.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);
        when(sharedPreferences.getBoolean(any(), anyBoolean())).thenReturn(true);
        when(sharedPreferences.getString(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

        upnpClient = mock(UpnpClient.class);
        Yaacc yaacc = mock(Yaacc.class);
        when(yaacc.getUpnpClient()).thenReturn(upnpClient);
        when(context.getApplicationContext()).thenReturn(yaacc);
        when(context.getString(anyInt())).thenReturn("some_error_string");

        contentListFragment = mock(ContentListFragment.class);
        when(contentListFragment.getContext()).thenReturn(context);
        when(contentListFragment.getNavigator()).thenReturn(null);

        adapter = BrowseContentItemAdapterSortTest.newAdapter(contentListFragment, mock(RecyclerView.class), upnpClient, mock(ProgressBar.class));
        Navigator navigator = new Navigator();
        navigator.pushPosition(new Position(0, "0", "device-1", "root"));
        when(contentListFragment.getNavigator()).thenReturn(navigator);
    }

    private BrowseItemLoadTask newTask() {
        return new BrowseItemLoadTask(adapter, 50L, null, new BrowseRetryPolicy(sleeps::add));
    }

    private static ContentDirectoryBrowseResult content(String containerId, String itemId) {
        ContentDirectoryBrowseResult result = new ContentDirectoryBrowseResult();
        DIDLContent didl = new DIDLContent();
        didl.addContainer(new Container(containerId, "0", "Folder", "creator", (DIDLObject.Class) null, 0));
        didl.addItem(new Item(itemId, "0", "Song", "creator", (DIDLObject.Class) null));
        result.setResult(didl);
        return result;
    }

    private static ContentDirectoryBrowseResult transportFailure() {
        ContentDirectoryBrowseResult result = new ContentDirectoryBrowseResult();
        result.setUpnpFailure(new UpnpFailure(null, null, "no connection", true));
        return result;
    }

    private static ContentDirectoryBrowseResult serverError() {
        ContentDirectoryBrowseResult result = new ContentDirectoryBrowseResult();
        result.setUpnpFailure(new UpnpFailure(null, null, "701 no such object", false));
        return result;
    }

    private void stubUnsorted(ContentDirectoryBrowseResult first, ContentDirectoryBrowseResult... more) {
        when(upnpClient.browseSync(any(Position.class), any(Long.class), any(Long.class))).thenReturn(first, more);
    }

    private void verifyUnsortedCalls(int times) {
        verify(upnpClient, times(times)).browseSync(any(Position.class), any(Long.class), any(Long.class));
    }

    @Test
    public void networkTroubleIsRetriedAndTheFirstGoodAnswerIsShown() {
        stubUnsorted(transportFailure(), transportFailure(), content("c1", "i1"));

        BrowseItemLoadTask task = newTask();
        ContentDirectoryBrowseResult result = task.doInBackground(0L);
        task.onPostExecute(result);

        verifyUnsortedCalls(3);
        assertEquals(Arrays.asList(500L, 1500L), sleeps);
        assertEquals("each item once, the failed tries added nothing", 2, adapter.getItemCount());
        assertFalse(adapter.isLoadFailed());
    }

    @Test
    public void failureAfterAllTriesKeepsTheItemsAndRemembersTheFailure() {
        adapter.addAll(Arrays.asList(new Item("old1", "0", "Old", "creator", (DIDLObject.Class) null)));
        adapter.setLoading(true);
        stubUnsorted(transportFailure());

        BrowseItemLoadTask task = newTask();
        ContentDirectoryBrowseResult result = task.doInBackground(1L);
        task.onPostExecute(result);

        verifyUnsortedCalls(3);
        assertEquals("what was shown stays", 1, adapter.getItemCount());
        assertTrue(adapter.isLoadFailed());
        assertFalse("loading ends", adapter.isLoading());
    }

    @Test
    public void failureOfTheFirstChunkLeavesTheListEmptyButFailed() {
        stubUnsorted(transportFailure());

        BrowseItemLoadTask task = newTask();
        task.onPostExecute(task.doInBackground(0L));

        assertEquals(0, adapter.getItemCount());
        assertTrue(adapter.isLoadFailed());
    }

    @Test
    public void noResultAtAllIsRetriedAndEndsAsFailureWithLoadingOff() {
        adapter.setLoading(true);
        stubUnsorted(null);

        BrowseItemLoadTask task = newTask();
        ContentDirectoryBrowseResult result = task.doInBackground(0L);
        assertNull(result);
        task.onPostExecute(result);

        verifyUnsortedCalls(3);
        assertTrue(adapter.isLoadFailed());
        assertFalse(adapter.isLoading());
    }

    @Test
    public void answerOfTheServerIsNotRetriedButStillKeepsTheList() {
        adapter.addAll(Arrays.asList(new Item("old1", "0", "Old", "creator", (DIDLObject.Class) null)));
        stubUnsorted(serverError());

        BrowseItemLoadTask task = newTask();
        task.onPostExecute(task.doInBackground(1L));

        verifyUnsortedCalls(1);
        assertTrue(sleeps.isEmpty());
        assertEquals(1, adapter.getItemCount());
        assertTrue(adapter.isLoadFailed());
    }

    @Test
    public void aLaterSuccessClearsTheFailedFlag() {
        adapter.setLoadFailed(true);
        stubUnsorted(content("c1", "i1"));

        BrowseItemLoadTask task = newTask();
        task.onPostExecute(task.doInBackground(0L));

        assertFalse(adapter.isLoadFailed());
        assertEquals(2, adapter.getItemCount());
    }

    @Test
    public void realEmptyFolderBehavesAsBefore() {
        ContentDirectoryBrowseResult empty = new ContentDirectoryBrowseResult();
        empty.setResult(new DIDLContent());
        stubUnsorted(empty);

        BrowseItemLoadTask task = newTask();
        task.onPostExecute(task.doInBackground(0L));

        verifyUnsortedCalls(1);
        assertEquals(0, adapter.getItemCount());
        assertFalse(adapter.isLoadFailed());
    }

    @Test
    public void networkTroubleOnTheSortedRequestIsRetriedWithoutBlamingTheSort() {
        adapter.setSortMode(BrowseContentItemAdapter.SortMode.DATE);
        ContentDirectoryBrowseResult good = content("c1", "i1");
        when(upnpClient.browseSync(any(Position.class), any(Long.class), any(Long.class), isA(SortCriterion.class)))
                .thenReturn(transportFailure(), good);

        BrowseItemLoadTask task = newTask();
        ContentDirectoryBrowseResult result = task.doInBackground(0L);

        assertSame(good, result);
        verify(upnpClient, times(2)).browseSync(any(Position.class), any(Long.class), any(Long.class), isA(SortCriterion.class));
        verifyUnsortedCalls(0);
        assertFalse("a dropped connection is no rejection of the sort criteria", adapter.isServerSortRejected());
    }

    @Test
    public void sortedRequestThatNeverGetsAnAnswerDoesNotStartAnotherRoundUnsorted() {
        adapter.setSortMode(BrowseContentItemAdapter.SortMode.DATE);
        when(upnpClient.browseSync(any(Position.class), any(Long.class), any(Long.class), isA(SortCriterion.class)))
                .thenReturn(transportFailure());

        BrowseItemLoadTask task = newTask();
        ContentDirectoryBrowseResult result = task.doInBackground(0L);

        assertNotNull(result);
        verify(upnpClient, times(3)).browseSync(any(Position.class), any(Long.class), any(Long.class), isA(SortCriterion.class));
        verifyUnsortedCalls(0);
        assertFalse(adapter.isServerSortRejected());
    }

    @Test
    public void offsetsAreTheSameOnEveryTry() {
        stubUnsorted(transportFailure(), content("c1", "i1"));

        BrowseItemLoadTask task = newTask();
        task.doInBackground(100L);

        verify(upnpClient, times(2)).browseSync(any(Position.class), org.mockito.ArgumentMatchers.eq(100L), org.mockito.ArgumentMatchers.eq(50L));
    }
}
