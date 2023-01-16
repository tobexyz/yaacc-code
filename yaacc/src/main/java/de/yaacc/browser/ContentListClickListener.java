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

import android.content.Context;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Toast;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;
import de.yaacc.player.PlayableItem;
import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseResult;

/**
 * ClickListener when browsing folders.
 *
 * @author Tobias Schoene (the openbit)
 */
public class ContentListClickListener implements OnItemClickListener {
    //FIXME: just for easter egg to play all items on prev button
    public static DIDLObject currentObject;
    private final ContentListFragment contentListFragment;
    private final UpnpClient upnpClient;
    private Navigator navigator;

    public ContentListClickListener(UpnpClient upnpClient, ContentListFragment contentListFragment) {
        this.upnpClient = upnpClient;
        this.navigator = contentListFragment.getNavigator();
        this.contentListFragment = contentListFragment;

    }

    @Override
    public void onItemClick(AdapterView<?> listView, View arg1, int position,
                            long id) {
        BrowseContentItemAdapter adapter = (BrowseContentItemAdapter) listView.getAdapter();
        currentObject = adapter.getFolder(position);
        if (currentObject instanceof Container) {
            //Fixme: Cache should store information for different folders....
            //IconDownloadCacheHandler.getInstance().resetCache();
            // if the current id is null, go back to the top level
            String newObjectId = Navigator.ITEM_ROOT_OBJECT_ID;
            if (navigator == null || currentObject.getId() == null) {
                navigator = new Navigator();
                contentListFragment.setNavigator(navigator);
            } else {
                newObjectId = adapter.getFolder(position).getId();
            }
            navigator.pushPosition(new Position(newObjectId, upnpClient.getProviderDeviceId()));
            contentListFragment.populateItemList();
        } else if (currentObject instanceof Item) {
            if (currentObject == BrowseContentItemAdapter.LOAD_MORE_FAKE_ITEM) {
                adapter.loadMore();
            } else {
                PlayableItem playableItem = new PlayableItem((Item) currentObject, 0);
                ContentItemPlayTask task = new ContentItemPlayTask(this);
                if (playableItem.getMimeType() != null && playableItem.getMimeType().startsWith("video")) {
                    task.execute(ContentItemPlayTask.PLAY_CURRENT);
                } else {
                    task.execute(ContentItemPlayTask.PLAY_ALL);
                }


            }
        }
    }

    public void playCurrent() {
        play(upnpClient.initializePlayers(currentObject));
    }

    public void playAll() {
        if (currentObject == null) {
            return;
        }
        ContentDirectoryBrowseResult result = upnpClient.browseSync(new Position(currentObject.getParentID(), upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString()));
        if (result == null || (result.getResult() != null && result.getResult().getItems().size() == 0)) {
            Log.d(getClass().getName(), "Browse result of parent no direct items found...");
            if (result != null && result.getResult() != null && result.getResult().getContainers().size() > 0) {
                play(upnpClient.initializePlayers(upnpClient.toItemList(result.getResult())));
            } else {
                play(upnpClient.initializePlayers(currentObject));
            }
        } else {
            List<Item> items = result.getResult() == null ? new ArrayList<>() : result.getResult().getItems();
            Log.d(getClass().getName(), "Browse result items: " + items.size());
            int index = items.indexOf(currentObject);
            if (index > 0) {
                //sort selected item to the beginning
                List<Item> tempItems = new ArrayList<>(items.subList(index, items.size()));
                tempItems.addAll(items.subList(0, index));
                items = tempItems;
            }

            play(upnpClient.initializePlayers(items));
        }
    }


    /**
     * Reacts on selecting an entry in the context menu.
     * <p/>
     * Since this is the onContextClickListener also the reaction on clicking something in the context menu resides in this class
     *
     * @param item               the menu item
     * @param selectedDIDLObject the selected object
     * @param applicationContext the application contex
     * @return always true
     */
    public boolean onContextItemSelected(DIDLObject selectedDIDLObject, MenuItem item, Context applicationContext) {
        if (item.getTitle().equals(applicationContext.getString(R.string.browse_context_play))) {
            new ContentItemPlayTask(this).execute(ContentItemPlayTask.PLAY_CURRENT);
        } else if (item.getTitle().equals(applicationContext.getString(R.string.browse_context_play_all))) {
            new ContentItemPlayTask(this).execute(ContentItemPlayTask.PLAY_ALL);
        } else if (item.getTitle().equals(applicationContext.getString(R.string.browse_context_download))) {
            try {
                upnpClient.downloadItem(selectedDIDLObject);
            } catch (Exception ex) {
                Toast toast = Toast.makeText(applicationContext, "Can't download item: " + ex.getMessage(), Toast.LENGTH_SHORT);
                toast.show();
            }
        } else {
            Toast toast = Toast.makeText(applicationContext, "Magic key pressed (Neither implemented nor defined ;))", Toast.LENGTH_SHORT);
            toast.show();
        }
        return true;
    }

    private void play(List<Player> players) {
        for (Player player : players) {
            if (player != null) {
                player.play();
            }
        }
    }
} 