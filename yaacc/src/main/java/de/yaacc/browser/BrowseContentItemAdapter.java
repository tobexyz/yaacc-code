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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.LinkedList;
import java.util.List;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.image.IconDownloadTask;

/**
 * Adapter for browsing devices.
 *
 * @author Christoph Haehnel (eyeless)
 */
public class BrowseContentItemAdapter extends RecyclerView.Adapter<BrowseContentItemAdapter.ViewHolder> {
    public static final Item LOAD_MORE_FAKE_ITEM = new Item("LoadMoreFakeItem", (String) null, "...", "", (DIDLObject.Class) null);

    private static final Item LOADING_FAKE_ITEM = new Item("LoadingFakeItem", (String) null, "Loading...", "", (DIDLObject.Class) null);
    private boolean loading = false;


    private List<DIDLObject> objects = new LinkedList<>();
    private Context context;
    private Navigator navigator;
    private List<AsyncTask> asyncTasks;
    private boolean allItemsFetched;
    private UpnpClient upnpClient;
    private ContentListFragment contentListFragment;
    private RecyclerView contentList;


    public BrowseContentItemAdapter(ContentListFragment contentListFragment, RecyclerView contentList, UpnpClient upnpClient, Navigator navigator) {
        context = contentListFragment.getContext();
        this.contentListFragment = contentListFragment;
        this.contentList = contentList;
        this.navigator = navigator;
        asyncTasks = new ArrayList<>();
        allItemsFetched = false;
        this.upnpClient = upnpClient;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getItemViewType(int position) {
        return position;
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
        this.loading = loading;
    }

    @Override
    public int getItemCount() {
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

    public void addAll(Collection<? extends DIDLObject> newObjects) {
        Log.d(getClass().getName(), "added objects; " + newObjects);
        int start = objects.size() - 1;
        objects.addAll(newObjects);
        notifyItemRangeInserted(start, objects.size());
    }

    public void clear() {
        objects.clear();
        loading = false;
        allItemsFetched = false;
        notifyDataSetChanged();
    }

    public Object getItem(int position) {
        return objects.get(position);
    }

    @Override
    public BrowseContentItemAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
                                                                  int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.browse_content_item, parent, false);
        ContentListClickListener bItemClickListener = new ContentListClickListener(upnpClient, contentListFragment, contentList, this);
        view.setOnClickListener(bItemClickListener);
        return new BrowseContentItemAdapter.ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final BrowseContentItemAdapter.ViewHolder holder, final int listPosition) {
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);

        DIDLObject currentObject = (DIDLObject) getItem(listPosition);
        holder.name.setText(currentObject.getTitle());
        IconDownloadTask iconDownloadTask = new IconDownloadTask(holder.icon,
                this);
        asyncTasks.add(iconDownloadTask);

