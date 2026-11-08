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

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.Item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import de.yaacc.R;

/**
 * Browser for the galleries of the images: one container per bucket of the media store (a directory with images in the media paths).
 * <p>
 * The galleries are grouped from the rows of the images first, then sorted and paged: a page is a page of galleries, not of rows
 * (a client that asks for 50 at a time gets 50 galleries, not the first 50 images, which may all be in one gallery).
 *
 * @author TheOpenBit (Tobias Schoene)
 */
public class ImagesByBucketNamesFolderBrowser extends ContentBrowser {

    private static final String[] GALLERY_PROJECTION = {MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME};

    // a browser lives for one Browse: the count and the page read the media store once
    private List<MediaGalleries.Gallery> galleries;

    public ImagesByBucketNamesFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        return new StorageFolder(ContentDirectoryIDs.IMAGES_BY_BUCKET_NAMES_FOLDER.getId(), ContentDirectoryIDs.IMAGES_FOLDER.getId(), getContext().getString(R.string.bucket_names), "yaacc", getSize(contentDirectory, myId),
                null);

    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
        return galleries(contentDirectory).size();
    }

    /** The galleries of the images in the media paths, in the order of the rows (see {@link MediaGalleries#group}). */
    List<MediaGalleries.Gallery> galleries(YaaccContentDirectory contentDirectory) {
        if (galleries == null) {
            galleries = readGalleries(contentDirectory);
        }
        return galleries;
    }

    @SuppressLint("Range")
    private List<MediaGalleries.Gallery> readGalleries(YaaccContentDirectory contentDirectory) {
        String selection = "(" + makeLikeClause(MediaStore.Images.Media.DATA, getMediaPathes().size()) + ")";
        String[] selectionArgs = getMediaPathesForLikeClause().toArray(new String[0]);
        List<String[]> rows = new ArrayList<>();
        try (Cursor cursor = contentDirectory.getContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                GALLERY_PROJECTION, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID);
                int nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                do {
                    String id = cursor.getString(idColumn);
                    if (id != null) {
                        rows.add(new String[]{id, cursor.getString(nameColumn)});
                    }
                } while (cursor.moveToNext());
            }
        }
        return MediaGalleries.group(rows, getContext().getString(R.string.gallery_unnamed));
    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Container> all = new ArrayList<>();
        for (MediaGalleries.Gallery gallery : galleries(contentDirectory)) {
            all.add(new StorageFolder(ContentDirectoryIDs.IMAGES_BY_BUCKET_NAME_PREFIX.getId() + gallery.id,
                    ContentDirectoryIDs.IMAGES_BY_BUCKET_NAMES_FOLDER.getId(), gallery.name, "yaacc", gallery.count, 90700L));
        }
        // sorted by the name without regard to the case, then paged
        Map<String, Function<DIDLObject, String>> accessors = new HashMap<>();
        accessors.put(SortSupport.PROPERTY_TITLE, MediaGalleries::titleKey);
        Comparator<DIDLObject> tieBreaker = MediaGalleries.inTitleOrder();
        all.sort(SortSupport.toComparator(orderby, accessors, tieBreaker).thenComparing(tieBreaker));
        return MediaGalleries.page(all, firstResult, maxResults);
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        return new ArrayList<>();
    }

}
