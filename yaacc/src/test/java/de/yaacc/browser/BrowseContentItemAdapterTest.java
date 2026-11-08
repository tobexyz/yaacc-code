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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ProgressBar;

import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.container.Container;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

import de.yaacc.upnp.UpnpClient;

/**
 * Tests for {@link BrowseContentItemAdapter}'s {@code ViewMode} support
 * ("Browse card-grid view" in docs/tech.md).
 * <p>
 * Reuses {@link BrowseContentItemAdapterSortTest#newAdapter} for the same
 * plain-JVM {@code RecyclerView.Adapter} notify-safety workaround already
 * established for this package's adapter tests (see that method's javadoc
 * for why it's needed) -- same mock-scaffolding convention as {@link
 * BrowseItemLoadTaskTest}.
 * </p>
 */
public class BrowseContentItemAdapterTest {

    private ContentListFragment contentListFragment;
    private UpnpClient upnpClient;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private SharedPreferences.Editor sharedPreferencesEditor;

    @Before
    public void setUp() {
        Context context = mock(Context.class);
        SharedPreferences sharedPreferences = mock(SharedPreferences.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);
        when(sharedPreferences.getBoolean(any(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
        when(sharedPreferences.getString(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        sharedPreferencesEditor = mock(SharedPreferences.Editor.class);
        when(sharedPreferences.edit()).thenReturn(sharedPreferencesEditor);
        // context.getString(int) is unstubbed here (returns null), so the
        // key argument these edits pass is null -- match with any() rather
        // than anyString(), which does not match null (same convention as
        // BrowseContentItemAdapterSortTest).
        when(sharedPreferencesEditor.putBoolean(any(), anyBoolean())).thenReturn(sharedPreferencesEditor);
        when(sharedPreferencesEditor.putString(any(), any())).thenReturn(sharedPreferencesEditor);

        contentListFragment = mock(ContentListFragment.class);
        when(contentListFragment.getContext()).thenReturn(context);
        // getNavigator() is intentionally left unstubbed (returns null), so
        // that any reload BrowseContentItemAdapter#setSortMode(...) would
        // trigger (not relevant here -- setViewMode never reloads) short-
        // circuits safely instead of touching the network/AsyncTask
        // machinery -- see BrowseContentItemAdapterSortTest.

        upnpClient = mock(UpnpClient.class);
        recyclerView = mock(RecyclerView.class);
        progressBar = mock(ProgressBar.class);
    }

    private BrowseContentItemAdapter newAdapter() {
        return BrowseContentItemAdapterSortTest.newAdapter(contentListFragment, recyclerView, upnpClient, progressBar);
    }

    private static Container container(String id, String title) {
        return new Container(id, "0", title, "creator", (DIDLObject.Class) null, 0);
    }

    /**
     * {@code RecyclerView.Adapter#registerAdapterDataObserver} delegates to
     * {@code android.database.Observable#registerObserver}, a platform
     * ({@code android.*}) method stubbed to a no-op under this project's
     * plain-JVM {@code unitTests.returnDefaultValues = true} setup (same
     * root cause as {@link BrowseContentItemAdapterSortTest
     * #fixUpAdapterDataObservable} -- that one only initializes the
     * {@code mObservers} field, it doesn't make the inherited register/
     * notify methods work for an observer added via the public API). This
     * adds the observer directly into that real (already-initialized)
     * {@code ArrayList}, so the adapter's own overridden {@code
     * notifyDataSetChanged()} -&gt; {@code AdapterDataObservable
     * #notifyChanged()} (androidx code, not platform-stubbed) actually
     * invokes it.
     */
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
            throw new IllegalStateException("Could not register the observer directly, working around the "
                    + "android.database.Observable stub-method limitation of this plain-JVM test environment", e);
        }
    }

    // 1. getItemViewType returns the ordinal matching the current mode.
    @Test
    public void getItemViewTypeReturnsOrdinalOfCurrentViewMode() {
        BrowseContentItemAdapter adapter = newAdapter();
        // nothing persisted (a fresh install): the card view is the default
        assertEquals(BrowseContentItemAdapter.ViewMode.CARD, adapter.getViewMode());

        adapter.addAll(java.util.List.of(container("c1", "Folder")));

        assertEquals(BrowseContentItemAdapter.ViewMode.CARD.ordinal(), adapter.getItemViewType(0));

        adapter.setViewMode(BrowseContentItemAdapter.ViewMode.LIST);

        assertEquals(BrowseContentItemAdapter.ViewMode.LIST.ordinal(), adapter.getItemViewType(0));
    }

    // 2. setViewMode with a new mode persists the pref and triggers exactly
    // one notifyDataSetChanged()-equivalent observer call, with no reload
    // (the backing data stays exactly as it was).
    @Test
    public void setViewModeWithNewModePersistsAndNotifiesWithoutReloading() {
        BrowseContentItemAdapter adapter = newAdapter();
        adapter.addAll(java.util.List.of(container("c1", "Folder")));
        assertEquals(1, adapter.getItemCount());

        RecyclerView.AdapterDataObserver observer = mock(RecyclerView.AdapterDataObserver.class);
        registerObserverDirectly(adapter, observer);

        adapter.setViewMode(BrowseContentItemAdapter.ViewMode.LIST);

        assertEquals(BrowseContentItemAdapter.ViewMode.LIST, adapter.getViewMode());
        // Persisted.
        verify(sharedPreferencesEditor, times(1)).putString(any(), eq(BrowseContentItemAdapter.ViewMode.LIST.name()));
        // Notified (notifyDataSetChanged()).
        verify(observer, times(1)).onChanged();
        // No reload: the previously-loaded item is still there untouched,
        // i.e. no clear()/loadMore() happened -- clear() would have emptied
        // the backing list.
        assertEquals(1, adapter.getItemCount());
        assertEquals("Folder", adapter.getFolder(0).getTitle());
    }

    // 3. setViewMode with the same (already-current) mode is a no-op: no
    // pref write, no notify.
    @Test
    public void setViewModeWithSameModeIsANoOp() {
        BrowseContentItemAdapter adapter = newAdapter();
        assertEquals(BrowseContentItemAdapter.ViewMode.CARD, adapter.getViewMode());

        RecyclerView.AdapterDataObserver observer = mock(RecyclerView.AdapterDataObserver.class);
        registerObserverDirectly(adapter, observer);

        adapter.setViewMode(BrowseContentItemAdapter.ViewMode.CARD);

        assertEquals(BrowseContentItemAdapter.ViewMode.CARD, adapter.getViewMode());
        verify(sharedPreferencesEditor, never()).putString(any(), any());
        verify(observer, never()).onChanged();
    }
}
