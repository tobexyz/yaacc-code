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

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

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
import java.util.LinkedList;
import java.util.List;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.image.IconDownloadTask;

/**
 * Adapter for browsing devices.
 *
 * @author Christoph Haehnel (eyeless)
 */
public class BrowseContentItemAdapter extends BaseAdapter implements AbsListView.OnScrollListener {
    public static final Item LOAD_MORE_FAKE_ITEM = new Item("LoadMoreFakeItem", (String) null, "...", "", (DIDLObject.Class) null);

    private static final Item LOADING_FAKE_ITEM = new Item("LoadingFakeItem", (String) null, "Loading...", "", (DIDLObject.Class) null);
    private boolean loading = false;


    private LayoutInflater inflator;
    private List<DIDLObject> objects = new LinkedList<>();
    private Context context;
    private Navigator navigator;
    private List<AsyncTask> asyncTasks;
    private boolean allItemsFetched;


    public BrowseContentItemAdapter(Context ctx, Navigator navigator) {
        initialize(ctx, navigator);
    }

    private void initialize(Context ctx, Navigator navigator) {
        inflator = LayoutInflater.from(ctx);
        context = ctx;
        this.navigator = navigator;
        asyncTasks = new ArrayList<>();
        allItemsFetched = false;
        loadMore();

    }

    public Navigator getNavigator() {
        return navigator;
    }

    public void setAllItemsFetched(boolean allItemsFetched) {
        this.allItemsFetched = allItemsFetched;
    }

    public Context getContext() {
        return context;
    }

    public void setLoading(boolean loading) {
        if (loading) {
            addLoadingItem();
        } else {
            removeLoadingItem();
        }
        notifyDataSetChanged();
        this.loading = loading;
    }

    @Override
    public int getCount() {
        if (objects == null) {
            return 0;
        }
        int result = objects.size();
        if (objects.contains(LOAD_MORE_FAKE_ITEM)) {
            result--;
        }
        if (objects.contains(LOADING_FAKE_ITEM)) {
            result--;
        }
        return result;
    }

    public void addAll(Collection<? extends DIDLObject> objects) {
        Log.d(getClass().getName(), "added objects; " + objects);
        this.objects.addAll(objects);
        notifyDataSetChanged();
    }

    public void clear() {
        objects = new LinkedList<>();
        allItemsFetched = true;
        notifyDataSetChanged();
    }

    @Override
    public Object getItem(int arg0) {
        return objects.get(arg0);
    }

    @Override
    public long getItemId(int arg0) {
        return arg0;
    }

