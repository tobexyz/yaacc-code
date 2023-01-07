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
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.container.PhotoAlbum;
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.R;

/**
 * Browser  for the image folder.
 *
 * @author TheOpenBit (Tobias Schoene)
 */
public class ImagesByBucketNamesFolderBrowser extends ContentBrowser {


    public ImagesByBucketNamesFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        PhotoAlbum photoAlbum = new PhotoAlbum(ContentDirectoryIDs.IMAGES_BY_BUCKET_NAMES_FOLDER.getId(), ContentDirectoryIDs.IMAGES_FOLDER.getId(), getContext().getString(R.string.bucket_names), "yaacc", getSize(contentDirectory, myId));
        return photoAlbum;
    }

    private Integer getSize(YaaccContentDirectory contentDirectory, String myId) {

        String[] projection = {MediaStore.Images.Media.BUCKET_ID};
        String selection = null;
        String[] selectionArgs = null;
        try (Cursor cursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
                selectionArgs, null)) {
            return cursor.getCount();
        }

    }

    private Integer getBucketNameFolderSize(YaaccContentDirectory contentDirectory, String id) {

        String[] projection = {MediaStore.Images.Media.BUCKET_ID};
        String selection = MediaStore.Images.Media.BUCKET_ID + "=?";
        String[] selectionArgs = new String[]{id};
        try (Cursor cursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
                selectionArgs, null)) {
            return cursor.getCount();
        }

    }


    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Container> result = new ArrayList<Container>();
        Map<String, StorageFolder> folderMap = new HashMap<String, StorageFolder>();
        String[] projection = {MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME};
        String selection = null;
        String[] selectionArgs = null;
        try (Cursor mediaCursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
                selectionArgs, MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " ASC")) {
            if (mediaCursor != null && mediaCursor.getCount() > 0) {
                mediaCursor.moveToFirst();
                int currentIndex = 0;
                int currentCount = 0;
                while (!mediaCursor.isAfterLast() && currentCount < maxResults) {
                    if (firstResult <= currentIndex) {
                        @SuppressLint("Range") String id = mediaCursor.getString(mediaCursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID));
                        @SuppressLint("Range") String name = mediaCursor.getString(mediaCursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME));
                        ;
                        StorageFolder imageFolder = new StorageFolder(ContentDirectoryIDs.IMAGES_BY_BUCKET_NAME_PREFIX.getId() + id, ContentDirectoryIDs.IMAGES_BY_BUCKET_NAMES_FOLDER.getId(), name, "yaacc", 0, 90700L);
                        folderMap.put(id, imageFolder);
                        Log.d(getClass().getName(), "image by bucket names folder: " + id + " Name: " + name);
                        currentCount++;
                    }
                    currentIndex++;
                    mediaCursor.moveToNext();
                }
                //Fetch folder size
                for (Map.Entry<String, StorageFolder> entry : folderMap.entrySet()) {
                    entry.getValue().setChildCount(getBucketNameFolderSize(contentDirectory, entry.getKey()));
                    result.add(entry.getValue());
                }
            } else {
                Log.d(getClass().getName(), "System media store is empty.");
            }
        }
        Collections.sort(result, new Comparator<Container>() {

            @Override
            public int compare(Container lhs, Container rhs) {
                return lhs.getTitle().compareTo(rhs.getTitle());
            }
        });
        return result;
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Item> result = new ArrayList<Item>();
        return result;

    }

}
