/*
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.browser;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.res.ResourcesCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.AudioItem;
import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.PlaylistItem;
import org.fourthline.cling.support.model.item.TextItem;
import org.fourthline.cling.support.model.item.VideoItem;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.player.PlayableItem;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.ShareFileTask;
import de.yaacc.util.ConfigKeyedCache;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.YaaccLogger;
import de.yaacc.util.image.IconDownloadCacheHandler;
import de.yaacc.util.image.IconDownloadTask;

/**
 * Adapter for browsing devices.
 *
 * @author Christoph Haehnel (eyeless)
 */
public class BrowseContentItemAdapter extends RecyclerView.Adapter<BrowseContentItemAdapter.ViewHolder> {

    /**
     * The order in which browsed items are shown. See issue #252.
     */
    public enum SortMode {
        NAME,
        DATE
    }

    /**
     * Which layout presents the currently-loaded items. See
     * "Browse card-grid view" in docs/tech.md -- this only changes how
     * already-loaded items are drawn, never what data is loaded.
     */
    public enum ViewMode {
        LIST,
        CARD;

        /** Used when nothing is stored yet, e.g. on a fresh install, or the stored value is unreadable. */
        public static final ViewMode DEFAULT = CARD;
    }

    /**
     * Thumbnail/icon pixel size requested in LIST mode, matching
     * {@code browse_content_item.xml}'s 48dp icon.
     */
    private static final int LIST_ICON_SIZE_DP = 48;
    /**
     * Thumbnail/icon pixel size (both dimensions) requested in CARD mode,
     * matching {@code browse_content_item_card.xml}'s square
     * {@code SquareImageView} image area (width == height, enforced at
     * measure time since the card's actual width varies by span
     * count/screen size/density). A fixed non-square request here would
     * mismatch that square display area - either upscaling/softening one
     * axis or requesting more resolution than ever gets displayed on the
     * other. 320dp comfortably covers realistic per-card widths across the
     * card-grid's span-count range (see CardGridSpanCount) without
     * requesting an unreasonably large decode for a thumbnail.
     */
    private static final int CARD_ICON_SIZE_DP = 320;

    private boolean loading = false;


    private static final String TAG = "BrowseContentItemAdapter";
    /** Payload of the range change after the sort: the rows are rebound in place, without a change animation. */
    private static final Object SORT_PAYLOAD = new Object();

    // volatile: getObjects() is read from a worker thread; the sort swaps in a new list instead of mutating this one
    private volatile List<DIDLObject> objects = new ArrayList<>();
    private Context context;
    private List<AsyncTask> asyncTasks;
    private boolean allItemsFetched;
    private UpnpClient upnpClient;
    private ContentListFragment contentListFragment;
    private RecyclerView contentList;
    private ProgressBar progressBar;
    private SharedPreferences sharedPreferences;
    private boolean showThumbnails;
    private SortMode sortMode;
    private boolean dateSortAvailable;
    private boolean serverSortRejected;
    /**
     * True when the last chunk request of this folder load failed after all retries (see
     * {@link BrowseRetryPolicy}); the items already shown stay. Reset by the next successful
     * chunk and by {@link #clear()}. The reload after the network is back reads it.
     */
    private boolean loadFailed;
    /**
     * Name mode's own direction: true = A-Z (ascending), false = Z-A. Kept
     * independent of {@link #dateAscending} per issue #252 Group 4 --
     * switching sort mode never clobbers the other mode's remembered
     * direction. Defaults to {@code true} (A-Z), preserving current
     * behavior for existing users until they explicitly toggle it.
     */
    private boolean nameAscending;
    /**
     * Date mode's own direction: true = oldest-first (ascending), false =
     * newest-first (descending). Defaults to {@code false} (newest-first),
     * preserving current behavior for existing users until they explicitly
     * toggle it.
     */
    private boolean dateAscending;
    /**
     * Which layout ({@link ViewMode#LIST}/{@link ViewMode#CARD}) presents
     * the currently-loaded items. Persisted the same way {@link #sortMode}
     * is.
     */
    private ViewMode viewMode;

    // The client-side sort runs on a worker thread; main thread only, except where noted.
    /** Incremented by everything that makes a running sort stale: a new sort, clear, cancel, added objects. */
    private int sortGeneration;
    private boolean sortPending;
    /** A scroll target that arrived while the sort was running; applied after the swap, as the target is a position in the sorted list. */
    private Integer deferredScrollPositionId;
    private Executor sortWorker;
    private ExecutorService ownedSortExecutor;
    private Executor mainThread;
    private final ConfigKeyedCache<Integer, Drawable.ConstantState> typeIcons = new ConfigKeyedCache<>();