    @Override
    public View getView(int position, View arg1, ViewGroup parent) {
        ViewHolder holder;
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(parent.getContext());
        context = parent.getContext();
        if (arg1 == null) {
            arg1 = inflator.inflate(R.layout.browse_content_item, parent, false);
            holder = new ViewHolder();
            holder.icon = arg1.findViewById(R.id.browseContentItemIcon);
            holder.name = arg1.findViewById(R.id.browseContentItemName);
            arg1.setTag(holder);
        } else {
            holder = (ViewHolder) arg1.getTag();
        }


        DIDLObject currentObject = (DIDLObject) getItem(position);
        holder.name.setText(currentObject.getTitle());
        IconDownloadTask iconDownloadTask = new IconDownloadTask(
                this, (ListView) parent, R.id.browseContentItemIcon, position);
        asyncTasks.add(iconDownloadTask);
        if (currentObject instanceof Container) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_folder_open_48, context.getTheme()), getContext().getTheme()));
        } else if (currentObject instanceof AudioItem) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_audiotrack_48, context.getTheme()), getContext().getTheme()));
            if (preferences.getBoolean(
                    context.getString(R.string.settings_thumbnails_chkbx),
                    true)) {
                DIDLObject.Property<URI> albumArtProperties = ((AudioItem) currentObject)
                        .getFirstProperty(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
                if (null != albumArtProperties) {
                    iconDownloadTask.executeOnExecutor(((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor(),
                            Uri.parse(albumArtProperties
                                    .getValue().toString()));
                }
            }
        } else if (currentObject instanceof ImageItem) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_image_48, getContext().getTheme()), getContext().getTheme()));
            if (preferences.getBoolean(
                    context.getString(R.string.settings_thumbnails_chkbx),
                    true))
                iconDownloadTask.executeOnExecutor(((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor(),
                        Uri.parse(((ImageItem) currentObject)
                                .getFirstResource().getValue()));
        } else if (currentObject instanceof VideoItem) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_movie_48, getContext().getTheme()), getContext().getTheme()));
            if (preferences.getBoolean(
                    context.getString(R.string.settings_thumbnails_chkbx),
                    true)) {
                DIDLObject.Property<URI> albumArtProperties = ((VideoItem) currentObject)
                        .getFirstProperty(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
                if (null != albumArtProperties) {
                    iconDownloadTask.executeOnExecutor(((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor(),
                            Uri.parse(albumArtProperties
                                    .getValue().toString()));
                }
            }
        } else if (currentObject instanceof PlaylistItem) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_library_music_48, getContext().getTheme()), getContext().getTheme()));
        } else if (currentObject instanceof TextItem) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_text_snippet_48, getContext().getTheme()), getContext().getTheme()));
        } else if (currentObject == LOAD_MORE_FAKE_ITEM) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_refresh_48, getContext().getTheme()), getContext().getTheme()));
        } else if (currentObject == LOADING_FAKE_ITEM) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_download_48, getContext().getTheme()), getContext().getTheme()));
        } else {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_question_mark_48, getContext().getTheme()), getContext().getTheme()));
        }
        return arg1;
    }

    public void cancelRunningTasks() {
        if (asyncTasks != null) {
            for (AsyncTask task : asyncTasks) {
                task.cancel(true);
            }
        }
        allItemsFetched = false;
    }

    public void removeTask(AsyncTask task) {
        if (asyncTasks != null && task != null) {
            asyncTasks.remove(task);
        }
    }

    public void addLoadMoreItem() {
        if (!objects.contains(LOAD_MORE_FAKE_ITEM)) {
            objects.add(LOAD_MORE_FAKE_ITEM);
            notifyDataSetChanged();
        }

    }

    public void addLoadingItem() {
        if (!objects.contains(LOADING_FAKE_ITEM)) {
            objects.add(LOADING_FAKE_ITEM);
            notifyDataSetChanged();
        }

    }

    public void removeLoadMoreItem() {
        objects.remove(LOAD_MORE_FAKE_ITEM);
        notifyDataSetChanged();
    }

    public void removeLoadingItem() {
        objects.remove(LOADING_FAKE_ITEM);
        notifyDataSetChanged();
    }

    public DIDLObject getFolder(int position) {
        if (objects == null) {
            return null;
        }
        return objects.get(position);
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem,
                         int visibleItemCount, int totalItemCount) {
        // check if the List needs more data
        if ((firstVisibleItem + visibleItemCount) > (totalItemCount - 10)) {
            // List needs more data. Go fetch !!
            loadMore();
        }
    }

    public void loadMore() {
        if (navigator == null || navigator.getCurrentPosition() == null || navigator.getCurrentPosition().getDeviceId() == null)
            return;
        if (loading || allItemsFetched) return;
        setLoading(true);
        Long from = (long) getCount();

        Log.d(getClass().getName(), "loadMore from: " + from);

        BrowseItemLoadTask browseItemLoadTask = new BrowseItemLoadTask(this, Long.parseLong(PreferenceManager.getDefaultSharedPreferences(getContext()).getString(getContext().getString(R.string.settings_browse_chunk_size_key), "50")));
        asyncTasks.add(browseItemLoadTask);
        browseItemLoadTask.executeOnExecutor(((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor(), from);

    }

    static class ViewHolder {
        ImageView icon;
        TextView name;
    }

}