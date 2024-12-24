/*
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.browser;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;

import de.yaacc.player.PlayableItem;
import de.yaacc.upnp.UpnpClient;

/**
 * ClickListener when browsing folders.
 *
 * @author Tobias Schoene (the openbit)
 */
public class ContentListClickListener implements View.OnClickListener {
    //FIXME: just for easter egg to play all items on prev button
    public static DIDLObject currentObject;
    private final ContentListFragment contentListFragment;
    private final UpnpClient upnpClient;
    private RecyclerView recyclerView;
    private BrowseContentItemAdapter adapter;

    public ContentListClickListener(UpnpClient upnpClient, ContentListFragment contentListFragment, RecyclerView recyclerView, BrowseContentItemAdapter adapter) {
        this.upnpClient = upnpClient;
        this.contentListFragment = contentListFragment;
        this.adapter = adapter;
        this.recyclerView = recyclerView;

    }

    @Override
    public void onClick(View itemView) {
        int position = recyclerView.getChildAdapterPosition(itemView);
        if (position == -1) {
            return;
        }
        currentObject = adapter.getFolder(position);
        if (currentObject instanceof Container) {
            //Fixme: Cache should store information for different folders....
            //IconDownloadCacheHandler.getInstance().resetCache();
            // if the current id is null, go back to the top level
            String newObjectId = Navigator.ITEM_ROOT_OBJECT_ID;
            Navigator navigator = contentListFragment.getNavigator();
            if (navigator == null || currentObject.getId() == null) {
                navigator = new Navigator();
                contentListFragment.setNavigator(navigator);
            } else {
                newObjectId = adapter.getFolder(position).getId();
            }

            navigator.pushPosition(new Position(position, newObjectId, upnpClient.getProviderDeviceId(), currentObject.getTitle()));
            contentListFragment.populateItemList(true);
        } else if (currentObject instanceof Item) {
            if (currentObject == BrowseContentItemAdapter.LOAD_MORE_FAKE_ITEM) {
                adapter.loadMore();
            } else {
                PlayableItem playableItem = new PlayableItem((Item) currentObject, 0);
                ContentItemPlayTask task = new ContentItemPlayTask(contentListFragment, currentObject);
                if (playableItem.getMimeType() != null && playableItem.getMimeType().startsWith("video")) {
                    task.execute(ContentItemPlayTask.PLAY_CURRENT);
                } else {
                    task.execute(ContentItemPlayTask.PLAY_ALL);
                }


            }
        }
    }


} 