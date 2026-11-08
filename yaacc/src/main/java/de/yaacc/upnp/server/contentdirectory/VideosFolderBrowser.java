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

/**
 * Browser for the video folder: "All videos" and "Galleries" (the directories), as the image folder has them.
 *
 * @author openbit (Tobias Schoene)
 */
public class VideosFolderBrowser extends ContentBrowser {

    public VideosFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        return new StorageFolder(ContentDirectoryIDs.VIDEOS_FOLDER.getId(), ContentDirectoryIDs.ROOT.getId(), getContext().getString(R.string.videos), "yaacc",
                getSize(contentDirectory, myId), null);
    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
        return 2;
    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Container> all = new ArrayList<>();
        all.add((Container) new VideosAllFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.VIDEOS_ALL_FOLDER.getId(), 0, 1, orderby));
        all.add((Container) new VideosByBucketNamesFolderBrowser(getContext()).browseMeta(contentDirectory,
                ContentDirectoryIDs.VIDEOS_BY_BUCKET_NAMES_FOLDER.getId(), 0, 1, orderby));
        // the fixed order "All videos", "Galleries" unless a client asks for a sort
        Map<String, Function<DIDLObject, String>> accessors = new HashMap<>();
        accessors.put(SortSupport.PROPERTY_TITLE, VideoGalleries::titleKey);
        Comparator<DIDLObject> fixedOrder = (a, b) -> 0;
        all.sort(SortSupport.toComparator(orderby, accessors, fixedOrder));
        return VideoGalleries.page(all, firstResult, maxResults);
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        return new ArrayList<>();
    }
}
