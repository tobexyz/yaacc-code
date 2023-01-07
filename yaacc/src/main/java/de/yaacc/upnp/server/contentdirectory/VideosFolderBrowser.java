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
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.VideoItem;
import org.seamless.util.MimeType;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;

/**
 * Browser  for the video folder.
 *
 * @author openbit (Tobias Schoene)
 */
public class VideosFolderBrowser extends ContentBrowser {

    public VideosFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        StorageFolder videosFolder = new StorageFolder(ContentDirectoryIDs.VIDEOS_FOLDER.getId(), ContentDirectoryIDs.ROOT.getId(), getContext().getString(R.string.videos), "yaacc", getSize(contentDirectory, myId),
                907000L);
        return videosFolder;
    }

    private Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
        String[] projection = {MediaStore.Video.Media._ID};
        String selection = "";
        String[] selectionArgs = null;
        try (Cursor cursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection,
                selectionArgs, null)) {
            return cursor.getCount();
        }
    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        return new ArrayList<Container>();
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Item> result = new ArrayList<Item>();
        String[] projection = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION};
        String selection = "";
        String[] selectionArgs = null;
        try (Cursor mediaCursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection,
                selectionArgs, MediaStore.Video.Media.DISPLAY_NAME + " ASC")) {

            if (mediaCursor != null && mediaCursor.getCount() > 0) {
                mediaCursor.moveToFirst();
                int currentIndex = 0;
                int currentCount = 0;
                while (!mediaCursor.isAfterLast() && currentCount < maxResults) {
                    if (firstResult <= currentIndex) {
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
                        result.add(new VideoItem(ContentDirectoryIDs.VIDEO_PREFIX.getId() + id, ContentDirectoryIDs.VIDEOS_FOLDER.getId(), name, "", resource));
                        Log.d(getClass().getName(), "VideoItem: " + id + " Name: " + name + " uri: " + uri);
                        currentCount++;
                    }
                    currentIndex++;
                    mediaCursor.moveToNext();
                }

            } else {
                Log.d(getClass().getName(), "System media store is empty.");
            }
        }
        return result;

    }

}
