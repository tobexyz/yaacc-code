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

/** "Galleries": one container per bucket of the media store (a directory with videos in the media paths). */
public class VideosByBucketNamesFolderBrowser extends VideoBrowser {

    public VideosByBucketNamesFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        return new StorageFolder(ContentDirectoryIDs.VIDEOS_BY_BUCKET_NAMES_FOLDER.getId(), ContentDirectoryIDs.VIDEOS_FOLDER.getId(),
                getContext().getString(R.string.bucket_names), "yaacc", getSize(contentDirectory, myId), null);
    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
        return galleries(contentDirectory).size();
    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Container> all = new ArrayList<>();
        for (VideoGalleries.Gallery gallery : galleries(contentDirectory)) {
            all.add(new StorageFolder(ContentDirectoryIDs.VIDEOS_BY_BUCKET_NAME_PREFIX.getId() + gallery.id,
                    ContentDirectoryIDs.VIDEOS_BY_BUCKET_NAMES_FOLDER.getId(), gallery.name, "yaacc", gallery.count, null));
        }
        // sorted by the name without regard to the case, then paged: a page of galleries, not of rows of the media store
        Map<String, Function<DIDLObject, String>> accessors = new HashMap<>();
        accessors.put(SortSupport.PROPERTY_TITLE, VideoGalleries::titleKey);
        Comparator<DIDLObject> tieBreaker = VideoGalleries.inTitleOrder();
        all.sort(SortSupport.toComparator(orderby, accessors, tieBreaker).thenComparing(tieBreaker));
        return VideoGalleries.page(all, firstResult, maxResults);
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        return new ArrayList<>();
    }
}
