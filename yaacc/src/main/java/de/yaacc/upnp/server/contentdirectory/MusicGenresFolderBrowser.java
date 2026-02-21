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
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.R;

/**
 * Browser  for the music genres folder.
 *
 * @author openbit (Tobias Schoene)
 */
public class MusicGenresFolderBrowser extends ContentBrowser {
    public MusicGenresFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        StorageFolder result = new StorageFolder(ContentDirectoryIDs.MUSIC_GENRES_FOLDER.getId(), ContentDirectoryIDs.MUSIC_FOLDER.getId(), getContext().getString(R.string.genres), "yaacc", getSize(contentDirectory, myId),
                null);
        result.setContainers(browseContainer(contentDirectory, myId, firstResult, maxResults, orderby));
        return result;

    }

    @SuppressLint("Range")
    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {

        String[] projection = {MediaStore.Audio.Genres._ID};
        String selection = "";
        String[] selectionArgs = null;
        int result = 0;
        try (Cursor mediaCursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI, projection, selection,
                selectionArgs, null)) {
            if (mediaCursor != null && mediaCursor.getCount() > 0 && mediaCursor.moveToFirst()) {
                while (!mediaCursor.isAfterLast()) {
                    if (getMusicTrackSize(contentDirectory, mediaCursor.getString(mediaCursor.getColumnIndex(MediaStore.Audio.Genres._ID))) > 0) {
                        result++;
                    }
                    mediaCursor.moveToNext();
                }
            }
        }
        return result;
    }


    private Integer getMusicTrackSize(YaaccContentDirectory contentDirectory, String parentId) {
        if (parentId == null) {
            return 0;
        }
        Integer result = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            String[] projection = {MediaStore.Audio.Media._ID};
            String selection = MediaStore.Audio.Media.GENRE_ID + "=? " + "and (" + makeLikeClause(MediaStore.Audio.Media.DATA, getMediaPathes().size()) + ")";
            List<String> selectionArgsList = new ArrayList<>();
            selectionArgsList.add(parentId);
            selectionArgsList.addAll(getMediaPathesForLikeClause());
            String[] selectionArgs = selectionArgsList.toArray(new String[0]);
            try (Cursor cursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection,
                    selectionArgs, null)) {
                result = cursor.getCount();
            }
        } else {
            String[] projection = {MediaStore.Audio.Genres.Members.AUDIO_ID};
            String selection = MediaStore.Audio.Genres.Members.GENRE_ID + "=? " + "and (" + makeLikeClause(MediaStore.Audio.Genres.Members.DATA, getMediaPathes().size()) + ")";
            List<String> selectionArgsList = new ArrayList<>();
            selectionArgsList.add(parentId);
            selectionArgsList.addAll(getMediaPathesForLikeClause());
            String[] selectionArgs = selectionArgsList.toArray(new String[0]);
            try (Cursor cursor = contentDirectory
                    .getContext()
                    .getContentResolver()
                    .query(MediaStore.Audio.Genres.Members.getContentUri("external", Long.valueOf(parentId)), projection,
                            selection, selectionArgs, null)) {
                result = cursor.getCount();
            }
        }
        return result;
    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Container> result = new ArrayList<>();
        String[] projection = {MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME};
        String selection = "";
        String[] selectionArgs = null;
        Map<String, MusicAlbum> folderMap = new HashMap<>();
        try (Cursor mediaCursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI, projection, selection,
                selectionArgs, MediaStore.Audio.Genres.NAME + " ASC")) {

            if (mediaCursor != null && mediaCursor.getCount() > 0) {
                mediaCursor.moveToFirst();
                while (!mediaCursor.isAfterLast()) {

                    @SuppressLint("Range") String id = mediaCursor.getString(mediaCursor.getColumnIndex(MediaStore.Audio.Genres._ID));
                    @SuppressLint("Range") String name = mediaCursor.getString(mediaCursor.getColumnIndex(MediaStore.Audio.Genres.NAME));
                    MusicAlbum musicAlbum = new MusicAlbum(ContentDirectoryIDs.MUSIC_GENRE_PREFIX.getId() + id, ContentDirectoryIDs.MUSIC_GENRES_FOLDER.getId(), name, "", 0);
                    if (id != null) {
                        folderMap.put(id, musicAlbum);
                    }

                    mediaCursor.moveToNext();
                }

                for (Map.Entry<String, MusicAlbum> entry : folderMap.entrySet()) {
                    int tracks = getMusicTrackSize(contentDirectory, entry.getKey());
                    entry.getValue().setChildCount(tracks);
                    if (tracks > 0) {
                        YaaccLogger.d(getClass().getName(), "Genre Folder: " + entry.getValue().getId() + " Name: " + entry.getValue().getTitle());
                        result.add(entry.getValue());
                    }
                }
            } else {
                YaaccLogger.d(getClass().getName(), "System media store is empty.");
            }
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

}
