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

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;
import android.util.Log;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.VideoItem;
import org.seamless.util.MimeType;

import java.util.ArrayList;
import java.util.List;

/**
 * Browser  for a video item.
 *
 * @author openbit (Tobias Schoene)
 */
public class VideoItemBrowser extends ContentBrowser {

    public VideoItemBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory,
                                 String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        Item result = null;
        String[] projection = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION};
        String selection = MediaStore.Video.Media._ID + "=?";
        String[] selectionArgs = new String[]{myId.substring(ContentDirectoryIDs.VIDEO_PREFIX.getId().length())};
        try (Cursor mediaCursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection,
                selectionArgs, null)) {

            if (mediaCursor != null && mediaCursor.getCount() > 0) {
                mediaCursor.moveToFirst();
                @SuppressLint("Range") String id = mediaCursor.getString(mediaCursor.getColumnIndex(MediaStore.Video.VideoColumns._ID));
                @SuppressLint("Range") String name = mediaCursor.getString(mediaCursor.getColumnIndex(MediaStore.Video.VideoColumns.DISPLAY_NAME));
                @SuppressLint("Range") String duration = mediaCursor.getString(mediaCursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION));
                duration = contentDirectory.formatDuration(duration);
                @SuppressLint("Range") Long size = Long.valueOf(mediaCursor.getString(mediaCursor.getColumnIndex(MediaStore.Video.VideoColumns.SIZE)));
                @SuppressLint("Range") String mimeTypeString = mediaCursor.getString(mediaCursor.getColumnIndex(MediaStore.Video.VideoColumns.MIME_TYPE));
                Log.d(getClass().getName(), "Mimetype: " + mimeTypeString);
                MimeType mimeType = MimeType.valueOf(mimeTypeString);
                // file parameter only needed for media players which decide the
                // ability of playing a file by the file extension
                String uri = getUriString(contentDirectory, id, mimeType);
                Res resource = new Res(mimeType, size, uri);
                resource.setDuration(duration);
                result = new VideoItem(ContentDirectoryIDs.VIDEO_PREFIX.getId() + id, ContentDirectoryIDs.VIDEOS_FOLDER.getId(), name, "", resource);
                Log.d(getClass().getName(), "VideoItem: " + id + " Name: " + name + " uri: " + uri);


            } else {
                Log.d(getClass().getName(), "Item " + myId + "  not found.");
            }
        }

        return result;
    }

    @Override
    public List<Container> browseContainer(
            YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        return new ArrayList<>();
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory,
                                 String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Item> result = new ArrayList<>();
        result.add((Item) browseMeta(contentDirectory, myId, firstResult, maxResults, orderby));
        return result;

    }

}