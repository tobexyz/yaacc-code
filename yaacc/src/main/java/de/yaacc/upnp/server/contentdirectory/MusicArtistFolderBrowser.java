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
import org.fourthline.cling.support.model.container.MusicAlbum;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.seamless.util.MimeType;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import de.yaacc.upnp.server.YaaccUpnpServerService;

/**
 * Browser for a music artist folder.
 *
 * @author openbit (Tobias Schoene)
 */
public class MusicArtistFolderBrowser extends ContentBrowser {
    public MusicArtistFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory,
                                 String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        MusicAlbum folder = new MusicAlbum(myId,
                ContentDirectoryIDs.MUSIC_ARTISTS_FOLDER.getId(), getName(
                contentDirectory, myId), "yaacc", getSize(
                contentDirectory, myId));
        return folder;
    }

    private String getName(YaaccContentDirectory contentDirectory, String myId) {
        String result = "";
        String[] projection = {MediaStore.Audio.Artists.ARTIST};
        String selection = MediaStore.Audio.Artists._ID + "=?";
        String[] selectionArgs = new String[]{myId.substring(myId
                .indexOf(ContentDirectoryIDs.MUSIC_ARTIST_PREFIX.getId()))};
        try (Cursor cursor = contentDirectory
                .getContext()
                .getContentResolver()
                .query(MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
                        projection, selection, selectionArgs, null)) {

            if (cursor != null && cursor.getCount() > 0) {
                cursor.moveToFirst();
                result = cursor.getString(0);
                cursor.close();
            }
        }
        return result;
    }

    private Integer getSize(YaaccContentDirectory contentDirectory, String myId) {

        String[] projection = {MediaStore.Audio.Media.ARTIST_ID};
        String selection = MediaStore.Audio.Media.ARTIST_ID + "=?";
        String[] selectionArgs = new String[]{myId
                .substring(ContentDirectoryIDs.MUSIC_ARTIST_PREFIX.getId()
                .length())};
        try (Cursor cursor = contentDirectory
                .getContext()
                .getContentResolver()
                .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                        selection, selectionArgs, null)) {
            return cursor.getCount();
        }

    }

    @Override
    public List<Container> browseContainer(
            YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        return new ArrayList<Container>();
    }

    @SuppressLint("Range")
    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory,
                                 String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Item> result = new ArrayList<Item>();
        String[] projection = {MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION};
        String selection = MediaStore.Audio.Media.ARTIST_ID + "=?";
        String[] selectionArgs = new String[]{myId
                .substring(ContentDirectoryIDs.MUSIC_ARTIST_PREFIX.getId()
                .length())};
        try (Cursor mediaCursor = contentDirectory
                .getContext()
                .getContentResolver()
                .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                        selection, selectionArgs, MediaStore.Audio.Media.DISPLAY_NAME + " ASC")) {

            if (mediaCursor != null && mediaCursor.getCount() > 0) {
                mediaCursor.moveToFirst();
                int currentIndex = 0;
                int currentCount = 0;
                while (!mediaCursor.isAfterLast() && currentCount < maxResults) {
                    if (firstResult <= currentIndex) {
                        @SuppressLint("Range") String id = mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media._ID));
                        @SuppressLint("Range") String name = mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME));
                        @SuppressLint("Range") Long size = Long.valueOf(mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.SIZE)));

                        @SuppressLint("Range") String album = mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.ALBUM));
                        @SuppressLint("Range") String albumId = mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.ALBUM_ID));
                        @SuppressLint("Range") String title = mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.TITLE));
                        @SuppressLint("Range") String artist = mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.ARTIST));
                        @SuppressLint("Range") String artistId = mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.ARTIST_ID));
                        @SuppressLint("Range") String duration = mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.DURATION));
                        duration = contentDirectory.formatDuration(duration);
                        Log.d(getClass().getName(),
                                "Mimetype: "
                                        + mediaCursor.getString(mediaCursor
                                        .getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)));

                        MimeType mimeType = MimeType
                                .valueOf(mediaCursor.getString(mediaCursor
                                        .getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)));
                        // file parameter only needed for media players which decide
                        // the
                        // ability of playing a file by the file extension

                        String uri = getUriString(contentDirectory, id, mimeType);
                        URI albumArtUri = URI.create("http://"
                                + contentDirectory.getIpAddress() + ":"
                                + YaaccUpnpServerService.PORT + "/?album=" + albumId);
                        Res resource = new Res(mimeType, size, uri);
                        resource.setDuration(duration);
                        MusicTrack musicTrack = new MusicTrack(
                                ContentDirectoryIDs.MUSIC_ARTIST_ITEM_PREFIX.getId()
                                        + id,
                                ContentDirectoryIDs.MUSIC_ARTIST_PREFIX.getId()
                                        + artistId, title + "-(" + name + ")", "",
                                album, artist, resource);
                        musicTrack
                                .replaceFirstProperty(new UPNP.ALBUM_ART_URI(albumArtUri));
                        result.add(musicTrack);
                        Log.d(getClass().getName(), "MusicTrack: " + id + " Name: "
                                + name + " uri: " + uri);
                        currentCount++;
                    }
                    currentIndex++;
                    mediaCursor.moveToNext();
                }

            } else {
                Log.d(getClass().getName(), "System media store is empty.");
            }
        }
        Collections.sort(result, new Comparator<Item>() {

            @Override
            public int compare(Item lhs, Item rhs) {
                return lhs.getTitle().compareTo(rhs.getTitle());
            }
        });
        return result;

    }

}
