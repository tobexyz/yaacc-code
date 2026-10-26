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
import android.widget.Toast;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.player.Player;
import de.yaacc.share.PickedAction;
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
    private TextView currentProvider;
    private View footerSeparator;
    // true in the classic layout: this fragment shows the original static footer (current
    // provider, selected receivers). False when the host owns the receiver bar (new layout).
    private boolean showsStaticFooter;
    private UpnpClient upnpClient = null;
    private BrowseContentItemAdapter bItemAdapter;
    private Navigator navigator = null;
    private ImageButton backButton;
    private TextView currentFolderNameView;
    private View topSeperator;
    private ProgressBar progressBar;
    private ImageButton sortByNameButton;
    private ImageButton sortByDateButton;
    private BrowseContentItemAdapter.SortMode currentSortMode = BrowseContentItemAdapter.SortMode.NAME;
    private ImageButton viewModeToggleButton;
    private BrowseContentItemAdapter.ViewMode currentViewMode = BrowseContentItemAdapter.ViewMode.DEFAULT;

    /**
     * Target width of a single card in CARD mode, in dp -- chosen so a
     * normal phone portrait width yields exactly 2 columns, matching
     * design.md decision 3 ("Column count scales with screen width").
     * Converted to px via the context's {@code DisplayMetrics}, not
     * hardcoded, so it scales correctly with device density.
     */
    private static final int TARGET_CARD_WIDTH_DP = 160;


    @Override
    public void onResume() {
        super.onResume();
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (upnpClient.getProviderDevice() != null) {
                    showCurrentProvider();
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
            BrowserHost host = browserHost();
            if (host != null && host.showsBackAtContentRoot() && isAtRoot()) {
                // the root of a server is one step below the server list
                host.showServers();
            } else {
                onBackPressed();
            }
        });
        if (showsBackAtContentRoot()) {
            backButton.setContentDescription(getString(R.string.browser_back));
        }
        currentFolderNameView = contentlistView.findViewById(R.id.contentListCurrentFolderName);
        currentReceivers = contentlistView.findViewById(R.id.contentListCurrentReceivers);
        currentProvider = contentlistView.findViewById(R.id.contentListCurrentProvider);
        footerSeparator = contentlistView.findViewById(R.id.contentListBottomSeperator);
        initFooter();
        initSortToggle(contentlistView);
        topSeperator = contentlistView.findViewById(R.id.contentListTopSeperator);
        contentList = contentlistView.findViewById(R.id.contentList);
        progressBar = contentlistView.findViewById(R.id.contentListProgressBar);
        YaaccLogger.d("ContentListFragment", "ProgressBar found: " + (progressBar != null));
        contentList.setLayoutManager(new LinearLayoutManager(getActivity()));
        contentList.setFocusable(true);
        contentList.setFocusableInTouchMode(false); // Good for D-Pad primary interaction
        contentList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        initViewModeToggle(contentlistView);
        upnpClient.addUpnpClientListener(this);
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {

                showCurrentReceivers();
                if (upnpClient.getProviderDevice() != null) {
                    showCurrentProvider();
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
        sortByNameButton.setAlpha(dateSelected ? 0.6f : 1.0f);
        sortByDateButton.setAlpha(!dateAvailable ? 0.38f : (dateSelected ? 1.0f : 0.6f));
    }

    /**
     * Wires up the list/card view-mode toggle (see the
     * `2026-10-17-browse-card-grid-view` spec): finds the button, applies
     * the persisted view mode from SharedPreferences as the initial
     * state (mirroring {@link #initSortToggle(View)}'s fallback-to-default
     * pattern for the sort toggle), makes the {@code contentList}'s
     * {@code LayoutManager} match that initial mode, installs the click
     * handler, and installs a width-change listener that keeps the grid's
     * span count correct across rotation/multi-window resize while in CARD
     * mode (design.md decision 3), without ever recreating the
     * {@code GridLayoutManager}.
     */
    private void initViewModeToggle(View contentlistView) {
        viewModeToggleButton = contentlistView.findViewById(R.id.contentListViewModeToggle);
        if (viewModeToggleButton == null || getContext() == null) {
            return;
        }

        String persisted = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getString(getString(R.string.settings_browse_view_mode_key), BrowseContentItemAdapter.ViewMode.DEFAULT.name());
        try {
            currentViewMode = BrowseContentItemAdapter.ViewMode.valueOf(persisted);
        } catch (IllegalArgumentException e) {
            currentViewMode = BrowseContentItemAdapter.ViewMode.DEFAULT;
        }
        applyLayoutManagerForViewMode(currentViewMode);

        viewModeToggleButton.setOnClickListener((v) -> onViewModeToggleClicked());
        updateViewModeToggleUi();

        // Covers rotation and multi-window resize (design.md decision 3):
        // only reacts while in CARD mode, and only on an actual width
        // change, applying the new span count via setSpanCount(...) rather
        // than recreating the LayoutManager (confirmed safe in
        // docs/tech.md's "Browse card-grid view" section).
        contentList.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (currentViewMode != BrowseContentItemAdapter.ViewMode.CARD) {
                return;
            }
            int newWidth = right - left;
            int oldWidth = oldRight - oldLeft;
            if (newWidth <= 0 || newWidth == oldWidth) {
                return;
            }
            RecyclerView.LayoutManager layoutManager = contentList.getLayoutManager();
            if (layoutManager instanceof GridLayoutManager) {
                ((GridLayoutManager) layoutManager).setSpanCount(CardGridSpanCount.computeSpanCount(newWidth, targetCardWidthPx()));
            }
        });
    }

    private void onViewModeToggleClicked() {
        BrowseContentItemAdapter.ViewMode newMode = currentViewMode == BrowseContentItemAdapter.ViewMode.LIST
                ? BrowseContentItemAdapter.ViewMode.CARD
                : BrowseContentItemAdapter.ViewMode.LIST;
        currentViewMode = newMode;
        if (getContext() != null) {
            PreferenceManager.getDefaultSharedPreferences(getContext()).edit()
                    .putString(getString(R.string.settings_browse_view_mode_key), newMode.name())
                    .apply();
        }
        if (bItemAdapter != null) {
            bItemAdapter.setViewMode(newMode);
        }
        applyLayoutManagerForViewMode(newMode);
        updateViewModeToggleUi();
    }

    /**
     * Switches {@code contentList}'s {@code LayoutManager} to match
     * {@code mode}: a plain {@link LinearLayoutManager} for LIST, or a
     * {@link GridLayoutManager} sized from the RecyclerView's current
     * width for CARD (design.md decision 3). Subsequent width changes
     * while already in CARD mode are handled by the listener installed in
     * {@link #initViewModeToggle(View)} via {@code setSpanCount(...)}, not
     * by calling this method again.
     */
    private void applyLayoutManagerForViewMode(BrowseContentItemAdapter.ViewMode mode) {
        if (contentList == null || getActivity() == null) {
            return;
        }
        if (mode == BrowseContentItemAdapter.ViewMode.CARD) {
            int initialSpanCount = CardGridSpanCount.computeSpanCount(contentList.getWidth(), targetCardWidthPx());
            contentList.setLayoutManager(new GridLayoutManager(getActivity(), initialSpanCount));
        } else {
            contentList.setLayoutManager(new LinearLayoutManager(getActivity()));
        }
    }

    /**
     * ~160dp target card width (design.md decision 3), converted to px via
     * the context's {@code DisplayMetrics} rather than a hardcoded pixel
     * value, so it scales correctly with device density.
     */
    private int targetCardWidthPx() {
        return (int) (TARGET_CARD_WIDTH_DP * getResources().getDisplayMetrics().density);
    }

    /**
     * Reflects the current view mode as the toggle button's selected/alpha
     * state, same lightweight convention {@link #updateSortToggleUi()}
     * uses for the sort toggle's mode selection (no second drawable
     * needed -- the button's single grid icon, set in the layout XML,
     * represents the toggle action itself).
     */
    private void updateViewModeToggleUi() {
        if (viewModeToggleButton == null) {
            return;
        }
        boolean cardMode = currentViewMode == BrowseContentItemAdapter.ViewMode.CARD;
        viewModeToggleButton.setSelected(cardMode);
        viewModeToggleButton.setAlpha(cardMode ? 1.0f : 0.6f);
    }

    /**
     * Classic layout: the content browsing ends in the original static two-line footer -- the
     * current provider and the selected receivers, each with its icon, not clickable. New layout:
     * the host shows one receiver bar at every level, so this fragment's footer lines and their
     * separator are hidden. All three sit in a container that is never GONE itself (see
     * fragment_content_list.xml), so nothing visible is anchored to a hidden view.
     */
    private void initFooter() {
        BrowserHost host = browserHost();
        showsStaticFooter = host == null || !host.providesReceiverBar();
        if (!showsStaticFooter) {
            currentReceivers.setVisibility(View.GONE);
            currentProvider.setVisibility(View.GONE);
            footerSeparator.setVisibility(View.GONE);
            return;
        }
        tintCompoundDrawables(currentReceivers);
        tintCompoundDrawables(currentProvider);
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

    /** Classic footer, first line: the selected provider. Main thread only. */
    private void showCurrentProvider() {
        if (showsStaticFooter && upnpClient.getProviderDevice() != null) {
            currentProvider.setText(upnpClient.getProviderDevice().getDetails().getFriendlyName());
        }
    }

    /** Classic footer, second line: the selected receivers joined with "; ". Main thread only. */
    private void showCurrentReceivers() {
        if (showsStaticFooter && upnpClient.getReceiverDevices() != null) {
            currentReceivers.setText(upnpClient.getReceiverDevices().stream().map(it -> it.getDetails().getFriendlyName()).collect(Collectors.joining("; ")));
        }
    }

    /** The hosting activity as a {@link BrowserHost}, or null if it is not one. */
    private BrowserHost browserHost() {
        return getActivity() instanceof BrowserHost ? (BrowserHost) getActivity() : null;
    }

    private boolean showsBackAtContentRoot() {
        BrowserHost host = browserHost();
        return host != null && host.showsBackAtContentRoot();
    }

    /**
     * Starts a play, play-all or add-to-playlist action ({@link ContentItemPlayTask}).
     * <p>
     * New layout (the host owns the receiver bar): the receivers are picked per action. The host's
     * "Play on..." sheet opens first; cancelling leaves everything as it was. Confirming stores the
     * chosen receivers ({@link UpnpClient#setReceiverDeviceIds}), then starts the task, and the
     * selection is cleared again once the players have been started ({@link #play(List)} and
     * {@link #addToPlayList}). Only one picked action may be starting at a time (the selection is
     * global): a request while a pick is open or another action is still starting is dropped, with
     * a short message.
     * <p>
     * Classic (no host, or a host without the receiver bar): the task starts at once with the
     * tab-based selection, exactly as before, and nothing is cleared.
     *
     * @param mode       {@link ContentItemPlayTask#PLAY_CURRENT}, {@link ContentItemPlayTask#PLAY_ALL} or
     *                   {@link ContentItemPlayTask#ADD_TO_PLAYLIST}
     * @param onStarted  runs on the main thread right before the task starts (progress, a toast), may be null
     * @param onComplete runs on the main thread when the task is done, may be null
     */
    public void startPlayAction(DIDLObject object, int mode, Runnable onStarted, Runnable onComplete) {
        if (object == null) {
            return;
        }
        BrowserHost host = browserHost();
        if (host == null || !host.providesReceiverBar()) {
            runPlayTask(object, mode, onStarted, onComplete);
            return;
        }
        if (!isAdded() || upnpClient == null
                || !host.pickReceivers(new PickedAction() {
            @Override
            public void onConfirmed(Set<String> receiverIds, Runnable done) {
                if (!isAdded()) {
                    done.run(); // the screen is gone; nothing was changed yet
                    return;
                }
                try {
                    upnpClient.setReceiverDeviceIds(receiverIds);
                    // the single flight ends with the task: play() / addToPlayList() have cleared the
                    // selection by then
                    runPlayTask(object, mode, onStarted, () -> {
                        try {
                            if (onComplete != null) {
                                onComplete.run();
                            }
                        } finally {
                            done.run();
                        }
                    });
                } catch (RuntimeException e) {
                    done.run();
                    throw e;
                }
            }

            @Override
            public void onCancelled() {
                // nothing was changed
            }
        }, null, null)) {
            // a pick is open or an action is still starting, or the screen is not ready: say so
            showBusyMessage();
        }
    }

    private void runPlayTask(DIDLObject object, int mode, Runnable onStarted, Runnable onComplete) {
        if (onStarted != null) {
            onStarted.run();
        }
        new ContentItemPlayTask(this, object, onComplete).execute(mode);
    }

    /** A short, fixed message: the tap did not open the receiver picker. */
    private void showBusyMessage() {
        try {
            if (getContext() != null) {
                Toast.makeText(getContext().getApplicationContext(), R.string.receiver_pick_busy, Toast.LENGTH_SHORT).show();
            }
        } catch (RuntimeException e) {
            YaaccLogger.w("ContentListFragment", "Could not show a message (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * For tests: the client and the layout this fragment acts for, without a view. {@code newLayout}
     * means the host owns the receiver bar, so the receivers are picked per action and cleared
     * after it.
     */
    void attachForTest(UpnpClient client, boolean newLayout) {
        this.upnpClient = client;
        this.showsStaticFooter = !newLayout;
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
                hideFolderNavigationControls();
            });
        }
        navigator = new Navigator();
        Position pos = new Position(0, Navigator.ITEM_ROOT_OBJECT_ID, upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString(), "");
        navigator.pushPosition(pos);
        populateItemList(true);
    }

    /**
     * Hides just the back button / separator while a device switch is in
     * progress and the navigator is about to be replaced. The path label
     * itself is left alone here -- {@link #populateItemList(boolean)} (always
     * called right after) sets it correctly via
     * {@link #updateFolderNavigationUi()} once the new navigator exists.
     */
    private void hideFolderNavigationControls() {
        backButton.setVisibility(showsBackAtContentRoot() ? View.VISIBLE : View.GONE);
        topSeperator.setVisibility(View.GONE);
    }

    /**
     * Reflects the current navigation position: the back button/separator
     * are shown only below the root (nothing to go back to from root), while
     * the path label itself is always visible, showing the provider device's
     * friendly name followed by any folder names below it (e.g. "MyServer"
     * at root, "MyServer &gt; Music &gt; Artists" further down) -- see issue
     * about always showing the server name and truncating long paths. The
     * label's own layout (ellipsize="start", maxLines="1" in both
     * fragment_content_list.xml and layout-land/fragment_content_list.xml)
     * keeps it on one line, truncated from the left so the most specific
     * (rightmost) part of the path stays visible.
     */
    private void updateFolderNavigationUi() {
        boolean atRoot = isAtRoot();
        // GONE correctly reclaims the back button's space at root -- the
        // path label no longer anchors to it for position at all (see
        // below), so there's no RelativeLayout anchor-to-a-hidden-sibling
        // risk here.
        // New layout: the arrow stays at the root and leads back to the server list.
        boolean showBack = !atRoot || showsBackAtContentRoot();
        backButton.setVisibility(showBack ? View.VISIBLE : View.GONE);
        // topSeperator divides the header from the content list below it,
        // independent of whether there's a folder to navigate back to --
        // always visible, not tied to atRoot.
        topSeperator.setVisibility(View.VISIBLE);
        currentFolderNameView.setVisibility(View.VISIBLE);
        currentFolderNameView.setText(buildPathDisplayText());
        // The label's start margin is set here instead of via a
        // layout_toEndOf="@id/contentListBackButton" anchor: anchoring a
        // visible view to a GONE sibling was unreliable across Android
        // skins (observed: the label jumping to the right at root on a real
        // device). Always set explicitly (never "restored" from XML, which
        // isn't possible once mutated) via resources that differ by
        // orientation: portrait places the label beside the button (needs
        // to clear its width), landscape places it below (doesn't) -- see
        // values/dimens.xml and values-land/dimens.xml.
        int marginStartDimenRes = !showBack
                ? R.dimen.folder_name_margin_start_base
                : R.dimen.folder_name_margin_start_with_back;
        ViewGroup.MarginLayoutParams folderNameLayoutParams =
                (ViewGroup.MarginLayoutParams) currentFolderNameView.getLayoutParams();
        folderNameLayoutParams.setMarginStart(getResources().getDimensionPixelSize(marginStartDimenRes));
        currentFolderNameView.setLayoutParams(folderNameLayoutParams);
    }

    /**
     * @return true if the shown folder is the root of the server, or if nothing is loaded yet.
     * Read-only and null-safe, for hosts that decide what back does (the return value of
     * {@link #onBackPressed()} cannot tell: it is always true).
     */
    public boolean isAtRoot() {
        Navigator current = navigator;
        return current == null || current.getCurrentPosition() == null
                || Navigator.ITEM_ROOT_OBJECT_ID.equals(current.getCurrentPosition().getObjectId());
    }

    private String buildPathDisplayText() {
        List<String> segments = new ArrayList<>();
        if (upnpClient != null && upnpClient.getProviderDevice() != null && upnpClient.getProviderDevice().getDetails() != null) {
            String serverName = upnpClient.getProviderDevice().getDetails().getFriendlyName();
            if (serverName != null && !serverName.isEmpty()) {
                segments.add(serverName);
            }
        }
        if (navigator != null) {
            segments.addAll(navigator.getPathNames());
        }
        return segments.stream().collect(Collectors.joining(" > "));
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
                            updateFolderNavigationUi();
                        }

                );
            }

        } else {
            //Fixme: Cache should store information for different folders....
            //IconDownloadCacheHandler.getInstance().resetCache();
            final RecyclerView itemList = contentList;
            Position lastPosition = navigator.popPosition(); // First pop is our
            if (getActivity() != null) {
                getActivity().runOnUiThread(this::updateFolderNavigationUi);
            }
            // currentPosition
            initBrowsItemAdapter(itemList);
            bItemAdapter.clear();
            int chunkSize = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(getContext()).getString(getContext().getString(R.string.settings_browse_chunk_size_key), "50"));
            bItemAdapter.loadMore((long) (lastPosition.getPositionId() + chunkSize), lastPosition.getPositionId());


        }
        return true;
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
            updateFolderNavigationUi();

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
        updateReceiversOnUiThread();
    }

    @Override
    public void receiverDeviceAdded(Device<?, ?, ?> device) {
        updateReceiversOnUiThread();
    }

    private void updateReceiversOnUiThread() {
        if (showsStaticFooter && getActivity() != null) {
            getActivity().runOnUiThread(this::showCurrentReceivers);
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

    @Override
    public void onDestroyView() {
        // The client lives as long as the application: a destroyed view must not keep receiving
        // device events (deviceRemoved would reach a fragment that has no activity any more), and
        // loads still running must not fill a list nobody sees.
        if (upnpClient != null) {
            upnpClient.removeUpnpClientListener(this);
        }
        if (bItemAdapter != null) {
            bItemAdapter.cancelRunningTasks();
        }
        super.onDestroyView();
    }

    public void playItem(DIDLObject item) {
        play(upnpClient.initializePlayers(item));
    }

    /**
     * Builds and plays the queue of all playable siblings of {@code item}'s
     * parent folder, starting at {@code item} -- e.g. tapping an audio item
     * row, or a row's "play all" button.
     * <p>
     * Prefers the {@link BrowseContentItemAdapter}'s own current, already
     * client-side-sorted list of loaded children (the exact list the user
     * sees and tapped from) over re-browsing the server, so the resulting
     * playback order matches the active sort mode/direction instead of the
     * server's unsorted default order (see issue
     * play-all-ignores-sort-order). Falls back to the old server re-browse
     * only when the adapter can't provide that view: no adapter, {@code
     * item} isn't among its currently-loaded children (e.g. a stale
     * reference, or the adapter belongs to a different folder than {@code
     * item}'s parent), or the current folder has no playable items loaded
     * at all (e.g. a containers-only folder).
     */
    public void playAllChildsOfParentFrom(DIDLObject item) {
        if (item == null) {
            return;
        }
        if (bItemAdapter != null) {
            List<DIDLObject> currentObjects = bItemAdapter.getObjects();
            if (currentObjects.contains(item)) {
                List<Item> sortedItems = currentObjects.stream()
                        .filter(Item.class::isInstance)
                        .map(Item.class::cast)
                        .collect(Collectors.toList());
                if (!sortedItems.isEmpty()) {
                    Item target = item instanceof Item ? (Item) item : null;
                    play(upnpClient.initializePlayers(rotateToStart(sortedItems, target)));
                    return;
                }
                // Falls through: current folder has no playable items at
                // all (e.g. containers-only) -- same edge case the server
                // re-browse fallback below already handles.
            }
            // Falls through: item isn't among the adapter's current
            // children -- fall back to the server re-browse safety net.
        }
        playAllChildsOfParentFromServer(item);
    }

    /**
     * Pre-fix fallback: re-browses the parent folder from the server
     * directly, via the no-{@code orderBy} {@code browseSync} overload, and
     * rotates the result so {@code item} plays first. Has no knowledge of
     * the active client-side sort mode, so used only as a safety net when
     * {@link #playAllChildsOfParentFrom(DIDLObject)} can't build the
     * playlist from the adapter's own (sorted) current list.
     */
    private void playAllChildsOfParentFromServer(DIDLObject item) {
        ContentDirectoryBrowseResult result = upnpClient.browseSync(new Position(0, item.getParentID(), upnpClient.getProviderDevice().getIdentity().getUdn().getIdentifierString(), item.getTitle()));
        if (result == null || (result.getResult() != null && result.getResult().getItems().isEmpty())) {
            if (result != null && result.getResult() != null && !result.getResult().getContainers().isEmpty()) {
                play(upnpClient.initializePlayers(upnpClient.toItemList(result.getResult())));
            } else {
                play(upnpClient.initializePlayers(item));
            }
        } else {
            List<Item> items = result.getResult() == null ? new ArrayList<>() : result.getResult().getItems();
            Item target = item instanceof Item ? (Item) item : null;
            play(upnpClient.initializePlayers(rotateToStart(items, target)));
        }
    }

    /**
     * Pure list-rotation helper: rotates {@code items} so {@code item} is
     * first, preserving the relative order of everything else (the rest of
     * the list wraps around). Returns a copy of {@code items} unchanged
     * (not rotated) when {@code item} is {@code null} or not found, or is
     * already first. Extracted from {@link #playAllChildsOfParentFrom(DIDLObject)}
     * so it's unit-testable without any Android/Cling-framework
     * dependency.
     */
    static List<Item> rotateToStart(List<Item> items, Item item) {
        if (items == null) {
            return new ArrayList<>();
        }
        int index = items.indexOf(item);
        if (index <= 0) {
            return new ArrayList<>(items);
        }
        List<Item> rotated = new ArrayList<>(items.subList(index, items.size()));
        rotated.addAll(items.subList(0, index));
        return rotated;
    }


    /**
     * The single place where a started playback ends. New layout: the picked receivers are only
     * needed to start it, so the selection is cleared now (also when no player could be created).
     * Players that run keep their device; an existing share's allowed set was fixed when it was made.
     */
    private void play(List<Player> players) {
        try {
            for (Player player : players) {
                if (player != null) {
                    player.play();
                }
            }
        } finally {
            clearPickedSelection();
        }
    }

    public void addToPlayList(DIDLObject currentObject) {
        try {
            upnpClient.addToPlaylist(currentObject);
        } finally {
            clearPickedSelection();
        }
    }

    /** New layout only; classic keeps its tab-based selection. Safe on the task's background thread. */
    private void clearPickedSelection() {
        if (!showsStaticFooter && upnpClient != null) {
            upnpClient.clearReceiverDevices();
        }
    }
}

