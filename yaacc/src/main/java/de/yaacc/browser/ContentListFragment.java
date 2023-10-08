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
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    private TextView currentReceivers;
    private UpnpClient upnpClient = null;
    private BrowseContentItemAdapter bItemAdapter;
    private Navigator navigator = null;
    private ImageButton backButton;
    private TextView currentFolderNameView;
    private View topSeperator;
    private TextView currentProvider;


    @Override
    public void onResume() {
        super.onResume();
        Thread thread = new Thread(() -> {
            if (upnpClient.getProviderDevice() != null) {
                currentProvider.setText(upnpClient.getProviderDevice().getDetails().getFriendlyName());
                if (navigator != null && navigator.getCurrentPosition().getDeviceId() != null && upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString().equals(navigator.getCurrentPosition().getDeviceId())) {
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
        backButton = contentlistView.findViewById(R.id.contentListBackButton);
        backButton.setOnClickListener((v) -> {
            onBackPressed();
        });
        currentFolderNameView = contentlistView.findViewById(R.id.contentListCurrentFolderName);
        currentReceivers = contentlistView.findViewById(R.id.contentListCurrentReceivers);
        currentProvider = contentlistView.findViewById(R.id.contentListCurrentProvider);
        topSeperator = contentlistView.findViewById(R.id.contentListTopSeperator);
        contentList = contentlistView.findViewById(R.id.contentList);
        contentList.setLayoutManager(new LinearLayoutManager(getActivity()));
        upnpClient.addUpnpClientListener(this);
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {

                if (upnpClient.getReceiverDevices() != null) {
                    currentReceivers.setText(upnpClient.getReceiverDevices().stream().map(it -> it.getDetails().getFriendlyName()).collect(Collectors.joining("; ")));
                }
                if (upnpClient.getProviderDevice() != null) {
                    currentProvider.setText(upnpClient.getProviderDevice().getDetails().getFriendlyName());
                    if (savedInstanceState == null || savedInstanceState.getSerializable(CONTENT_LIST_NAVIGATOR) == null) {
                        showMainFolder();
                    } else {
                        navigator = (Navigator) savedInstanceState.getSerializable(CONTENT_LIST_NAVIGATOR);
                        if (navigator.getCurrentPosition() != null && upnpClient.getProviderDevice() != null && upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString().equals(navigator.getCurrentPosition().getDeviceId())) {
                            populateItemList(true);
                        } else {
                            showMainFolder();
                        }
                    }
                } else {
                    clearItemList();
                }
            });
        }
        ;

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
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                removeFolderNavigation();
            });
        }
        navigator = new Navigator();
        Position pos = new Position(Navigator.ITEM_ROOT_OBJECT_ID, upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString(), "");
        navigator.pushPosition(pos);
        populateItemList(true);
    }

    private void removeFolderNavigation() {
        backButton.setVisibility(View.GONE);
        currentFolderNameView.setVisibility(View.GONE);
        topSeperator.setVisibility(View.GONE);
        ((RelativeLayout.LayoutParams) contentList.getLayoutParams()).addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE);
        currentFolderNameView.setText("");
    }

    @Override
    public void onClick(View v) {
        populateItemList(false);
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
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                            removeFolderNavigation();
                        }

                );
            }
            if (requireActivity().getParent() instanceof TabBrowserActivity) {
                ((TabBrowserActivity) requireActivity().getParent()).setCurrentTab(BrowserTabs.SERVER);
            }

        } else {
            //Fixme: Cache should store information for different folders....
            //IconDownloadCacheHandler.getInstance().resetCache();
            final RecyclerView itemList = contentList;
            navigator.popPosition(); // First pop is our
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (Navigator.ITEM_ROOT_OBJECT_ID.equals(navigator.getCurrentPosition().getObjectId())) {
                        removeFolderNavigation();
                    } else {
                        showFolderNavigation();
                        currentFolderNameView.setText(navigator.getPathNames().stream().collect(Collectors.joining(" > ")));
                    }
                });
            }
            // currentPosition
            initBrowsItemAdapter(itemList);
            bItemAdapter.clear();
            bItemAdapter.loadMore();

        }
        return true;
    }

    private void showFolderNavigation() {
        backButton.setVisibility(View.VISIBLE);
        currentFolderNameView.setVisibility(View.VISIBLE);
        topSeperator.setVisibility(View.VISIBLE);
        ((RelativeLayout.LayoutParams) contentList.getLayoutParams()).removeRule(RelativeLayout.ALIGN_PARENT_TOP);
    }


    private void initBrowsItemAdapter(RecyclerView itemList) {
        if (bItemAdapter == null) {
            if (getContext() == null) {
                return;
            }
            bItemAdapter = new BrowseContentItemAdapter(this, itemList, upnpClient);
            itemList.setAdapter(bItemAdapter);
            itemList.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    LinearLayoutManager linearLayoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();


                    if (linearLayoutManager != null && linearLayoutManager.findLastCompletelyVisibleItemPosition() == bItemAdapter.getItemCount() - 1) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                Log.d(getClass().getName(), "scroll int dx, int dy" + dx + ", " + dy);
                                bItemAdapter.loadMore();
                            });
                        }
                    }


                }
            });
        }
    }


    /**
     * Selects the place in the UI where the items are shown and renders the
     * content directory
     */
    public void populateItemList(boolean clear) {
        requireActivity().runOnUiThread(() -> {
            if (Navigator.ITEM_ROOT_OBJECT_ID.equals(navigator.getCurrentPosition().getObjectId())) {
                removeFolderNavigation();
            } else {
                showFolderNavigation();
                currentFolderNameView.setText(navigator.getPathNames().stream().collect(Collectors.joining(" > ")));
            }
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
            Position pos = new Position(Navigator.ITEM_ROOT_OBJECT_ID, null, "");
            navigator.pushPosition(pos);
            if (bItemAdapter != null) {
                bItemAdapter.clear();
            }
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
        if (device.equals(upnpClient.getProviderDevice())) {
            clearItemList();
        }
    }

    @Override
    public void deviceUpdated(Device<?, ?, ?> device) {

    }

    @Override
    public void receiverDeviceRemoved(Device<?, ?, ?> device) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (upnpClient.getReceiverDevices() != null) {
                    currentReceivers.setText(upnpClient.getReceiverDevices().stream().map(it -> it.getDetails().getFriendlyName()).collect(Collectors.joining("; ")));
                }
            });
        }
    }

    @Override
    public void receiverDeviceAdded(Device<?, ?, ?> device) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (upnpClient.getReceiverDevices() != null) {
                    currentReceivers.setText(upnpClient.getReceiverDevices().stream().map(it -> it.getDetails().getFriendlyName()).collect(Collectors.joining("; ")));
                }
            });
        }
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
        return inflater.inflate(R.layout.fragment_content_list, container, false);

    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        init(savedInstanceState, view);
    }

    public void playItem(DIDLObject item) {
        play(upnpClient.initializePlayers(item));
    }

    public void playAllChildsOfParentFrom(DIDLObject item) {
        if (item == null) {
            return;
        }
        ContentDirectoryBrowseResult result = upnpClient.browseSync(new Position(item.getParentID(), upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString(), item.getTitle()));
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

