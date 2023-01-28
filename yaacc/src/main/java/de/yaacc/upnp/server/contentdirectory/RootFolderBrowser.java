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
import android.preference.PreferenceManager;

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

        return new StorageFolder(ContentDirectoryIDs.ROOT.getId(), ContentDirectoryIDs.PARENT_OF_ROOT.getId(), "Yaacc", "yaacc", getSize(),
                null);
    }

    private Integer getSize() {
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
        return result;
    }


    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Container> result = new ArrayList<>();
        if (isServingMusic()) {
            result.add((Container) new MusicFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.MUSIC_FOLDER.getId(), firstResult, maxResults, orderby
            ));
        }
        if (isServingImages()) {
            result.add((Container) new ImagesFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.IMAGES_FOLDER.getId(), firstResult, maxResults, orderby));
        }
        if (isServingVideos()) {
            result.add((Container) new VideosFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.VIDEOS_FOLDER.getId(), firstResult, maxResults, orderby));
        }

        return result;
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
}
