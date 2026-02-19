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
import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.container.MusicAlbum;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.seamless.util.MimeType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import de.yaacc.upnp.server.YaaccUpnpServerService;

/**
 * Browser for a music genre folder.
 *
 * @author openbit (Tobias Schoene)
 */
public class MusicGenreFolderBrowser extends ContentBrowser {
    public MusicGenreFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory,
                                 String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        return new MusicAlbum(myId, ContentDirectoryIDs.MUSIC_GENRES_FOLDER.getId(), getName(
                contentDirectory, myId), "yaacc", getSize(
                contentDirectory, myId), browseItem(contentDirectory,
                myId, firstResult, maxResults, orderby));


    }

    private String getName(YaaccContentDirectory contentDirectory, String myId) {
        String result = "";
        String[] projection = {MediaStore.Audio.Genres.NAME};
        String selection = MediaStore.Audio.Genres._ID + "=?";
        String[] selectionArgs = new String[]{myId
                .substring(ContentDirectoryIDs.MUSIC_GENRE_PREFIX.getId()
                .length())};
        try (Cursor cursor = contentDirectory
                .getContext()
                .getContentResolver()
                .query(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                        projection, selection, selectionArgs, null)) {

            if (cursor != null && cursor.getCount() > 0) {
                cursor.moveToFirst();
                result = cursor.getString(0);

            }
        }
        return result;
    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            String[] projection = {MediaStore.Audio.Media._ID};
            String selection = MediaStore.Audio.Media.GENRE_ID + "=? " + "and (" + makeLikeClause(MediaStore.Audio.Media.DATA, getMediaPathes().size()) + ")";
            List<String> selectionArgsList = new ArrayList<>();
            selectionArgsList.add(myId
                    .substring(ContentDirectoryIDs.MUSIC_GENRE_PREFIX.getId()
                            .length()));
            selectionArgsList.addAll(getMediaPathesForLikeClause());
            String[] selectionArgs = selectionArgsList.toArray(new String[0]);
            try (Cursor cursor = contentDirectory
                    .getContext()
                    .getContentResolver()
                    .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                            selection, selectionArgs, null)) {
                return cursor.getCount();
            }
        } else {
            String[] projection = {MediaStore.Audio.Genres.Members.AUDIO_ID};
            String selection = MediaStore.Audio.Genres.Members.GENRE_ID + "=? " + "and (" + makeLikeClause(MediaStore.Audio.Genres.Members.DATA, getMediaPathes().size()) + ")";
            List<String> selectionArgsList = new ArrayList<>();
            String genreId = myId.substring(ContentDirectoryIDs.MUSIC_GENRE_PREFIX.getId().length());
            selectionArgsList.add(genreId);
            selectionArgsList.addAll(getMediaPathesForLikeClause());
            String[] selectionArgs = selectionArgsList.toArray(new String[0]);
            try (Cursor cursor = contentDirectory
                    .getContext()
                    .getContentResolver()
                    .query(MediaStore.Audio.Genres.Members.getContentUri("external", Long.parseLong(genreId)), projection,
                            selection, selectionArgs, null)) {
                return cursor.getCount();
            }
        }

    }

    @Override
    public List<Container> browseContainer(
            YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        return new ArrayList<>();
    }

    @Override
    public List<MusicTrack> browseItem(YaaccContentDirectory contentDirectory,
                                       String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<MusicTrack> result = new ArrayList<>();
        String[] projection;
        String selection;
        String[] selectionArgs;
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
                    MediaStore.Audio.Media.GENRE_ID,
                    MediaStore.Audio.Media.GENRE};
            selection = MediaStore.Audio.Media.GENRE_ID + "=? " + "and (" + makeLikeClause(MediaStore.Audio.Media.DATA, getMediaPathes().size()) + ")";
            List<String> selectionArgsList = new ArrayList<>();
            selectionArgsList.add(myId
                    .substring(ContentDirectoryIDs.MUSIC_GENRE_PREFIX.getId()
                            .length()));
            selectionArgsList.addAll(getMediaPathesForLikeClause());
            selectionArgs = selectionArgsList.toArray(new String[0]);
        } else {

            String[] genreProjection = new String[]{MediaStore.Audio.Genres.Members.AUDIO_ID};
            String genreSelection = MediaStore.Audio.Genres.Members.GENRE_ID + "=? " + "and (" + makeLikeClause(MediaStore.Audio.Genres.Members.DATA, getMediaPathes().size()) + ")";
            List<String> selectionArgsList = new ArrayList<>();
            String genreId = myId
                    .substring(ContentDirectoryIDs.MUSIC_GENRE_PREFIX.getId()
                            .length());
            selectionArgsList.add(genreId);
            selectionArgsList.addAll(getMediaPathesForLikeClause());
            String[] genreSelectionArgs = selectionArgsList.toArray(new String[0]);
            List<String> audioIds = new ArrayList<>();
            try (Cursor genreCursor = contentDirectory
                    .getContext()
                    .getContentResolver()
                    .query(MediaStore.Audio.Genres.Members.getContentUri("external", Long.parseLong(genreId)), genreProjection,
                            genreSelection, genreSelectionArgs, "")) {
                if (genreCursor == null || genreCursor.getCount() == 0) {
                    return result;
                }
                genreCursor.moveToFirst();
                int currentIndex = 0;
                int currentCount = 0;
                while (!genreCursor.isAfterLast() && currentCount < maxResults) {
                    if (firstResult <= currentIndex) {
                        @SuppressLint("Range") String id = genreCursor
                                .getString(genreCursor
                                        .getColumnIndex(MediaStore.Audio.Genres.Members.AUDIO_ID));
                        audioIds.add(id);
                        currentCount++;
                    }
                    currentIndex++;
                    genreCursor.moveToNext();
                }
            }
            projection = new String[]{MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION};
            StringBuilder selectionBuilder = new StringBuilder(MediaStore.Audio.Media._ID + " in (");
            for (int i = 0; i < audioIds.size(); i++) {
                selectionBuilder.append("?");
                if (i < audioIds.size() - 1) {
                    selectionBuilder.append(",");
                }
            }
            selectionBuilder.append(")");
            selection = selectionBuilder.toString();
            selectionArgs = audioIds.toArray(new String[0]);
        }


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
                        @SuppressLint("Range") String id = mediaCursor
                                .getString(mediaCursor
                                        .getColumnIndex(MediaStore.Audio.Media._ID));
                        String genreId = myId;
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            @SuppressLint("Range") int genreIdIdx = mediaCursor.getColumnIndex(MediaStore.Audio.Media.GENRE_ID);
                            genreId = mediaCursor.getString(genreIdIdx);
                        }
                        @SuppressLint("Range") String name = mediaCursor
                                .getString(mediaCursor
                                        .getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME));
                        @SuppressLint("Range") Long size = Long.valueOf(mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.SIZE)));

                        @SuppressLint("Range") String album = mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.ALBUM));
                        @SuppressLint("Range") String albumId = mediaCursor
                                .getString(mediaCursor
                                        .getColumnIndex(MediaStore.Audio.Media.ALBUM_ID));
                        @SuppressLint("Range") String title = mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.TITLE));
                        @SuppressLint("Range") String artist = mediaCursor
                                .getString(mediaCursor
                                        .getColumnIndex(MediaStore.Audio.Media.ARTIST));
                        artist = artist.equals("<unknown>") ? "" : artist;
                        @SuppressLint("Range") String duration = mediaCursor
                                .getString(mediaCursor
                                        .getColumnIndex(MediaStore.Audio.Media.DURATION));
                        duration = contentDirectory.formatDuration(duration);
                Integer trackNumber = null;
                Integer year = null;
                int trackIdx = mediaCursor.getColumnIndex(MediaStore.Audio.Media.TRACK);
                if (trackIdx >= 0) {
                    trackNumber = mediaCursor.getInt(trackIdx);
                }
                int yearIdx = mediaCursor.getColumnIndex(MediaStore.Audio.Media.YEAR);
                if (yearIdx >= 0) {
                    year = mediaCursor.getInt(yearIdx);
                }
                        @SuppressLint("Range") String mimeTypeString = mediaCursor.getString(mediaCursor
                                .getColumnIndex(MediaStore.Audio.Media.MIME_TYPE));
                        YaaccLogger.d(getClass().getName(),
                                "Mimetype: "
                                        + mimeTypeString);
                        MimeType mimeType = MimeType
                                .valueOf(mimeTypeString);
                        // file parameter only needed for media players which decide
                        // the
                        // ability of playing a file by the file extension
                        String uri = getUriString(contentDirectory, id, mimeType);
                        URI albumArtUri = URI.create("http://"
                                + contentDirectory.getIpAddress() + ":"
                                + YaaccUpnpServerService.PORT + "/album/" + albumId);

                        MusicTrack musicTrack;
                        
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            @SuppressLint("Range") String genre = mediaCursor.getString(mediaCursor
                                    .getColumnIndex(MediaStore.Audio.Media.GENRE));
                            @SuppressLint("Range") String bitrateStr = mediaCursor.getString(mediaCursor
                                    .getColumnIndex(MediaStore.Audio.Media.BITRATE));
                            Long bitrate = bitrateStr != null ? Long.valueOf(bitrateStr) : null;
                            
                            musicTrack = createMusicTrack(
                                ContentDirectoryIDs.MUSIC_GENRE_ITEM_PREFIX.getId() + id,
                                ContentDirectoryIDs.MUSIC_GENRE_PREFIX.getId() + genreId,
                                title + "-(" + name + ")",
                                "",
                                false,
                                mimeType,
                                uri,
                                size,
                                duration,
                                album,
                                artist,
                                trackNumber,
                                year != null && year > 0 ? year + "-01-01" : null,
                                new String[]{genre},
                                albumArtUri.toString(),
                                bitrate
                            );
                        } else {
                            musicTrack = createMusicTrack(
                                ContentDirectoryIDs.MUSIC_GENRE_ITEM_PREFIX.getId() + id,
                                ContentDirectoryIDs.MUSIC_GENRE_PREFIX.getId() + genreId,
                                title + "-(" + name + ")",
                                "",
                                false,
                                mimeType,
                                uri,
                                size,
                                duration,
                                album,
                                artist,
                                trackNumber,
                                year != null && year > 0 ? year + "-01-01" : null,
                                null,
                                albumArtUri.toString()
                            );
                        }

                        result.add(musicTrack);

                        YaaccLogger.d(getClass().getName(), "MusicTrack: " + id + " Name: "
                                + name + " uri: " + uri);
                        currentCount++;
                    }
                    currentIndex++;
                    mediaCursor.moveToNext();
                }

            } else {
                YaaccLogger.d(getClass().getName(), "System media store is empty.");
            }
        }

        return result;

    }

}
