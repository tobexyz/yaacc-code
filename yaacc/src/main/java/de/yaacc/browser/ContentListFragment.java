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

import android.graphics.drawable.Drawable;
import android.widget.ProgressBar;
import android.os.Bundle;
import de.yaacc.util.YaaccLogger;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
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
import de.yaacc.util.ThemeHelper;

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
    private ProgressBar progressBar;
    private ImageButton sortByNameButton;
    private ImageButton sortByDateButton;
    private BrowseContentItemAdapter.SortMode currentSortMode = BrowseContentItemAdapter.SortMode.NAME;


    @Override
    public void onResume() {
        super.onResume();
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (upnpClient.getProviderDevice() != null) {
                    currentProvider.setText(upnpClient.getProviderDevice().getDetails().getFriendlyName());
                    if (navigator != null && navigator.getCurrentPosition() != null && navigator.getCurrentPosition().getDeviceId() != null && upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString().equals(navigator.getCurrentPosition().getDeviceId())) {
                        populateItemList(false);
                    } else {
                        showMainFolder();
                    }
                } else {

                    clearItemList();
                }
            }
        });
    }

    private void init(Bundle savedInstanceState, View contentlistView) {
        upnpClient = ((Yaacc) requireActivity().getApplicationContext()).getUpnpClient();
        backButton = contentlistView.findViewById(R.id.contentListBackButton);
        Drawable icon = ThemeHelper.tintDrawable(getResources().getDrawable(R.drawable.ic_baseline_arrow_back_24, getContext().getTheme()), getContext().getTheme());
        backButton.setImageDrawable(icon);
        backButton.setOnClickListener((v) -> {
            onBackPressed();
        });
        currentFolderNameView = contentlistView.findViewById(R.id.contentListCurrentFolderName);
        currentReceivers = contentlistView.findViewById(R.id.contentListCurrentReceivers);
        currentProvider = contentlistView.findViewById(R.id.contentListCurrentProvider);
        tintCompoundDrawables(currentReceivers);
        tintCompoundDrawables(currentProvider);
        initSortToggle(contentlistView);
        topSeperator = contentlistView.findViewById(R.id.contentListTopSeperator);
        contentList = contentlistView.findViewById(R.id.contentList);
        progressBar = contentlistView.findViewById(R.id.contentListProgressBar);
        YaaccLogger.d("ContentListFragment", "ProgressBar found: " + (progressBar != null));
        contentList.setLayoutManager(new LinearLayoutManager(getActivity()));
        contentList.setFocusable(true);
        contentList.setFocusableInTouchMode(false); // Good for D-Pad primary interaction
        contentList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
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
                        if (navigator != null && navigator.getCurrentPosition() != null && upnpClient.getProviderDevice() != null && upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString().equals(navigator.getCurrentPosition().getDeviceId())) {
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

    /**
     * Wires up the "Name"/"Date" sort toggle (issue #252): finds the
     * buttons, applies persisted sort order from SharedPreferences as the
     * initial visual state, and installs click handlers.
     */
    private void initSortToggle(View contentlistView) {
        sortByNameButton = contentlistView.findViewById(R.id.contentListSortByNameButton);
        sortByDateButton = contentlistView.findViewById(R.id.contentListSortByDateButton);
        if (sortByNameButton == null || sortByDateButton == null || getContext() == null) {
            return;
        }

        String persisted = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getString(getString(R.string.settings_sort_order_key), BrowseContentItemAdapter.SortMode.NAME.name());
        try {
            currentSortMode = BrowseContentItemAdapter.SortMode.valueOf(persisted);
        } catch (IllegalArgumentException e) {
            currentSortMode = BrowseContentItemAdapter.SortMode.NAME;
        }

        // Tapping the already-selected mode's button flips that mode's own
        // direction; tapping the other button switches mode, using that
        // mode's own last-remembered direction (issue #252 Group 4).
        sortByNameButton.setOnClickListener((v) -> {
            if (currentSortMode == BrowseContentItemAdapter.SortMode.NAME && bItemAdapter != null) {
                bItemAdapter.toggleDirection();
                updateSortToggleUi();
            } else {
                onSortModeSelected(BrowseContentItemAdapter.SortMode.NAME);
            }
        });
        sortByDateButton.setOnClickListener((v) -> {
            if (currentSortMode == BrowseContentItemAdapter.SortMode.DATE && bItemAdapter != null) {
                bItemAdapter.toggleDirection();
                updateSortToggleUi();
            } else {
                onSortModeSelected(BrowseContentItemAdapter.SortMode.DATE);
            }
        });
        updateSortToggleUi();
    }

    private void onSortModeSelected(BrowseContentItemAdapter.SortMode mode) {
        if (mode == currentSortMode) {
            return;
        }
        currentSortMode = mode;
        if (getContext() != null) {
            PreferenceManager.getDefaultSharedPreferences(getContext()).edit()
                    .putString(getString(R.string.settings_sort_order_key), mode.name())
                    .apply();
        }
        if (bItemAdapter != null) {
            // Equivalent to re-entering the folder in the new mode: clears
            // and reloads a single fresh chunk, no extra network requests.
            bItemAdapter.setSortMode(mode);
        }
        updateSortToggleUi();
    }

    /**
     * Reflects the current sort mode selection, the live
     * date-sort-availability (issue #252 requirement 5), and each button's
     * own current direction icon (issue #252 Group 4) on the toggle
     * buttons. Each icon reflects the adapter's live
     * {@code isNameAscending()}/{@code isDateAscending()} state
     * independently of which mode is currently selected.
     */
    private void updateSortToggleUi() {
        if (sortByNameButton == null || sortByDateButton == null || getContext() == null) {
            return;
        }
        boolean nameAscending = bItemAdapter == null || bItemAdapter.isNameAscending();
        boolean dateAscending = bItemAdapter != null && bItemAdapter.isDateAscending();
        int sortByNameIconRes = nameAscending ? R.drawable.ic_baseline_sort_by_alpha_32 : R.drawable.ic_baseline_sort_by_alpha_desc_32;
        int sortByDateIconRes = dateAscending ? R.drawable.ic_baseline_date_range_asc_32 : R.drawable.ic_baseline_date_range_32;
        sortByNameButton.setImageDrawable(ThemeHelper.tintDrawable(getResources().getDrawable(sortByNameIconRes, getContext().getTheme()), getContext().getTheme()));
        sortByDateButton.setImageDrawable(ThemeHelper.tintDrawable(getResources().getDrawable(sortByDateIconRes, getContext().getTheme()), getContext().getTheme()));

        boolean dateAvailable = bItemAdapter != null && bItemAdapter.isDateSortAvailable();
        boolean dateSelected = currentSortMode == BrowseContentItemAdapter.SortMode.DATE;
        sortByDateButton.setEnabled(dateAvailable);
        sortByNameButton.setSelected(!dateSelected);
        sortByDateButton.setSelected(dateSelected);
        sortByNameButton.setAlpha(dateSelected ? 0.5f : 1.0f);
        sortByDateButton.setAlpha(!dateAvailable ? 0.3f : (dateSelected ? 1.0f : 0.5f));
    }

    private void tintCompoundDrawables(TextView textView) {
        Drawable[] drawables = textView.getCompoundDrawablesRelative();
        for (int i = 0; i < drawables.length; i++) {
            if (drawables[i] != null) {
                drawables[i] = ThemeHelper.tintDrawable(drawables[i], requireContext().getTheme());
            }
        }
        textView.setCompoundDrawablesRelativeWithIntrinsicBounds(drawables[0], drawables[1], drawables[2], drawables[3]);
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
        Position pos = new Position(0, Navigator.ITEM_ROOT_OBJECT_ID, upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString(), "");
        navigator.pushPosition(pos);
        populateItemList(true);
    }

    private void removeFolderNavigation() {
        backButton.setVisibility(View.GONE);
        currentFolderNameView.setVisibility(View.GONE);
        topSeperator.setVisibility(View.GONE);
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

        YaaccLogger.d(ContentListFragment.class.getName(), "onBackPressed() CurrentPosition: " + navigator.getCurrentPosition());

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
            Position lastPosition = navigator.popPosition(); // First pop is our
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
            int chunkSize = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(getContext()).getString(getContext().getString(R.string.settings_browse_chunk_size_key), "50"));
            bItemAdapter.loadMore((long) (lastPosition.getPositionId() + chunkSize), lastPosition.getPositionId());


        }
        return true;
    }

    private void showFolderNavigation() {
        backButton.setVisibility(View.VISIBLE);
        currentFolderNameView.setVisibility(View.VISIBLE);
        topSeperator.setVisibility(View.VISIBLE);
    }


    private void initBrowsItemAdapter(RecyclerView itemList) {
        if (bItemAdapter == null) {
            if (getContext() == null) {
                return;
            }
            bItemAdapter = new BrowseContentItemAdapter(this, itemList, upnpClient, progressBar);
            // The adapter already reads the same persisted preference on
            // construction; this call is a no-op unless the two diverge, so
            // it never triggers an extra network fetch on top of the
            // initial load.
            bItemAdapter.setSortMode(currentSortMode);
            // Reflect the newly-constructed adapter's own persisted
            // direction state (isNameAscending()/isDateAscending()) right
            // away, rather than waiting for the first data-change callback.
            updateSortToggleUi();
            bItemAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override
                public void onChanged() {
                    updateSortToggleUi();
                }

                @Override
                public void onItemRangeInserted(int positionStart, int itemCount) {
                    updateSortToggleUi();
                }
            });
            itemList.setAdapter(bItemAdapter);
            itemList.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    LinearLayoutManager linearLayoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (linearLayoutManager != null && linearLayoutManager.findLastCompletelyVisibleItemPosition() == bItemAdapter.getItemCount() - 1) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                YaaccLogger.d(getClass().getName(), "scroll int dx, int dy" + dx + ", " + dy);
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

    public void showLoading(int position) {
        requireActivity().runOnUiThread(() -> {
            ProgressBar progressBar = requireView().findViewById(R.id.contentListProgressBar);
            if (progressBar != null) {
                progressBar.setVisibility(View.VISIBLE);
            }
        });
    }

    public void hideLoading(int position) {
        requireActivity().runOnUiThread(() -> {
            ProgressBar progressBar = requireView().findViewById(R.id.contentListProgressBar);
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
        });
    }

    private void clearItemList() {
        requireActivity().runOnUiThread(() -> {
            navigator = new Navigator();
            Position pos = new Position(0, Navigator.ITEM_ROOT_OBJECT_ID, null, "");
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
        YaaccLogger.d(this.getClass().toString(), "device removal called");
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
        ContentDirectoryBrowseResult result = upnpClient.browseSync(new Position(0, item.getParentID(), upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString(), item.getTitle()));
        if (result == null || (result.getResult() != null && result.getResult().getItems().isEmpty())) {
            if (result != null && result.getResult() != null && !result.getResult().getContainers().isEmpty()) {
                play(upnpClient.initializePlayers(upnpClient.toItemList(result.getResult())));
            } else {
                play(upnpClient.initializePlayers(item));
            }
        } else {
            List<Item> items = result.getResult() == null ? new ArrayList<>() : result.getResult().getItems();
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

    public void addToPlayList(DIDLObject currentObject) {
        upnpClient.addToPlaylist(currentObject);
    }
}

