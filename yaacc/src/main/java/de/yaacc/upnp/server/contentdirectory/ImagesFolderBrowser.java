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
 * Browser  for the image folder.
 *
 * @author openbit (Tobias Schoene)
 */
public class ImagesFolderBrowser extends ContentBrowser {

    public ImagesFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        return new StorageFolder(ContentDirectoryIDs.IMAGES_FOLDER.getId(), ContentDirectoryIDs.ROOT.getId(), getContext().getString(R.string.images), "yaacc", getSize(contentDirectory, myId),
                null);

    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
        return 2;
    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Container> result = new ArrayList<>();
        result.add((Container) new ImagesAllFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.IMAGES_ALL_FOLDER.getId(), 0, 1, orderby));
        result.add((Container) new ImagesByBucketNamesFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.IMAGES_BY_BUCKET_NAMES_FOLDER.getId(), 0, 1, orderby));

        Map<String, Function<DIDLObject, String>> accessorMap = new HashMap<>();
        accessorMap.put(SortSupport.PROPERTY_TITLE, MediaGalleries::titleKey);
        // No natural "default" sort key for this fixed, hand-picked list of top-level
        // image folders - a no-op comparator relies on List.sort's stability to keep
        // today's existing fixed order ("All images", "Galleries") unchanged
        // when no SortCriteria was requested.
        Comparator<DIDLObject> defaultComparator = (a, b) -> 0;
        result.sort(SortSupport.toComparator(orderby, accessorMap, defaultComparator));

        return MediaGalleries.page(result, firstResult, maxResults);
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        return new ArrayList<>();
    }

}
