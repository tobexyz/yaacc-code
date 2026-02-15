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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.AudioItem;
import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.PlaylistItem;
import org.fourthline.cling.support.model.item.TextItem;
import org.fourthline.cling.support.model.item.VideoItem;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.YaaccLogger;
import de.yaacc.util.image.IconDownloadTask;

/**
 * Adapter for browsing devices.
 *
 * @author Christoph Haehnel (eyeless)
 */
public class BrowseContentItemAdapter extends RecyclerView.Adapter<BrowseContentItemAdapter.ViewHolder> {
    private boolean loading = false;


    private List<DIDLObject> objects = new LinkedList<>();
    private Context context;
    private List<AsyncTask> asyncTasks;
    private boolean allItemsFetched;
    private UpnpClient upnpClient;
    private ContentListFragment contentListFragment;
    private RecyclerView contentList;
    private ProgressBar progressBar;


    public BrowseContentItemAdapter(ContentListFragment contentListFragment, RecyclerView contentList, UpnpClient upnpClient, ProgressBar progressBar) {
        context = contentListFragment.getContext();
        this.contentListFragment = contentListFragment;
        this.contentList = contentList;
        this.progressBar = progressBar;
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
        return contentListFragment.getNavigator();
    }


    public void setAllItemsFetched(boolean allItemsFetched) {
        this.allItemsFetched = allItemsFetched;
    }

    public Context getContext() {
        return context;
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
        YaaccLogger.d(getClass().getName(), "added objects; " + newObjects);
        int start = objects.size();
        List<DIDLObject> filteredObjects = newObjects.stream().filter(it -> !objects.contains(it)).collect(Collectors.toList());
        YaaccLogger.d(getClass().getName(), "Adding " + filteredObjects.size() + " new objects (filtered from " + newObjects.size() + " total)");
        objects.addAll(filteredObjects);
        notifyItemRangeInserted(start, filteredObjects.size());
    }

    public void clear() {
        if (objects != null) {
            objects.clear();
        }
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
                    // Focus first visible action button
                    holder = (BrowseContentItemAdapter.ViewHolder) contentList.getChildViewHolder(v);
                    if (holder.playlistAdd.getVisibility() == View.VISIBLE) {
                        holder.playlistAdd.requestFocus();
                        return true;
                    }
                    if (holder.download.getVisibility() == View.VISIBLE) {
                        holder.download.requestFocus();
                        return true;
                    }
                    if (holder.play.getVisibility() == View.VISIBLE) {
                        holder.play.requestFocus();
                        return true;
                    }
                    return false;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    holder = (BrowseContentItemAdapter.ViewHolder) contentList.getChildViewHolder(v);
                    View focus = v.findFocus();
                    if (holder.playAll.hasFocus()) {
                        holder.play.requestFocus();
                        return true;
                    }
                    if (holder.play.hasFocus() && holder.download.getVisibility() == View.VISIBLE) {
                        holder.download.requestFocus();
                        return true;
                    }
                    if (holder.download.hasFocus() && holder.playlistAdd.getVisibility() == View.VISIBLE) {
                        holder.playlistAdd.requestFocus();
                        return true;
                    }
                    // Let parent handle; if we are on first column maybe switch tabs later
                    return true;
            }
            return false;
        });
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
        holder.playlistAdd.setOnClickListener((v) -> {
            new ContentItemPlayTask(contentListFragment, currentObject).execute(ContentItemPlayTask.ADD_TO_PLAYLIST);
            Toast toast = Toast.makeText(contentListFragment.getActivity(), R.string.add_to_playlist, Toast.LENGTH_SHORT);
            toast.show();
        });
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
        if (currentObject instanceof Container) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_folder_open_48, context.getTheme()), getContext().getTheme()));
            holder.playAll.setVisibility(View.VISIBLE);
            holder.play.setVisibility(View.VISIBLE);
            holder.download.setVisibility(View.GONE);
            holder.playlistAdd.setVisibility(View.GONE);

        } else if (currentObject instanceof AudioItem) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_audiotrack_48, context.getTheme()), getContext().getTheme()));
            holder.playAll.setVisibility(View.VISIBLE);
            holder.play.setVisibility(View.VISIBLE);
            holder.download.setVisibility(View.VISIBLE);
            holder.playlistAdd.setVisibility(View.VISIBLE);
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
            holder.playlistAdd.setVisibility(View.GONE);
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
            holder.playlistAdd.setVisibility(View.VISIBLE);
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
            holder.playlistAdd.setVisibility(View.GONE);
        } else if (currentObject instanceof TextItem) {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_text_snippet_48, getContext().getTheme()), getContext().getTheme()));
            holder.playAll.setVisibility(View.GONE);
            holder.play.setVisibility(View.GONE);
            holder.download.setVisibility(View.GONE);
            holder.playlistAdd.setVisibility(View.GONE);
        } else {
            holder.icon.clearAnimation(); // Clear any previous animation
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(getContext().getResources().getDrawable(R.drawable.ic_baseline_question_mark_48, getContext().getTheme()), getContext().getTheme()));
            holder.playAll.setVisibility(View.GONE);
            holder.play.setVisibility(View.GONE);
            holder.download.setVisibility(View.GONE);
            holder.playlistAdd.setVisibility(View.GONE);
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
        ((LinearLayoutManager) contentList.getLayoutManager()).scrollToPositionWithOffset(id, 0);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;
        ImageButton play;
        ImageButton playAll;
        ImageButton download;
        ImageButton playlistAdd;

        public ViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.browseContentItemIcon);
            name = itemView.findViewById(R.id.browseContentItemName);
            play = itemView.findViewById(R.id.browseContentItemPlay);
            playAll = itemView.findViewById(R.id.browseContentItemPlayAll);
            download = itemView.findViewById(R.id.browseContentItemDownload);
            playlistAdd = itemView.findViewById(R.id.browseContentItemPlaylistAdd);
            // Ensure buttons are reachable via DPAD when focused from row
            View.OnKeyListener actionKeyListener = (v, keyCode, event) -> {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                        // Move focus back to row root
                        itemView.requestFocus();
                        return true;
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
        }
    }
}
