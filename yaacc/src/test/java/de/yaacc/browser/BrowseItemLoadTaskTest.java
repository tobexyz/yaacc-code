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
import static org.junit.Assert.assertNotNull;
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
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;
import org.junit.Before;
import org.junit.Test;

import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpFailure;
import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseResult;

/**
 * Red-phase (TDD) regression test for the post-ship bug fixed in "Fix Group
 * 2" of issue #252: a server that actively <b>rejects</b> an unsupported
 * {@code dc:date} {@code SortCriteria} (a UPnP action failure, not a
 * silently-ignored one) must not empty the folder. Instead {@link
 * BrowseItemLoadTask#doInBackground} retries the same chunk request without
 * {@code orderBy}, and remembers the rejection on the adapter so later chunk
 * requests for the same folder load skip the doomed sorted attempt.
 * <p>
 * Same package as {@link BrowseItemLoadTask} so its {@code protected}
 * {@code doInBackground}/{@code onPostExecute} can be invoked directly --
 * no {@code AsyncTask.execute()}/Looper needed.
 * </p>
 */
public class BrowseItemLoadTaskTest {

    private ContentListFragment contentListFragment;
    private UpnpClient upnpClient;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private Navigator navigatorWithPosition;
    private Position position;

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
        when(context.getString(org.mockito.ArgumentMatchers.anyInt())).thenReturn("some_error_string");

        contentListFragment = mock(ContentListFragment.class);
        when(contentListFragment.getContext()).thenReturn(context);
        // Left returning null during adapter construction/setSortMode so that
        // any reload setSortMode(...) triggers short-circuits safely inside
        // BrowseContentItemAdapter#loadMore(Long, Integer), exactly like
        // BrowseContentItemAdapterSortTest does. Re-stubbed below to a real
        // position once the adapter is in DATE mode, for the direct
        // doInBackground(...) calls this test makes.
        when(contentListFragment.getNavigator()).thenReturn(null);

        recyclerView = mock(RecyclerView.class);
        progressBar = mock(ProgressBar.class);

