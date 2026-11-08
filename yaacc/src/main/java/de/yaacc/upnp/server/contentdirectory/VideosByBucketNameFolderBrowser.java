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

import android.content.Context;
import android.provider.MediaStore;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.Item;

import java.util.ArrayList;
import java.util.List;

/** One gallery: the videos of one bucket of the media store in the media paths. */
public class VideosByBucketNameFolderBrowser extends VideoBrowser {

    public VideosByBucketNameFolderBrowser(Context context) {
        super(context);
    }

    private static String bucketId(String myId) {
        return myId.substring(ContentDirectoryIDs.VIDEOS_BY_BUCKET_NAME_PREFIX.getId().length());
    }

    private String selection() {
        return mediaPathSelection() + " AND " + MediaStore.Video.Media.BUCKET_ID + "=?";
    }

    private List<String> selectionArgs(String myId) {
        List<String> args = new ArrayList<>(mediaPathArguments());
        args.add(bucketId(myId));
        return args;
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        String bucketId = bucketId(myId);
        // the name comes from the list of all galleries: a gallery named like another one has the same suffix here as there
        for (MediaGalleries.Gallery gallery : galleries(contentDirectory)) {
            if (gallery.id.equals(bucketId)) {
                return new StorageFolder(myId, ContentDirectoryIDs.VIDEOS_BY_BUCKET_NAMES_FOLDER.getId(), gallery.name, "yaacc",
                        gallery.count, null);
            }
        }
        return null;
    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
        return count(contentDirectory, selection(), selectionArgs(myId));
    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        return new ArrayList<>();
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        return items(contentDirectory, selection(), selectionArgs(myId), myId, firstResult, maxResults, orderby);
    }
}
