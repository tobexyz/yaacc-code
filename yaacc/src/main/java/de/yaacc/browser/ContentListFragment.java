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

import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.item.Item;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;
import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseResult;

/**
 * Activity for browsing devices and folders. Represents the entrypoint for the whole application.
 *
 * @author Tobias Schöne (the openbit)
 */
public class ContentListFragment extends Fragment implements OnClickListener,
        UpnpClientListener, OnBackPressedListener {
    public static final String CONTENT_LIST_NAVIGATOR = "CONTENT_LIST_NAVIGATOR";
    protected RecyclerView contentList;
    private UpnpClient upnpClient = null;
    private BrowseContentItemAdapter bItemAdapter;
    private DIDLObject selectedDIDLObject;
    private Navigator navigator = null;


    @Override
    public void onResume() {
        super.onResume();
        Thread thread = new Thread(() -> {
            if (upnpClient.getProviderDevice() != null) {
                if (navigator != null && navigator.getCurrentPosition().getDeviceId() != null) {
                    populateItemList(false);
                } else {
                    showMainFolder();
                }
            } else {

                clearItemList();
            }
        });
        thread.start();
    }
    
    private void init(Bundle savedInstanceState, View contentlistView) {
        upnpClient = ((Yaacc) requireActivity().getApplicationContext()).getUpnpClient();
        contentList = (RecyclerView) contentlistView.findViewById(R.id.contentList);
        contentList.setLayoutManager(new LinearLayoutManager(getActivity()));
        registerForContextMenu(contentList);
        upnpClient.addUpnpClientListener(this);
        Thread thread = new Thread(() -> {
            if (upnpClient.getProviderDevice() != null) {
                if (savedInstanceState == null || savedInstanceState.getSerializable(CONTENT_LIST_NAVIGATOR) == null) {

                    showMainFolder();
                } else {
                    navigator = (Navigator) savedInstanceState.getSerializable(CONTENT_LIST_NAVIGATOR);
                    populateItemList(true);
                }
            } else {

                clearItemList();
            }
        });
        thread.start();

    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(CONTENT_LIST_NAVIGATOR, navigator);
    }


    /**
     * Tries to populate the browsing area if a providing device is configured
     */
    private void showMainFolder() {
        navigator = new Navigator();
        Position pos = new Position(Navigator.ITEM_ROOT_OBJECT_ID, upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString());
        navigator.pushPosition(pos);
        populateItemList(true);
    }

    @Override
    public void onClick(View v) {
        populateItemList(false);
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (item.getTitle().equals(getString(R.string.browse_context_play))) {
            new ContentItemPlayTask(this, selectedDIDLObject).execute(ContentItemPlayTask.PLAY_CURRENT);
        } else if (item.getTitle().equals(getString(R.string.browse_context_play_all))) {
            new ContentItemPlayTask(this, selectedDIDLObject).execute(ContentItemPlayTask.PLAY_ALL);
        } else if (item.getTitle().equals(getString(R.string.browse_context_download))) {
            try {
                upnpClient.downloadItem(selectedDIDLObject);
            } catch (Exception ex) {
                Toast toast = Toast.makeText(getActivity(), "Can't download item: " + ex.getMessage(), Toast.LENGTH_SHORT);
                toast.show();
            }
        } else {
            Toast toast = Toast.makeText(getActivity(), "Magic key pressed (Neither implemented nor defined ;))", Toast.LENGTH_SHORT);
            toast.show();
        }
        return true;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /**
     * Steps 'up' in the folder hierarchy or closes App if on device level.
     */
    public boolean onBackPressed() {

        Log.d(ContentListFragment.class.getName(), "onBackPressed() CurrentPosition: " + navigator.getCurrentPosition());
        if (bItemAdapter != null) {
            bItemAdapter.cancelRunningTasks();
        }
        String currentObjectId = navigator.getCurrentPosition() == null ? Navigator.ITEM_ROOT_OBJECT_ID : navigator.getCurrentPosition().getObjectId();
        if (Navigator.ITEM_ROOT_OBJECT_ID.equals(currentObjectId)) {
            if (requireActivity().getParent() instanceof TabBrowserActivity) {
                ((TabBrowserActivity) requireActivity().getParent()).setCurrentTab(BrowserTabs.SERVER);
            }

        } else {
            //Fixme: Cache should store information for different folders....
            //IconDownloadCacheHandler.getInstance().resetCache();
            final RecyclerView itemList = contentList;
            navigator.popPosition(); // First pop is our
            // currentPosition
            initBrowsItemAdapter(itemList);
            bItemAdapter.clear();
            bItemAdapter.loadMore();

        }
        return true;
    }


    private void initBrowsItemAdapter(RecyclerView itemList) {
        if (bItemAdapter == null) {
            bItemAdapter = new BrowseContentItemAdapter(this, itemList, upnpClient, navigator);
            itemList.setAdapter(bItemAdapter);
            itemList.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    bItemAdapter.loadMore();
                }
            });
        }
    }

    /**
     * Creates context menu for certain actions on a specific item.
     */
    @Override
    public void onCreateContextMenu(@NonNull ContextMenu menu, @NonNull View v,
                                    ContextMenuInfo menuInfo) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        if (v instanceof ListView) {
            ListView listView = (ListView) v;
            Object item = listView.getAdapter().getItem(info.position);
            if (item instanceof DIDLObject) {
                selectedDIDLObject = (DIDLObject) item;
            }
        }
        menu.setHeaderTitle(v.getContext().getString(
                R.string.browse_context_title));
        ArrayList<String> menuItems = new ArrayList<>();
        menuItems.add(v.getContext().getString(R.string.browse_context_play_all));
        menuItems.add(v.getContext().getString(R.string.browse_context_play));
        //menuItems.add(v.getContext().getString( R.string.browse_context_add_to_playplist));
        menuItems.add(v.getContext()
                .getString(R.string.browse_context_download));
        for (int i = 0; i < menuItems.size(); i++) {
            menu.add(Menu.NONE, i, i, menuItems.get(i));
        }
    }

    /**
     * Selects the place in the UI where the items are shown and renders the
     * content directory
     */
    public void populateItemList(boolean clear) {


        requireActivity().runOnUiThread(() -> {
            if (bItemAdapter != null) {
                bItemAdapter.cancelRunningTasks();
            }
            initBrowsItemAdapter(contentList);
            if (clear) bItemAdapter.clear();
            bItemAdapter.loadMore();
        });
    }

    private void clearItemList() {
        requireActivity().runOnUiThread(() -> {
            navigator = new Navigator();
            Position pos = new Position(Navigator.ITEM_ROOT_OBJECT_ID, null);
            navigator.pushPosition(pos);
            bItemAdapter.clear();
        });
    }


    /**
     * Refreshes the shown devices when device is added.
     */
    @Override
    public void deviceAdded(Device<?, ?, ?> device) {

    }

    /**
     * Refreshes the shown devices when device is removed.
     */
    @Override
    public void deviceRemoved(Device<?, ?, ?> device) {
        Log.d(this.getClass().toString(), "device removal called");
        if (!device.equals(upnpClient.getProviderDevice())) {
            //    clearItemList();
        }
    }

    @Override
    public void deviceUpdated(Device<?, ?, ?> device) {

    }

    /**
     * Returns Object containing about the current navigation way
     *
     * @return information about current navigation
     */
    public Navigator getNavigator() {
        return navigator;
    }

    public void setNavigator(Navigator navigator) {
        this.navigator = navigator;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_content_list, container, false);
        init(savedInstanceState, v);
        return v;
    }

    public void playItem(DIDLObject item) {
        play(upnpClient.initializePlayers(item));
    }

    public void playAllChildsOfParentFrom(DIDLObject item) {
        if (item == null) {
            return;
        }
        ContentDirectoryBrowseResult result = upnpClient.browseSync(new Position(item.getParentID(), upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString()));
        if (result == null || (result.getResult() != null && result.getResult().getItems().size() == 0)) {
            Log.d(getClass().getName(), "Browse result of parent no direct items found...");
            if (result != null && result.getResult() != null && result.getResult().getContainers().size() > 0) {
                play(upnpClient.initializePlayers(upnpClient.toItemList(result.getResult())));
            } else {
                play(upnpClient.initializePlayers(item));
            }
        } else {
            List<Item> items = result.getResult() == null ? new ArrayList<>() : result.getResult().getItems();
            Log.d(getClass().getName(), "Browse result items: " + items.size());
            int index = items.indexOf(item);
            if (index > 0) {
                //sort selected item to the beginning
                List<Item> tempItems = new ArrayList<>(items.subList(index, items.size()));
                tempItems.addAll(items.subList(0, index));
                items = tempItems;
            }

            play(upnpClient.initializePlayers(items));
        }
    }


    private void play(List<Player> players) {
        for (Player player : players) {
            if (player != null) {
                player.play();
            }
        }
    }
}

