/*
 *
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
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
package de.yaacc.upnp.server.contentdirectory;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.preference.PreferenceManager;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.Item;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;

/**
 * Browser  for the root folder.
 *
 * @author openbit (Tobias Schoene)
 */
public class RootFolderBrowser extends ContentBrowser {
    public RootFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        return new StorageFolder(ContentDirectoryIDs.ROOT.getId(), ContentDirectoryIDs.PARENT_OF_ROOT.getId(), "Yaacc", "yaacc", getSize(contentDirectory, myId),
                null);
    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {

        int result = 0;
        if (isServingMusic()) {
            result++;
        }
        if (isServingImages()) {
            result++;
        }
        if (isServingVideos()) {
            result++;
        }
        if (isServingSaf()) {
            result++;
        }
        if (isServingLiveStreams()) {
            result++;
        }
        return result;
    }


    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Container> result = new ArrayList<>();
        if (isServingMusic()) {
            result.add((Container) new MusicFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.MUSIC_FOLDER.getId(), 0, 1, orderby
            ));
        }
        if (isServingImages()) {
            result.add((Container) new ImagesFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.IMAGES_FOLDER.getId(), 0, 1, orderby));
        }
        if (isServingVideos()) {
            result.add((Container) new VideosFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.VIDEOS_FOLDER.getId(), 0, 1, orderby));
        }
        if (isServingSaf()) {
            result.add((Container) new SafFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.SAF_FOLDER.getId(), 0, 1, orderby));
        }
        if (isServingLiveStreams()) {
            result.add((Container) new LiveStreamFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.LIVE_STREAM_FOLDER.getId(), 0, 1, orderby));
        }
        int start = firstResult > 0 ? (int) firstResult : 0;
        if (firstResult >= (result.size() - 1)) {
            start = result.size() - 1;
        }
        int end = start + (int) maxResults;
        if (maxResults > result.size() - 1) {
            end = result.size();
        }
        return result.subList(start, end);
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        return new ArrayList<>();

    }

    private boolean isServingImages() {

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        return preferences.getBoolean(
                getContext().getString(
                        R.string.settings_local_server_serve_images_chkbx),
                false);
    }


    private boolean isServingVideos() {

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        return preferences.getBoolean(
                getContext().getString(
                        R.string.settings_local_server_serve_video_chkbx),
                false);
    }

    private boolean isServingMusic() {

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        return preferences.getBoolean(
                getContext().getString(
                        R.string.settings_local_server_serve_music_chkbx),
                false);
    }

    private boolean isServingSaf() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        return preferences.getBoolean(
                getContext().getString(
                        R.string.settings_local_server_serve_saf_chkbx),
                false);
    }

    private boolean isServingLiveStreams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        return preferences.getBoolean(
                getContext().getString(
                        R.string.settings_local_server_serve_system_audio_chkbx),
                false) ||
                preferences.getBoolean(
                getContext().getString(
                        R.string.settings_local_server_serve_screen_cast_chkbx),
                false);
    }
}
