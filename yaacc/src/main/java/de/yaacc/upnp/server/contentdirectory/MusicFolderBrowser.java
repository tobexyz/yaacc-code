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
import java.util.List;

import de.yaacc.R;

/**
 * Browser  for the music folder.
 *
 * @author openbit (Tobias Schoene)
 */
public class MusicFolderBrowser extends ContentBrowser {

    public MusicFolderBrowser(Context context) {
        super(context);
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {

        return new StorageFolder(ContentDirectoryIDs.MUSIC_FOLDER.getId(), ContentDirectoryIDs.ROOT.getId(), getContext().getString(R.string.music), "yaacc", getSize(contentDirectory, myId),
                null);
    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
        return 4;
    }


    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Container> result = new ArrayList<>();
        result.add((Container) new MusicAllTitlesFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.MUSIC_ALL_TITLES_FOLDER.getId(), 0, 1, orderby));
        result.add((Container) new MusicAlbumsFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.MUSIC_ALBUMS_FOLDER.getId(), 0, 1, orderby));
        result.add((Container) new MusicArtistsFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.MUSIC_ARTISTS_FOLDER.getId(), 0, 1, orderby));
        result.add((Container) new MusicGenresFolderBrowser(getContext()).browseMeta(contentDirectory, ContentDirectoryIDs.MUSIC_GENRES_FOLDER.getId(), 0, 1, orderby));
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