        holder.playAll.setOnClickListener((v) -> {
            new ContentItemPlayTask(contentListFragment, currentObject).execute(ContentItemPlayTask.PLAY_ALL);
        });
        holder.play.setOnClickListener((v) -> {
            new ContentItemPlayTask(contentListFragment, currentObject).execute(ContentItemPlayTask.PLAY_CURRENT);
        });
        holder.download.setOnClickListener((v) -> {
            try {
                upnpClient.downloadItem(currentObject);
            } catch (Exception ex) {
                Toast toast = Toast.makeText(contentListFragment.getActivity(), "Can't download item: " + ex.getMessage(), Toast.LENGTH_SHORT);
                toast.show();
            }
        });
        if (currentObject instanceof Container) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_folder_open_48, context.getTheme()), getContext().getTheme()));
            holder.playAll.setVisibility(View.VISIBLE);
            holder.play.setVisibility(View.VISIBLE);
            holder.download.setVisibility(View.GONE);

        } else if (currentObject instanceof AudioItem) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_audiotrack_48, context.getTheme()), getContext().getTheme()));
            holder.playAll.setVisibility(View.VISIBLE);
            holder.play.setVisibility(View.VISIBLE);
            holder.download.setVisibility(View.VISIBLE);
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
            holder.playAll.setVisibility(View.VISIBLE);
            holder.play.setVisibility(View.VISIBLE);
            holder.download.setVisibility(View.VISIBLE);
            if (preferences.getBoolean(
                    context.getString(R.string.settings_thumbnails_chkbx),
                    true))
                iconDownloadTask.executeOnExecutor(((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor(),
                        Uri.parse(((ImageItem) currentObject)
                                .getFirstResource().getValue()));
        } else if (currentObject instanceof VideoItem) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_movie_48, getContext().getTheme()), getContext().getTheme()));
            holder.playAll.setVisibility(View.VISIBLE);
            holder.play.setVisibility(View.VISIBLE);
            holder.download.setVisibility(View.VISIBLE);
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
            holder.playAll.setVisibility(View.GONE);
            holder.play.setVisibility(View.GONE);
            holder.download.setVisibility(View.GONE);
        } else if (currentObject instanceof TextItem) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_text_snippet_48, getContext().getTheme()), getContext().getTheme()));
            holder.playAll.setVisibility(View.GONE);
            holder.play.setVisibility(View.GONE);
            holder.download.setVisibility(View.GONE);
        } else if (currentObject == LOAD_MORE_FAKE_ITEM) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_refresh_48, getContext().getTheme()), getContext().getTheme()));
        } else if (currentObject == LOADING_FAKE_ITEM) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_download_48, getContext().getTheme()), getContext().getTheme()));
            holder.playAll.setVisibility(View.GONE);
            holder.play.setVisibility(View.GONE);
            holder.download.setVisibility(View.GONE);
        } else {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_question_mark_48, getContext().getTheme()), getContext().getTheme()));
            holder.playAll.setVisibility(View.GONE);
            holder.play.setVisibility(View.GONE);
            holder.download.setVisibility(View.GONE);
        }
    }

    public void cancelRunningTasks() {
        if (asyncTasks != null) {
            for (AsyncTask task : asyncTasks) {
                task.cancel(true);
            }
        }
        loading = false;
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
            notifyItemInserted(objects.size() - 1);
        }

    }

    public void addLoadingItem() {
        if (!objects.contains(LOADING_FAKE_ITEM)) {
            objects.add(LOADING_FAKE_ITEM);
            notifyItemInserted(objects.size() - 1);
        }

    }

    public void removeLoadMoreItem() {
        int idx = objects.indexOf(LOAD_MORE_FAKE_ITEM);
        if (idx > -1) {
            objects.remove(LOAD_MORE_FAKE_ITEM);
            notifyItemRemoved(idx);
        }
    }

    public void removeLoadingItem() {
        int idx = objects.indexOf(LOADING_FAKE_ITEM);
        if (idx > -1) {
            objects.remove(LOADING_FAKE_ITEM);
            notifyItemRemoved(idx);
        }
    }

    public DIDLObject getFolder(int position) {
        if (objects == null) {
            return null;
        }
        return objects.get(position);
    }

    public void loadMore() {
        if (navigator == null || navigator.getCurrentPosition() == null || navigator.getCurrentPosition().getDeviceId() == null)
            return;
        if (loading || allItemsFetched) return;
        setLoading(true);
        Long from = (long) getItemCount();

        Log.d(getClass().getName(), "loadMore from: " + from);

        BrowseItemLoadTask browseItemLoadTask = new BrowseItemLoadTask(this, Long.parseLong(PreferenceManager.getDefaultSharedPreferences(getContext()).getString(getContext().getString(R.string.settings_browse_chunk_size_key), "50")));
        asyncTasks.add(browseItemLoadTask);
        browseItemLoadTask.executeOnExecutor(((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor(), from);

    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;
        ImageButton play;
        ImageButton playAll;
        ImageButton download;

        public ViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.browseContentItemIcon);
            name = itemView.findViewById(R.id.browseContentItemName);
            play = itemView.findViewById(R.id.browseContentItemPlay);
            playAll = itemView.findViewById(R.id.browseContentItemPlayAll);
            download = itemView.findViewById(R.id.browseContentItemDownload);
        }
    }
}
