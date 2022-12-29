/*
 *
 * Copyright (C) 2014 www.yaacc.de
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
import org.fourthline.cling.support.model.DIDLObject.Property.UPNP;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.Photo;
import org.seamless.util.MimeType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import de.yaacc.upnp.server.YaaccUpnpServerService;

/**
 * Browser  for an  image item.
 *
 * @author TheOpenBit (Tobias Schoene)
 */
public class ImageByBucketNameItemBrowser extends ContentBrowser {

    public ImageByBucketNameItemBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory,
                                 String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        Item result = null;
        String[] projection = {MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN};
        String selection = MediaStore.Images.Media.BUCKET_ID + "=?";
        String[] selectionArgs = new String[]{myId.substring(ContentDirectoryIDs.IMAGE_BY_BUCKET_PREFIX.getId().length())};
        Cursor mImageCursor = contentDirectory
                .getContext()
                .getContentResolver()
                .query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection, selection, selectionArgs, null);

        if (mImageCursor != null) {
            mImageCursor.moveToFirst();
            @SuppressLint("Range") String id = mImageCursor.getString(mImageCursor
                    .getColumnIndex(MediaStore.Images.Media._ID));
            @SuppressLint("Range") String name = mImageCursor
                    .getString(mImageCursor
                            .getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME));
            @SuppressLint("Range") Long size = Long.valueOf(mImageCursor.getString(mImageCursor
                    .getColumnIndex(MediaStore.Images.Media.SIZE)));
            @SuppressLint("Range") Long dateTaken = Long.valueOf(mImageCursor.getString(mImageCursor
                    .getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)));
            Log.d(getClass().getName(),
                    "Mimetype: "
                            + mImageCursor.getString(mImageCursor
                            .getColumnIndex(MediaStore.Images.Media.MIME_TYPE)));
            @SuppressLint("Range") MimeType mimeType = MimeType
                    .valueOf(mImageCursor.getString(mImageCursor
                            .getColumnIndex(MediaStore.Images.Media.MIME_TYPE)));
            // file parameter only needed for media players which decide the
            // ability of playing a file by the file extension
            String uri = getUriString(contentDirectory, id, mimeType);
            Res resource = new Res(mimeType, size, uri);
            result = new Photo(ContentDirectoryIDs.IMAGE_BY_BUCKET_PREFIX.getId() + id,
                    ContentDirectoryIDs.IMAGES_BY_BUCKET_NAME_PREFIX.getId() + dateTaken, name, "", "",
                    resource);
            URI albumArtUri = URI.create("http://"
                    + contentDirectory.getIpAddress() + ":"
                    + YaaccUpnpServerService.PORT + "/?thumb=" + id);
            result.replaceFirstProperty(new UPNP.ALBUM_ART_URI(
                    albumArtUri));
            Log.d(getClass().getName(), "Image: " + id + " Name: " + name
                    + " uri: " + uri);
            mImageCursor.close();
        } else {
            Log.d(getClass().getName(), "Item " + myId + "  not found.");
        }

        return result;
    }


    @Override
    public List<Container> browseContainer(
            YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        return new ArrayList<Container>();
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory,
                                 String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Item> result = new ArrayList<Item>();
        result.add((Item) browseMeta(contentDirectory, myId, firstResult, maxResults, orderby));
        return result;

    }

}