    public BrowseContentItemAdapter(ContentListFragment contentListFragment, RecyclerView contentList, UpnpClient upnpClient, ProgressBar progressBar) {
        context = contentListFragment.getContext();
        this.contentListFragment = contentListFragment;
        this.contentList = contentList;
        this.progressBar = progressBar;
        asyncTasks = new ArrayList<>();
        allItemsFetched = false;
        this.upnpClient = upnpClient;
        // Cache SharedPreferences lookup
        this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.showThumbnails = sharedPreferences.getBoolean(context.getString(R.string.settings_thumbnails_chkbx), true);
        this.sortMode = readPersistedSortMode();
        this.viewMode = readPersistedViewMode();
        this.dateSortAvailable = false;
        this.nameAscending = sharedPreferences.getBoolean(context.getString(R.string.settings_sort_name_ascending_key), true);
        this.dateAscending = sharedPreferences.getBoolean(context.getString(R.string.settings_sort_date_ascending_key), false);
    }

    private SortMode readPersistedSortMode() {
        String persisted = sharedPreferences.getString(context.getString(R.string.settings_sort_order_key), SortMode.NAME.name());
        try {
            return SortMode.valueOf(persisted);
        } catch (IllegalArgumentException | NullPointerException e) {
            return SortMode.NAME;
        }
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    private ViewMode readPersistedViewMode() {
        String persisted = sharedPreferences.getString(context.getString(R.string.settings_browse_view_mode_key), ViewMode.DEFAULT.name());
        try {
            return ViewMode.valueOf(persisted);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ViewMode.DEFAULT;
        }
    }

    public ViewMode getViewMode() {
        return viewMode;
    }

    /**
     * Changes which layout presents the currently-loaded items. Unlike
     * {@link #setSortMode(SortMode)}, this never clears/reloads -- the
     * backing data doesn't change, only how it's drawn, so a plain
     * {@link #notifyDataSetChanged()} is enough. No-op if {@code mode}
     * already matches the current mode.
     */
    // Layout mode changes the view type of every row.
    @SuppressLint("NotifyDataSetChanged")
    public void setViewMode(ViewMode mode) {
        if (mode == null || mode == this.viewMode) {
            return;
        }
        this.viewMode = mode;
        sharedPreferences.edit().putString(context.getString(R.string.settings_browse_view_mode_key), mode.name()).apply();
        notifyDataSetChanged();
    }

    /**
     * @return Name mode's own current direction: {@code true} for A-Z
     * (ascending), {@code false} for Z-A.
     */
    public boolean isNameAscending() {
        return nameAscending;
    }

    /**
     * @return Date mode's own current direction: {@code true} for
     * oldest-first (ascending), {@code false} for newest-first
     * (descending).
     */
    public boolean isDateAscending() {
        return dateAscending;
    }

    /**
     * Changes the current sort mode. If the mode actually changes, the
     * currently loaded items are cleared and reloaded so the new mode can
     * take effect server-side (see {@link BrowseItemLoadTask}) as well as
     * client-side (see {@link #sortObjects()}).
     */
    public void setSortMode(SortMode sortMode) {
        if (sortMode == null || sortMode == this.sortMode) {
            return;
        }
        this.sortMode = sortMode;
        cancelRunningTasks();
        clear();
        loadMore();
    }

    /**
     * Flips the <em>currently-selected</em> mode's own direction (Name:
     * A-Z &lt;-&gt; Z-A; Date: newest-first &lt;-&gt; oldest-first),
     * persists it, and reloads -- same as {@link #setSortMode(SortMode)}.
     * The other (not-currently-selected) mode's direction is left
     * untouched, per issue #252 Group 4.
     */
    public void toggleDirection() {
        if (sortMode == SortMode.DATE) {
            dateAscending = !dateAscending;
            sharedPreferences.edit().putBoolean(context.getString(R.string.settings_sort_date_ascending_key), dateAscending).apply();
        } else {
            nameAscending = !nameAscending;
            sharedPreferences.edit().putBoolean(context.getString(R.string.settings_sort_name_ascending_key), nameAscending).apply();
        }
        cancelRunningTasks();
        clear();
        loadMore();
    }

    /**
     * @return true if at least one currently loaded item carries a non-null
     * {@code dc:date} property, i.e. date sorting is meaningful for the
     * current folder.
     */
    public boolean isDateSortAvailable() {
        return dateSortAvailable;
    }

    /**
     * @return true once this folder load has already observed the server
     * reject a sorted ({@code orderBy}-bearing) Browse request (a UPnP
     * action failure, per issue #252's post-ship bug fix) -- reset in
     * {@link #clear()}. While true, {@link BrowseItemLoadTask} skips the
     * doomed sorted attempt entirely for subsequent chunk requests of the
     * same folder load.
     */
    public boolean isServerSortRejected() {
        return serverSortRejected;
    }

    /**
     * Records that the server rejected a sorted Browse request for the
     * current folder load, so later chunk requests skip straight to the
     * unsorted request. Reset on {@link #clear()} (i.e. on folder change or
     * sort mode change).
     */
    public void markServerSortRejected() {
        this.serverSortRejected = true;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getItemViewType(int position) {
        return viewMode.ordinal();
    }

    public Navigator getNavigator() {
        return contentListFragment.getNavigator();
    }


    public void setAllItemsFetched(boolean allItemsFetched) {
        boolean justCompleted = allItemsFetched && !this.allItemsFetched;
        this.allItemsFetched = allItemsFetched;
        if (justCompleted) {
            // The whole folder is now loaded: apply the client-side fallback
            // sort once (no mid-load reshuffle).
            startSort();
        }
    }

    public Context getContext() {
        return context;
    }

    /**
     * @return true while a chunk request is running
     */
    public boolean isLoading() {
        return loading;
    }

    /**
     * @return true if the last chunk request of the current folder load failed (after the
     * retries); what is shown is what was loaded before
     */
    public boolean isLoadFailed() {
        return loadFailed;
    }

    public void setLoadFailed(boolean loadFailed) {
        this.loadFailed = loadFailed;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
        // Show/hide progress bar
        if (progressBar != null) {
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
            YaaccLogger.d("BrowseContentItemAdapter", "Progress bar visibility set to: " + (loading ? "VISIBLE" : "GONE"));
        } else {
            YaaccLogger.e("BrowseContentItemAdapter", "Progress bar is null!");
        }
    }

    @Override
    public int getItemCount() {
        if (objects == null) {
            return 0;
        }
        return objects.size();
    }

    public void addAll(Collection<? extends DIDLObject> newObjects) {
        YaaccLogger.d(getClass().getName(), "adding objects: " + newObjects.size());
        int start = objects.size();
        // a hash lookup per new object: List.contains per new object is quadratic over a folder of thousands, on the main thread
        Set<DIDLObject> knownObjects = new HashSet<>(objects);
        List<DIDLObject> filteredObjects = newObjects.stream().filter(it -> !knownObjects.contains(it)).collect(Collectors.toList());
        YaaccLogger.d(getClass().getName(), "Adding " + filteredObjects.size() + " new objects (filtered from " + newObjects.size() + " total)");
        objects.addAll(filteredObjects);
        if (!dateSortAvailable) {
            // Recompute live as chunks arrive so the Date toggle can enable
            // itself as soon as any loaded item carries a dc:date, without
            // waiting for the whole folder to finish loading.
            dateSortAvailable = filteredObjects.stream().anyMatch(BrowseContentItemAdapter::hasDate);
        }
        notifyItemRangeInserted(start, filteredObjects.size());
        if (sortPending && !filteredObjects.isEmpty()) {
            // the running sort does not know these objects: sort again
            startSort();
        }
    }

    // The whole list is cleared; the previous item count is not tracked here.
    @SuppressLint("NotifyDataSetChanged")
    public void clear() {
        if (objects != null) {
            objects.clear();
        }
        loading = false;
        allItemsFetched = false;
        dateSortAvailable = false;
        serverSortRejected = false;
        loadFailed = false;
        dropPendingSort();
        notifyDataSetChanged();
    }

    /**
     * Client-side fallback sort, applied once the whole folder has been fetched (see
     * {@link #setAllItemsFetched(boolean)}). Only reorders what is already loaded -- never triggers additional
     * fetches. A folder of thousands of items means ten thousands of comparisons, so the sorted copy is computed
     * on a worker thread ({@link BrowseSorter}, same orderings as before); only the swap and the notification of
     * the changed range run on the main thread. A result that arrives after the folder, the sort mode or the
     * direction changed (or the list was cleared) is dropped.
     */
    private void startSort() {
        int count = objects.size();
        if (count < 2) {
            dropPendingSort();
            return;
        }
        final int generation = ++sortGeneration;
        sortPending = true;
        final SortMode mode = sortMode;
        final boolean nameAsc = nameAscending;
        final boolean dateAsc = dateAscending;
        final List<DIDLObject> snapshot = new ArrayList<>(objects);
        try {
            workerForSort().execute(() -> {
                long startNanos = System.nanoTime();
                BrowseSorter.Result result;
                try {
                    result = BrowseSorter.sort(snapshot, mode, nameAsc, dateAsc);
                } catch (RuntimeException e) {
                    YaaccLogger.e(TAG, "Browse sort failed, the list stays as loaded", e);
                    mainThreadExecutor().execute(() -> sortFailed(generation));
                    return;
                }
                YaaccLogger.d(TAG, "Browse sort n=" + count + " mode=" + mode + " in "
                        + (System.nanoTime() - startNanos) / 1_000_000L + " ms off the main thread");
                mainThreadExecutor().execute(() -> applySortResult(generation, result));
            });
        } catch (RejectedExecutionException e) {
            // released meanwhile
            YaaccLogger.w(TAG, "Browse sort not started: " + e.getMessage());
            sortPending = false;
        }
    }

    private void applySortResult(int generation, BrowseSorter.Result result) {
        if (generation != sortGeneration) {
            YaaccLogger.d(TAG, "Browse sort result dropped, the list changed meanwhile");
            return;
        }
        sortPending = false;
        objects = result.sorted;
        if (result.isChanged()) {
            notifyItemRangeChanged(result.firstChanged, result.changedCount(), SORT_PAYLOAD);
        }
        applyDeferredScroll();
    }

    private void sortFailed(int generation) {
        if (generation == sortGeneration) {
            sortPending = false;
            applyDeferredScroll();
        }
    }

    private void applyDeferredScroll() {
        Integer id = deferredScrollPositionId;
        deferredScrollPositionId = null;
        if (id != null) {
            scrollToPositionId(id);
        }
    }

    /** What a running sort would deliver is no longer wanted. */
    private void dropPendingSort() {
        sortGeneration++;
        sortPending = false;
        deferredScrollPositionId = null;
    }

    private Executor workerForSort() {
        if (sortWorker == null) {
            // one thread that ends by itself when idle, so nothing lingers if release() is never called
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "BrowseSort");
                        thread.setDaemon(true);
                        return thread;
                    });
            executor.allowCoreThreadTimeOut(true);
            ownedSortExecutor = executor;
            sortWorker = executor;
        }
        return sortWorker;
    }

    private Executor mainThreadExecutor() {
        if (mainThread == null) {
            Handler handler = new Handler(Looper.getMainLooper());
            mainThread = handler::post;
        }
        return mainThread;
    }

    /** For tests: runs the sort and its result without threads. */
    void setSortExecutors(Executor worker, Executor mainThread) {
        this.sortWorker = worker;
        this.mainThread = mainThread;
    }

    /**
     * Ends the sort thread and drops a running sort; to be called when the adapter is not used any more (the
     * view of its fragment is destroyed). The adapter can be used again, the thread is created on demand.
     */
    public void release() {
        dropPendingSort();
        if (ownedSortExecutor != null) {
            ownedSortExecutor.shutdownNow();
            ownedSortExecutor = null;
            sortWorker = null;
        }
    }

    private static boolean hasDate(DIDLObject object) {
        return BrowseSorter.hasDate(object);
    }

    public Object getItem(int position) {
        return objects.get(position);
    }

    /**
     * Returns the currently-loaded children in their current, on-screen
     * order -- i.e. already reflecting whatever client-side sort mode and
     * direction is active (see {@link #sortObjects()}). Used by {@link
     * ContentListFragment#playAllChildsOfParentFrom(DIDLObject)} (issue
     * play-all-ignores-sort-order) to build a playback queue that matches
     * what the user sees and tapped, instead of re-fetching the folder from
     * the server in its unsorted default order.
     *
     * Returns a defensive snapshot copy (wrapped unmodifiable) rather than a
     * live view over {@link #objects}, because this method is also called
     * from a background thread (see {@code ContentListFragment#
     * playAllChildsOfParentFrom}, invoked from {@code ContentItemPlayTask
     * #doInBackground}) while {@link #objects} is mutated in place from the
     * main thread ({@code addAll}/{@code clear}/{@code sortObjects}, driven
     * by {@code setSortMode}/{@code toggleDirection}/{@code loadMore}). A
     * live {@code Collections.unmodifiableList(objects)} view does not
     * protect against that: a concurrent mutation while the background
     * thread iterates/filters the view can still throw a
     * {@code ConcurrentModificationException} or yield a torn read.
     *
     * @return an unmodifiable snapshot copy of the adapter's current backing
     * list, taken at the time of the call; never {@code null}.
     */
    public List<DIDLObject> getObjects() {
        return Collections.unmodifiableList(new ArrayList<>(objects));
    }

    @Override
    public BrowseContentItemAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
                                                                  int viewType) {
        int layoutRes = viewType == ViewMode.CARD.ordinal()
                ? R.layout.browse_content_item_card
                : R.layout.browse_content_item;
        View view = LayoutInflater.from(parent.getContext())
                .inflate(layoutRes, parent, false);
        ContentListClickListener bItemClickListener = new ContentListClickListener(upnpClient, contentListFragment, contentList, this);
        view.setOnClickListener(bItemClickListener);
        view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            int position = contentList.getChildAdapterPosition(v);

            if (position == RecyclerView.NO_POSITION) return false;
            BrowseContentItemAdapter.ViewHolder holder = null;
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                    // Trigger normal click
                    v.performClick();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    // Focus the first visible action button of the item; without one the focus search goes on
                    holder = (BrowseContentItemAdapter.ViewHolder) contentList.getChildViewHolder(v);
                    View first = DpadActionNavigation.next(holder.actions(), null);
                    if (first != null) {
                        first.requestFocus();
                        return true;
                    }
                    return false;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    // The focus search moves to the previous item (card grid) or stays (list)
                    return false;
            }
            return false;
        });
        return new BrowseContentItemAdapter.ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final BrowseContentItemAdapter.ViewHolder holder, final int listPosition) {
        DIDLObject currentObject = (DIDLObject) getItem(listPosition);
        holder.name.setText(currentObject.getTitle());

        // new layout: each of these opens the receiver picker first; classic starts at once
        holder.playAll.setOnClickListener((v) ->
                contentListFragment.startPlayAction(currentObject, ContentItemPlayTask.PLAY_ALL, null, null));
        holder.play.setOnClickListener((v) ->
                contentListFragment.startPlayAction(currentObject, ContentItemPlayTask.PLAY_CURRENT, null, null));
        holder.playlistAdd.setOnClickListener((v) ->
                contentListFragment.startPlayAction(currentObject, ContentItemPlayTask.ADD_TO_PLAYLIST, () -> {
                    Toast toast = Toast.makeText(contentListFragment.getActivity(), R.string.add_to_playlist, Toast.LENGTH_SHORT);
                    toast.show();
                }, null));
        holder.download.setOnClickListener((v) -> {
            try {
                upnpClient.downloadItem(currentObject);
                Toast toast = Toast.makeText(contentListFragment.getActivity(), R.string.downloaded_to_target, Toast.LENGTH_LONG);
                toast.show();
            } catch (Exception ex) {
                Toast toast = Toast.makeText(contentListFragment.getActivity(), "Can't download item: " + ex.getMessage(), Toast.LENGTH_SHORT);
                toast.show();
            }
        });
        holder.share.setOnClickListener((v) -> {
            // Only Item subtypes (Audio/Image/Video) ever show this button
            // (see the per-type visibility below) - safe to treat
            // currentObject as an Item here.
            PlayableItem playableItem = new PlayableItem((Item) currentObject, 0);
            if (playableItem.getUri() == null) {
                // No resolvable resource on this item (selectBestResource
                // found none) - nothing either menu option could actually
                // do, so fail here rather than showing a menu that can
                // only lead to a broken share.
                Toast.makeText(getContext(), getContext().getString(R.string.browse_share_failed, "no resource"), Toast.LENGTH_SHORT).show();
                return;
            }
            // A single tap doesn't commit to one behavior: a folder's
            // resource URL is frequently only reachable on the local
            // network (this device's own embedded server, or another
            // server on the same LAN), which silently fails for a remote
            // recipient - but sharing the file itself costs a download
            // first. Offering both, chosen per tap, beats guessing once
            // globally (a hidden setting) or always paying the download
            // cost just in case.
            PopupMenu popupMenu = new PopupMenu(getContext(), v);
            popupMenu.getMenu().add(Menu.NONE, 0, 0, R.string.browse_share_menu_file);
            popupMenu.getMenu().add(Menu.NONE, 1, 1, R.string.browse_share_menu_link);
            popupMenu.setOnMenuItemClickListener(menuItem -> {
                if (menuItem.getItemId() == 0) {
                    Toast.makeText(getContext(), R.string.browse_share_preparing, Toast.LENGTH_SHORT).show();
                    new ShareFileTask(getContext(), playableItem.getUri(),
                            playableItem.getTitle(), playableItem.getMimeType()).execute();
                } else {
                    try {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_SUBJECT, playableItem.getTitle());
                        shareIntent.putExtra(Intent.EXTRA_TEXT, playableItem.getUri().toString());
                        getContext().startActivity(Intent.createChooser(shareIntent, getContext().getString(R.string.browse_context_share)));
                    } catch (Exception ex) {
                        Toast.makeText(getContext(), getContext().getString(R.string.browse_share_failed, ex.getMessage()), Toast.LENGTH_SHORT).show();
                    }
                }
                return true;
            });
            popupMenu.show();
        });
        // The type icon is the fallback and the only icon of most rows; a thumbnail (if wanted) is loaded over it.
        int typeIconRes;
        Uri thumbnailUri = null;
        if (currentObject instanceof Container) {
            typeIconRes = R.drawable.ic_baseline_folder_open_48;
            holder.playAll.setVisibility(View.VISIBLE);
            holder.play.setVisibility(View.VISIBLE);
            holder.download.setVisibility(View.GONE);
            holder.share.setVisibility(View.GONE);
            holder.playlistAdd.setVisibility(View.GONE);

        } else if (currentObject instanceof AudioItem) {
            typeIconRes = R.drawable.ic_baseline_audiotrack_48;
            holder.playAll.setVisibility(View.VISIBLE);
            holder.play.setVisibility(View.VISIBLE);
            holder.download.setVisibility(View.VISIBLE);
            holder.share.setVisibility(View.VISIBLE);
            holder.playlistAdd.setVisibility(View.VISIBLE);
            thumbnailUri = albumArtUri(currentObject);
        } else if (currentObject instanceof ImageItem) {
            typeIconRes = R.drawable.ic_baseline_image_48;
            holder.playAll.setVisibility(View.VISIBLE);
            holder.play.setVisibility(View.VISIBLE);
            holder.download.setVisibility(View.VISIBLE);
            holder.share.setVisibility(View.VISIBLE);
            holder.playlistAdd.setVisibility(View.GONE);
            if (showThumbnails && currentObject.getFirstResource() != null) {
                thumbnailUri = Uri.parse(currentObject.getFirstResource().getValue());
            }
        } else if (currentObject instanceof VideoItem) {
            typeIconRes = R.drawable.ic_baseline_movie_48;
            holder.playAll.setVisibility(View.VISIBLE);
            holder.play.setVisibility(View.VISIBLE);
            holder.download.setVisibility(View.VISIBLE);
            holder.share.setVisibility(View.VISIBLE);
            holder.playlistAdd.setVisibility(View.VISIBLE);
            thumbnailUri = albumArtUri(currentObject);
        } else if (currentObject instanceof PlaylistItem) {
            typeIconRes = R.drawable.ic_baseline_library_music_48;
            holder.playAll.setVisibility(View.GONE);
            holder.play.setVisibility(View.GONE);
            holder.download.setVisibility(View.GONE);
            holder.share.setVisibility(View.GONE);
            holder.playlistAdd.setVisibility(View.GONE);
        } else if (currentObject instanceof TextItem) {
            typeIconRes = R.drawable.ic_baseline_text_snippet_48;
            holder.playAll.setVisibility(View.GONE);
            holder.play.setVisibility(View.GONE);
            holder.download.setVisibility(View.GONE);
            holder.share.setVisibility(View.GONE);
            holder.playlistAdd.setVisibility(View.GONE);
        } else {
            holder.icon.clearAnimation(); // Clear any previous animation
            typeIconRes = R.drawable.ic_baseline_question_mark_48;
            holder.playAll.setVisibility(View.GONE);
            holder.play.setVisibility(View.GONE);
            holder.download.setVisibility(View.GONE);
            holder.share.setVisibility(View.GONE);
            holder.playlistAdd.setVisibility(View.GONE);
        }
        bindIcon(holder, typeIconRes, thumbnailUri);
    }

    /**
     * The album art of an audio or video item, if thumbnails are switched on and the item has one.
     */
    private Uri albumArtUri(DIDLObject object) {
        if (!showThumbnails) {
            return null;
        }
        DIDLObject.Property<URI> albumArt = object.getFirstProperty(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
        return albumArt == null ? null : Uri.parse(albumArt.getValue().toString());
    }

    /**
     * Shows the type icon and, if the row has a thumbnail, the thumbnail: from the memory cache at once, else
     * loaded by one task per recycled row. A bind of the request the row already shows or loads changes nothing,
     * so a re-bind (notify, sort) does not flash the fallback icon or start a second download; the task of the
     * request the row showed before is cancelled.
     */
    private void bindIcon(ViewHolder holder, int typeIconRes, Uri thumbnailUri) {
        int sizePx = dpToPx(viewMode == ViewMode.CARD ? CARD_ICON_SIZE_DP : LIST_ICON_SIZE_DP);
        String key = thumbnailUri == null ? null : ThumbnailSlot.key(thumbnailUri.toString(), sizePx, sizePx);
        Bitmap cached = thumbnailUri == null ? null : IconDownloadCacheHandler.getInstance().getBitmap(thumbnailUri, sizePx, sizePx);
        if (holder.thumbnailTask != null && holder.thumbnailTask.isCancelled()) {
            // cancelled with the folder load, its end has not been delivered yet
            holder.thumbnail.onFinished(holder.thumbnail.key(), false);
            holder.thumbnailTask = null;
        }
        ThumbnailSlot.Decision decision = holder.thumbnail.onBind(key, cached != null);
        if (decision == ThumbnailSlot.Decision.KEEP) {
            return;
        }
        cancelThumbnailTask(holder);
        // Reset every bind (RecyclerView view recycling) rather than only
        // when a thumbnail loads: CARD mode's fallback type-icon glyph
        // should stay at its natural size, centered on the tinted
        // background (browse_content_item_card.xml) -- CENTER_CROP would
        // stretch it to fill the whole width instead. Switched to
        // CENTER_CROP below, only at the point a real thumbnail is
        // shown or its load is actually kicked off. LIST mode is untouched
        // (default scaleType), unaffected by this card-only change.
        if (viewMode == ViewMode.CARD) {
            holder.icon.setScaleType(decision == ThumbnailSlot.Decision.NONE ? ImageView.ScaleType.CENTER_INSIDE : ImageView.ScaleType.CENTER_CROP);
        }
        holder.icon.setTag(key);
        if (decision == ThumbnailSlot.Decision.SHOW_CACHED) {
            holder.icon.setVisibility(View.VISIBLE);
            holder.icon.setImageBitmap(cached);
            return;
        }
        holder.icon.setImageDrawable(typeIcon(typeIconRes));
        if (decision == ThumbnailSlot.Decision.LOAD) {
            IconDownloadTask task = new IconDownloadTask(holder.icon, key, sizePx, sizePx, (finished, shown) -> {
                removeTask(finished);
                if (holder.thumbnailTask == finished) {
                    holder.thumbnailTask = null;
                }
                holder.thumbnail.onFinished(key, shown);
            });
            holder.thumbnailTask = task;
            asyncTasks.add(task);
            task.executeOnExecutor(((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor(), thumbnailUri);
        }
    }

    private void cancelThumbnailTask(ViewHolder holder) {
        IconDownloadTask task = holder.thumbnailTask;
        if (task != null) {
            holder.thumbnailTask = null;
            task.cancel(true);
            removeTask(task);
        }
    }

    /**
     * The tinted type icon: resolved and tinted once per icon and night mode, each row gets a new drawable from
     * the cached constant state (a drawable instance must not be shared between views).
     */
    private Drawable typeIcon(int drawableRes) {
        int nightMode = context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        Drawable.ConstantState state = typeIcons.get(nightMode, drawableRes, () -> {
            Drawable tinted = ThemeHelper.tintDrawable(ResourcesCompat.getDrawable(context.getResources(), drawableRes, context.getTheme()), context.getTheme());
            return tinted.getConstantState();
        });
        if (state != null) {
            return state.newDrawable(context.getResources(), context.getTheme());
        }
        return ThemeHelper.tintDrawable(ResourcesCompat.getDrawable(context.getResources(), drawableRes, context.getTheme()), context.getTheme());
    }

    public void cancelRunningTasks() {
        if (asyncTasks != null) {
            // cancelled tasks never run onPostExecute; a copy because the cancelled ones may call removeTask
            for (AsyncTask task : new ArrayList<>(asyncTasks)) {
                task.cancel(true);
            }
            // nothing in the list is of use once cancelled, and nothing else would remove it
            asyncTasks.clear();
        }
        loading = false;
        allItemsFetched = false;
        dropPendingSort();
    }

    public void removeTask(AsyncTask task) {
        if (asyncTasks != null && task != null) {
            asyncTasks.remove(task);
        }
    }

    @Override
    public void onViewRecycled(ViewHolder holder) {
        cancelThumbnailTask(holder);
        holder.thumbnail.release();
        holder.icon.setTag(null);
        super.onViewRecycled(holder);
    }

    public DIDLObject getFolder(int position) {
        if (objects == null) {
            return null;
        }
        return objects.get(position);
    }

    public void loadMore() {
        loadMore(Long.parseLong(PreferenceManager.getDefaultSharedPreferences(getContext()).getString(getContext().getString(R.string.settings_browse_chunk_size_key), "50")), null);

    }

    public void loadMore(Long itemsToLoad, Integer scrollToPositionId) {
        if (contentListFragment.getNavigator() == null || contentListFragment.getNavigator().getCurrentPosition() == null || contentListFragment.getNavigator().getCurrentPosition().getDeviceId() == null)
            return;
        if (loading || allItemsFetched) return;
        setLoading(true);
        Long from = (long) getItemCount();

        YaaccLogger.d(getClass().getName(), "loadMore from: " + from);

        BrowseItemLoadTask browseItemLoadTask = new BrowseItemLoadTask(this, itemsToLoad, scrollToPositionId);
        asyncTasks.add(browseItemLoadTask);
        browseItemLoadTask.executeOnExecutor(((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor(), from);

    }

    public void scrollToPositionId(Integer id) {
        if (id == null) return;
        if (sortPending) {
            // the position is one of the sorted list: scroll after the swap
            deferredScrollPositionId = id;
            return;
        }
        ((LinearLayoutManager) contentList.getLayoutManager()).scrollToPositionWithOffset(id, 0);
    }

    /**
     * Converts a dp value to pixels using the adapter's own
     * {@link Context}'s display metrics, for sizing thumbnail/icon
     * download requests per {@link #viewMode} (see {@code docs/tech.md}'s
     * "Browse card-grid view" section).
     */
    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        /** The thumbnail request of this recycled row and its running task (main thread only). */
        final ThumbnailSlot thumbnail = new ThumbnailSlot();
        IconDownloadTask thumbnailTask;
        TextView name;
        ImageButton play;
        ImageButton playAll;
        ImageButton download;
        ImageButton playlistAdd;
        ImageButton share;

        /** The action buttons in the order they are shown, left to right. */
        List<View> actions() {
            return java.util.Arrays.asList(playlistAdd, download, play, playAll, share);
        }

        public ViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.browseContentItemIcon);
            name = itemView.findViewById(R.id.browseContentItemName);
            play = itemView.findViewById(R.id.browseContentItemPlay);
            playAll = itemView.findViewById(R.id.browseContentItemPlayAll);
            download = itemView.findViewById(R.id.browseContentItemDownload);
            playlistAdd = itemView.findViewById(R.id.browseContentItemPlaylistAdd);
            share = itemView.findViewById(R.id.browseContentItemShare);
            // Ensure buttons are reachable via DPAD when focused from row
            View.OnKeyListener actionKeyListener = (v, keyCode, event) -> {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case android.view.KeyEvent.KEYCODE_DPAD_LEFT: {
                        // previous action button, from the first one back to the item itself
                        View previous = DpadActionNavigation.previous(actions(), v);
                        (previous != null ? previous : itemView).requestFocus();
                        return true;
                    }
                    case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: {
                        // next action button; behind the last one the focus search goes on to the next item
                        View next = DpadActionNavigation.next(actions(), v);
                        if (next != null) {
                            next.requestFocus();
                            return true;
                        }
                        return false;
                    }
                    case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    case android.view.KeyEvent.KEYCODE_DPAD_UP:
                        // Let RecyclerView handle vertical navigation
                        return false;
                }
                return false;
            };
            play.setOnKeyListener(actionKeyListener);
            playAll.setOnKeyListener(actionKeyListener);
            download.setOnKeyListener(actionKeyListener);
            playlistAdd.setOnKeyListener(actionKeyListener);
            share.setOnKeyListener(actionKeyListener);
        }
    }
}
