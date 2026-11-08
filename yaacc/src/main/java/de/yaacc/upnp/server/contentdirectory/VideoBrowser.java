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
import android.database.Cursor;
import android.provider.MediaStore;

import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.item.Item;
import org.seamless.util.MimeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.R;
import de.yaacc.util.YaaccLogger;

/** What the browsers of the videos have in common: the media store query, the items and the galleries. */
abstract class VideoBrowser extends ContentBrowser {
    private static final String[] ITEM_PROJECTION = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED};
    private static final String[] GALLERY_PROJECTION = {MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME};

    VideoBrowser(android.content.Context context) {
        super(context);
    }

    /** The selection of the media paths (the setting of the server), with the arguments from {@link #mediaPathArguments()}. */
    String mediaPathSelection() {
        return "(" + makeLikeClause(MediaStore.Video.Media.DATA, getMediaPathes().size()) + ")";
    }

    List<String> mediaPathArguments() {
        return getMediaPathesForLikeClause();
    }

    /** How many rows of the media store match. */
    int count(YaaccContentDirectory contentDirectory, String selection, List<String> selectionArgs) {
        try (Cursor cursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID}, selection, selectionArgs.toArray(new String[0]), null)) {
            return cursor == null ? 0 : cursor.getCount();
        }
    }

    /**
     * The videos that match, as items: a page of them in the order the client asked for.
     *
     * @param parentId the container the items are listed in
     */
    List<Item> items(YaaccContentDirectory contentDirectory, String selection, List<String> selectionArgs, String parentId,
                     long firstResult, long maxResults, SortCriterion[] orderby) {
        Map<String, String> columnMap = new HashMap<>();
        columnMap.put(SortSupport.PROPERTY_TITLE, MediaStore.Video.Media.DISPLAY_NAME);
        columnMap.put(SortSupport.PROPERTY_DATE, MediaStore.Video.Media.DATE_ADDED);
        String sortOrder = SortSupport.toMediaStoreSortOrder(orderby, columnMap, MediaStore.Video.Media.DISPLAY_NAME + " ASC");
        List<Item> result = new ArrayList<>();
        try (Cursor cursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ITEM_PROJECTION, selection, selectionArgs.toArray(new String[0]), sortOrder)) {
            if (cursor == null || maxResults <= 0 || !cursor.moveToPosition((int) Math.min(Math.max(0, firstResult), Integer.MAX_VALUE))) {
                return result;
            }
            do {
                result.add(item(contentDirectory, cursor, parentId));
            } while (result.size() < maxResults && cursor.moveToNext());
        }
        return result;
    }

    @SuppressLint("Range")
    private Item item(YaaccContentDirectory contentDirectory, Cursor cursor, String parentId) {
        String id = cursor.getString(cursor.getColumnIndex(MediaStore.Video.VideoColumns._ID));
        String name = cursor.getString(cursor.getColumnIndex(MediaStore.Video.VideoColumns.DISPLAY_NAME));
        String duration = contentDirectory.formatDuration(cursor.getString(cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)));
        long size = cursor.getLong(cursor.getColumnIndex(MediaStore.Video.VideoColumns.SIZE));
        String mimeTypeString = cursor.getString(cursor.getColumnIndex(MediaStore.Video.VideoColumns.MIME_TYPE));
        YaaccLogger.d(getClass().getName(), "Mimetype: " + mimeTypeString);
        MimeType mimeType = mimeTypeString == null || mimeTypeString.isEmpty()
                ? MimeType.valueOf("application/octet-stream") : MimeType.valueOf(mimeTypeString);
        // MediaStore.Video.Media.DATE_ADDED is SECONDS since epoch (unlike DATE_TAKEN for images, which is millis)
        // - convert before formatting.
        long dateAddedSeconds = cursor.getLong(cursor.getColumnIndex(MediaStore.Video.VideoColumns.DATE_ADDED));
        String date = SortSupport.formatEpochMillisAsDate(dateAddedSeconds * 1000L);
        // file parameter only needed for media players which decide the ability of playing a file by the file extension
        String uri = getUriString(contentDirectory, id, mimeType, name, null);
        return createItem(ContentDirectoryIDs.VIDEO_PREFIX.getId() + id, parentId, name, "", false, mimeType, uri, size, duration, date);
    }

    // a browser lives for one Browse: the count, the page and the metadata of it read the media store once
    private List<VideoGalleries.Gallery> galleries;

    /** The galleries of the videos in the media paths, in the order of the rows (see {@link VideoGalleries#group}). */
    @SuppressLint("Range")
    List<VideoGalleries.Gallery> galleries(YaaccContentDirectory contentDirectory) {
        if (galleries == null) {
            galleries = readGalleries(contentDirectory);
        }
        return galleries;
    }

    @SuppressLint("Range")
    private List<VideoGalleries.Gallery> readGalleries(YaaccContentDirectory contentDirectory) {
        List<String[]> rows = new ArrayList<>();
        try (Cursor cursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                GALLERY_PROJECTION, mediaPathSelection(), mediaPathArguments().toArray(new String[0]), null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
                int nameColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                do {
                    String id = cursor.getString(idColumn);
                    if (id != null) {
                        rows.add(new String[]{id, cursor.getString(nameColumn)});
                    }
                } while (cursor.moveToNext());
            }
        }
        return VideoGalleries.group(rows, getContext().getString(R.string.gallery_unnamed));
    }
}
