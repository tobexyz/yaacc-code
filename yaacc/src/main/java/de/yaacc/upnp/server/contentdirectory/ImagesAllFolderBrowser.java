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
import org.fourthline.cling.support.model.container.PhotoAlbum;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.Photo;
import org.seamless.util.MimeType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;
import de.yaacc.upnp.server.YaaccUpnpServerService;

/**
 * Browser  for the image folder.
 *
 * @author openbit (Tobias Schoene)
 */
public class ImagesAllFolderBrowser extends ContentBrowser {

    public ImagesAllFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        PhotoAlbum photoAlbum = new PhotoAlbum(ContentDirectoryIDs.IMAGES_ALL_FOLDER.getId(), ContentDirectoryIDs.IMAGES_FOLDER.getId(), getContext().getString(R.string.all_images), "yaacc", getSize(contentDirectory, myId));
        return photoAlbum;
    }

    private Integer getSize(YaaccContentDirectory contentDirectory, String myId) {

        String[] projection = {MediaStore.Images.Media._ID};
        String selection = "";
        String[] selectionArgs = null;
        try (Cursor cursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
                selectionArgs, null)) {
            return cursor.getCount();
        }

    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        return new ArrayList<Container>();
    }

    @SuppressLint("Range")
    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Item> result = new ArrayList<Item>();
        // Query for all images on external storage
        String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.SIZE};
        String selection = "";
        String[] selectionArgs = null;
        try (Cursor mImageCursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
                selectionArgs, MediaStore.Images.Media.DISPLAY_NAME + " ASC")) {

            if (mImageCursor != null && mImageCursor.getCount() > 0) {
                mImageCursor.moveToFirst();
                int currentIndex = 0;
                int currentCount = 0;
                while (!mImageCursor.isAfterLast() && currentCount < maxResults) {
                    if (firstResult <= currentIndex) {
                        @SuppressLint("Range") String id = mImageCursor.getString(mImageCursor.getColumnIndex(MediaStore.Images.ImageColumns._ID));
                        @SuppressLint("Range") String name = mImageCursor.getString(mImageCursor.getColumnIndex(MediaStore.Images.ImageColumns.DISPLAY_NAME));
                        @SuppressLint("Range") Long size = Long.valueOf(mImageCursor.getString(mImageCursor.getColumnIndex(MediaStore.Images.ImageColumns.SIZE)));
                        Log.d(getClass().getName(),
                                "Mimetype: " + mImageCursor.getString(mImageCursor.getColumnIndex(MediaStore.Images.ImageColumns.MIME_TYPE)));
                        MimeType mimeType = MimeType.valueOf(mImageCursor.getString(mImageCursor.getColumnIndex(MediaStore.Images.ImageColumns.MIME_TYPE)));
                        // file parameter only needed for media players which decide the
                        // ability of playing a file by the file extension
                        String uri = getUriString(contentDirectory, id, mimeType);
                        Res resource = new Res(mimeType, size, uri);

                        Photo photo = new Photo(ContentDirectoryIDs.IMAGE_ALL_PREFIX.getId() + id, ContentDirectoryIDs.IMAGES_ALL_FOLDER.getId(), name, "", "", resource);
                        URI albumArtUri = URI.create("http://"
                                + contentDirectory.getIpAddress() + ":"
                                + YaaccUpnpServerService.PORT + "/?thumb=" + id);
                        photo.replaceFirstProperty(new UPNP.ALBUM_ART_URI(
                                albumArtUri));

                        result.add(photo);
                        Log.d(getClass().getName(), "Image: " + id + " Name: " + name + " uri: " + uri);
                        currentCount++;
                    }
                    currentIndex++;
                    mImageCursor.moveToNext();
                }

            } else {
                Log.d(getClass().getName(), "System media store is empty.");
            }
        }
        return result;

    }

}
