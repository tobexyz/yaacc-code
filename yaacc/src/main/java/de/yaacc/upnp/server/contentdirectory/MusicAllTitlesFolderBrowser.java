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
import org.fourthline.cling.support.model.DIDLObject.Property.UPNP;
import org.fourthline.cling.support.model.PersonWithRole;
import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.seamless.util.MimeType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;
import de.yaacc.upnp.server.YaaccUpnpServerService;

/**
 * Browser for the music all titles folder.
 *
 * @author openbit (Tobias Schoene)
 */
public class MusicAllTitlesFolderBrowser extends ContentBrowser {
    public MusicAllTitlesFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory,
                                 String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        /*List<MusicTrack> items = browseItem(contentDirectory, myId, firstResult, maxResults, orderby);
        return new MusicAlbum(
                ContentDirectoryIDs.MUSIC_ALL_TITLES_FOLDER.getId(),
                ContentDirectoryIDs.MUSIC_FOLDER.getId(), getContext().getString(R.string.all), "yaacc",
                getSize(contentDirectory, myId), items);
*/

        Log.d(getClass().getName(), "Foo2: " + myId + " first: " + firstResult + " max: " + maxResults);
        return new StorageFolder(myId, ContentDirectoryIDs.MUSIC_FOLDER.getId(), getContext().getString(R.string.all), "yaacc", getSize(
                contentDirectory, myId), null);

    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {

        String[] projection = {MediaStore.Audio.Media._ID};
        //String selection = "(" + makeLikeClause(MediaStore.Audio.Media.RELATIVE_PATH, getMediaPathes().size()) + ")";
        //String[] selectionArgs = getMediaPathesForLikeClause().toArray(new String[0]);
        String selection = "";
        String[] selectionArgs = null;
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

        return new ArrayList<>();
    }

    @SuppressLint("Range")
    @Override
    public List<MusicTrack> browseItem(YaaccContentDirectory contentDirectory,
                                       String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<MusicTrack> result = new ArrayList<>();
        String[] projection;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            projection = new String[]{MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.BITRATE,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.GENRE};
        } else {
            projection = new String[]{MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION};
        }
        //String selection = "(" + makeLikeClause(MediaStore.Audio.Media.RELATIVE_PATH, getMediaPathes().size()) + ")";
        //String[] selectionArgs = getMediaPathesForLikeClause().toArray(new String[0]);
        String selection = "";
        String[] selectionArgs = null;
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
                        Log.d(getClass().getName(), "browse firstResult: " + firstResult + " currentIndex:" + currentIndex + " currentCount: " + currentCount);
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
                        @SuppressLint("Range") String duration = mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.DURATION));
                        duration = contentDirectory.formatDuration(duration);
                        Log.d(getClass().getName(),
                                "Mimetype: "
                                        + mediaCursor.getString(mediaCursor
                                        .getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)));

                        Log.d(getClass().getName(),
                                "PATH: "
                                        + mediaCursor.getString(mediaCursor
                                        .getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)));


                        MimeType mimeType = MimeType
                                .valueOf(mediaCursor.getString(mediaCursor
                                        .getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)));

                        // file parameter only needed for media players which decide
                        // the
                        // ability of playing a file by the file extension
                        String uri = getUriString(contentDirectory, id, mimeType);
                        URI albumArtUri = URI.create("http://"
                                + contentDirectory.getIpAddress() + ":"
                                + YaaccUpnpServerService.PORT + "/album/" + albumId);
                        ProtocolInfo protocolInfo = new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType.toString(), getDLNAAttributes(mimeType));
                        Res resource = new Res(protocolInfo, size, uri);
                        resource.setDuration(duration);
                        MusicTrack musicTrack = new MusicTrack(
                                ContentDirectoryIDs.MUSIC_ALL_TITLES_ITEM_PREFIX.getId()
                                        + id, ContentDirectoryIDs.MUSIC_ALL_TITLES_FOLDER.getId(),
                                title + "-(" + name + ")", "", album, artist, resource);
                        musicTrack.replaceFirstProperty(new UPNP.ALBUM_ART_URI(
                                albumArtUri));
                        musicTrack.setArtists(new PersonWithRole[]{new PersonWithRole(artist, "AlbumArtist")});
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            @SuppressLint("Range") String genre = mediaCursor.getString(mediaCursor
                                    .getColumnIndex(MediaStore.Audio.Media.GENRE));
                            @SuppressLint("Range") String bitrate = mediaCursor.getString(mediaCursor
                                    .getColumnIndex(MediaStore.Audio.Media.BITRATE));
                            resource.setBitrate(Long.valueOf(bitrate));
                            musicTrack.setGenres(new String[]{genre});
                        }
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
        return result;

    }

}