        position = new Position(0, "0", "device-1", "root");
        navigatorWithPosition = new Navigator();
        navigatorWithPosition.pushPosition(position);
    }

    private static Container container(String id, String title) {
        return new Container(id, "0", title, "creator", (org.fourthline.cling.support.model.DIDLObject.Class) null, 0);
    }

    private static Item item(String id, String title) {
        return new Item(id, "0", title, "creator", (org.fourthline.cling.support.model.DIDLObject.Class) null);
    }

    private static ContentDirectoryBrowseResult successResultWith(Container container, Item item) {
        ContentDirectoryBrowseResult result = new ContentDirectoryBrowseResult();
        DIDLContent content = new DIDLContent();
        content.addContainer(container);
        content.addItem(item);
        result.setResult(content);
        return result;
    }

    private static ContentDirectoryBrowseResult rejectedResult() {
        ContentDirectoryBrowseResult result = new ContentDirectoryBrowseResult();
        result.setUpnpFailure(mock(UpnpFailure.class));
        return result;
    }

    /**
     * Stubs the sorted attempt: {@code browseSync(...)} called with exactly
     * one {@link SortCriterion} vararg element. Mockito matches varargs
     * element-wise, so the matcher count (4: three fixed args plus one
     * per-element matcher) must equal the actual flattened argument count
     * -- which is only true when the real call carries exactly one
     * criterion, i.e. the sorted attempt.
     */
    private void stubSortedAttempt(ContentDirectoryBrowseResult toReturn) {
        when(upnpClient.browseSync(any(Position.class), any(Long.class), any(Long.class), isA(SortCriterion.class)))
                .thenReturn(toReturn);
    }

    /**
     * Stubs the unsorted attempt: {@code browseSync(...)} called with zero
     * {@code orderBy} vararg elements. Omitting the vararg matcher entirely
     * makes the matcher count (3) equal the actual flattened argument count
     * only when the real call's {@code orderBy} array is empty, i.e. the
     * unsorted/fallback attempt.
     */
    private void stubUnsortedAttempt(ContentDirectoryBrowseResult toReturn) {
        when(upnpClient.browseSync(any(Position.class), any(Long.class), any(Long.class)))
                .thenReturn(toReturn);
    }

    private void verifySortedAttemptCount(int times) {
        verify(upnpClient, times(times)).browseSync(any(Position.class), any(Long.class), any(Long.class), isA(SortCriterion.class));
    }

    private void verifyUnsortedAttemptCount(int times) {
        verify(upnpClient, times(times)).browseSync(any(Position.class), any(Long.class), any(Long.class));
    }

    @Test
    public void serverRejectionOfSortedBrowseFallsBackToUnsortedContentInsteadOfClearing() {
        BrowseContentItemAdapter adapter = BrowseContentItemAdapterSortTest.newAdapter(contentListFragment, recyclerView, upnpClient, progressBar);
        adapter.setSortMode(BrowseContentItemAdapter.SortMode.DATE); // navigator is null here -> loadMore() no-ops safely
        assertEquals(BrowseContentItemAdapter.SortMode.DATE, adapter.getSortMode());

        // Now that the adapter is in DATE mode, give it a real position for
        // the direct doInBackground(...) calls below.
        when(contentListFragment.getNavigator()).thenReturn(navigatorWithPosition);

        Container container = container("c1", "Folder");
        Item item = item("i1", "Song");
        stubSortedAttempt(rejectedResult());
        stubUnsortedAttempt(successResultWith(container, item));

        BrowseItemLoadTask firstTask = new BrowseItemLoadTask(adapter, 50L, null);
        ContentDirectoryBrowseResult firstResult = firstTask.doInBackground(0L);
        assertNotNull(firstResult);
        firstTask.onPostExecute(firstResult);

        // (a) adapter ends up populated with the fallback content, NOT cleared.
        assertEquals(2, adapter.getItemCount());

        // Exactly one sorted attempt (rejected) and one unsorted fallback
        // attempt were made for this first chunk request.
        verifySortedAttemptCount(1);
        verifyUnsortedAttemptCount(1);

        // A second chunk request for the same adapter/folder load (mode
        // still DATE) must not attempt the sorted overload again -- the
        // adapter remembers the first rejection.
        BrowseItemLoadTask secondTask = new BrowseItemLoadTask(adapter, 50L, null);
        ContentDirectoryBrowseResult secondResult = secondTask.doInBackground(2L);
        assertNotNull(secondResult);
        secondTask.onPostExecute(secondResult);

        // (b) still only ever one sorted-attempt call total; the second
        // chunk request went straight to the unsorted overload.
        verifySortedAttemptCount(1);
        verifyUnsortedAttemptCount(2);
    }

    @Test
    public void genuinelyEmptyUnsortedFolderStillShowsEmptyNotLoopingOrErroring() {
        BrowseContentItemAdapter adapter = BrowseContentItemAdapterSortTest.newAdapter(contentListFragment, recyclerView, upnpClient, progressBar);
        assertEquals(BrowseContentItemAdapter.SortMode.NAME, adapter.getSortMode());
        when(contentListFragment.getNavigator()).thenReturn(navigatorWithPosition);

        // NAME mode never sends orderBy; a genuinely empty (but successful)
        // unsorted result must simply show as an empty list -- not loop,
        // error, or trigger any sorted-attempt/retry machinery.
        ContentDirectoryBrowseResult emptyResult = new ContentDirectoryBrowseResult();
        emptyResult.setResult(new DIDLContent());
        stubUnsortedAttempt(emptyResult);

        BrowseItemLoadTask task = new BrowseItemLoadTask(adapter, 50L, null);
        ContentDirectoryBrowseResult result = task.doInBackground(0L);
        task.onPostExecute(result);

        assertEquals(0, adapter.getItemCount());
        // Only ever the single unsorted attempt -- no sorted attempt at all
        // in NAME mode, and no retry loop for a genuinely empty unsorted
        // result.
        verifyUnsortedAttemptCount(1);
        verifySortedAttemptCount(0);
    }
}
