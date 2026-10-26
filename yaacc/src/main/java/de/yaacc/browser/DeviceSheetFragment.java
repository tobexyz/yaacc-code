/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
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
package de.yaacc.browser;

import android.content.DialogInterface;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.player.Player;
import de.yaacc.player.PlayerListChangeNotifier;
import de.yaacc.share.PickSheetResult;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;
import de.yaacc.util.YaaccLogger;

/**
 * Bottom sheet listing every receiver with its device controls and, under
 * each, the yaacc players running on it (spec 2026-10-23-unified-device-sheet).
 * <p>
 * While started (visible) it is subscribed to the shared
 * {@link RendererStatusMonitor}, to player add/remove events and to a 2 s
 * local-device poll; all three are dropped in {@link #onStop()} (and in
 * {@link #onDestroyView()} as a backstop), and callbacks that still arrive
 * afterwards are ignored. An in-app dialog only:
 * nothing is exported and it is opened only through {@link #show(FragmentManager)} or
 * {@link #showPicker}. See {@code docs/tech.md}, "Unified device sheet", for the Material API details.
 * <p>
 * <b>Pick mode</b> ({@link #showPicker}, "Play on..."): the receiver picker of the new layout (and of
 * a shared file in classic). Receivers with checkboxes, a confirm and a cancel button; no controls,
 * no sessions, no status polling. The ticks are the adapter's own set, never the stored receiver
 * selection, so a cancel changes nothing. The answer is delivered once through
 * {@link FragmentManager#setFragmentResult}: a confirm with the ticked ids when the confirm button
 * is pressed with at least one receiver (with none, a hint is shown and the sheet stays open), a
 * cancel from {@link #onDismiss} for every other way of closing ({@link PickSheetResult} makes
 * double delivery impossible). A restored sheet whose pick is gone (the host lost it) closes itself
 * without an answer.
 */
public class DeviceSheetFragment extends BottomSheetDialogFragment {
    public static final String TAG = "DeviceSheetFragment";
    /** Tag of the picker: separate from {@link #TAG}, so the host can tell the two apart. */
    public static final String TAG_PICK = "DeviceSheetPicker";

    /** Keys of the result bundle sent with {@link FragmentManager#setFragmentResult}. */
    static final String RESULT_PICK_ID = "pickId";
    static final String RESULT_CONFIRMED = "confirmed";
    static final String RESULT_RECEIVER_IDS = "receiverIds";

    private static final String ARG_PICK = "pick";
    private static final String ARG_REQUEST_KEY = "requestKey";
    private static final String ARG_PICK_ID = "pickId";
    private static final String ARG_FOR_SHARE = "forShare";
    private static final String ARG_SHARE_NAME = "shareName";
    private static final String ARG_INITIAL_IDS = "initialIds";
    private static final long PICK_REFRESH_DELAY_MS = 300;
    private static final long LOCAL_POLL_INTERVAL_MS = 2000;
    private static final float HALF_EXPANDED_RATIO = 0.5f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private UpnpClient upnpClient;
    // Non-null exactly while the view exists; every callback checks it.
    private DeviceSheetAdapter adapter;
    private boolean sheetStateApplied;
    // True between startLiveUpdates and stopLiveUpdates; prevents a double subscribe.
    private boolean liveUpdatesRunning;
    // The list was just built in onViewCreated; the first onStart needn't rebuild it.
    private boolean listFresh;
    // Pick mode: makes "exactly one answer" true; see PickSheetResult.
    private final PickSheetResult resultGate = new PickSheetResult();
    // Pick mode: true between onStart and onStop while new/removed devices refresh the list.
    private boolean pickRefreshRunning;
    private TextView pickHint;

    // Pick mode: a receiver that appears (discovery takes seconds) or leaves while the picker is open.
    private final UpnpClientListener pickDeviceListener = new UpnpClientListener() {
        @Override
        public void deviceAdded(Device<?, ?, ?> device) {
            schedulePickRefresh();
        }

        @Override
        public void deviceRemoved(Device<?, ?, ?> device) {
            schedulePickRefresh();
        }

        @Override
        public void deviceUpdated(Device<?, ?, ?> device) {
        }

        @Override
        public void receiverDeviceAdded(Device<?, ?, ?> device) {
        }

        @Override
        public void receiverDeviceRemoved(Device<?, ?, ?> device) {
        }
    };

    private final Runnable pickRefresher = () -> {
        if (pickRefreshRunning && adapter != null) {
            adapter.refresh();
        }
    };

    private final RendererStatusMonitor.StatusListener statusListener = status -> {
        if (adapter != null) {
            adapter.onStatusChanged(status);
        }
    };

    // An inner class, so it references this fragment; it is unregistered in
    // onStop (and onDestroyView as a backstop), so PlayerService never keeps
    // a stopped or dead sheet alive.
    private final PlayerListChangeNotifier.Listener playerListListener = new PlayerListChangeNotifier.Listener() {
        @Override
        public void onPlayerAdded(Player player) {
            if (adapter != null) {
                adapter.refresh();
            }
        }

        @Override
        public void onPlayerRemoved(Player player) {
            if (adapter != null) {
                adapter.refresh();
            }
        }
    };

    private final Runnable localPoller = new Runnable() {
        @Override
        public void run() {
            if (!liveUpdatesRunning || adapter == null) {
                return;
            }
            adapter.refreshLiveState();
            handler.postDelayed(this, LOCAL_POLL_INTERVAL_MS);
        }
    };

    /**
     * Shows the normal sheet unless it (or the picker) is already showing or the state is saved
     * (a commit then would throw).
     */
    public static void show(FragmentManager fragmentManager) {
        if (fragmentManager.isStateSaved() || fragmentManager.findFragmentByTag(TAG) != null
                || fragmentManager.findFragmentByTag(TAG_PICK) != null) {
            return;
        }
        new DeviceSheetFragment().show(fragmentManager, TAG);
    }

    /**
     * Shows the receiver picker unless one is already showing or the state is saved.
     *
     * @param requestKey the key the result is sent under ({@link FragmentManager#setFragmentResult})
     * @param pickId     reported back in the result so the host can tell a stale answer apart
     * @param forShare   true if the pick is for a pending share (shows its name)
     * @param shareName  the cleaned name of the shared item, may be null or empty (a fixed text is shown)
     * @param initialIds receivers that start ticked, may be null
     * @return false if the picker could not be shown
     */
    public static boolean showPicker(FragmentManager fragmentManager, String requestKey, String pickId,
                                     boolean forShare, String shareName, Collection<String> initialIds) {
        if (fragmentManager.isStateSaved() || fragmentManager.findFragmentByTag(TAG_PICK) != null) {
            return false;
        }
        DeviceSheetFragment sheet = new DeviceSheetFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_PICK, true);
        args.putString(ARG_REQUEST_KEY, requestKey);
        args.putString(ARG_PICK_ID, pickId);
        args.putBoolean(ARG_FOR_SHARE, forShare);
        args.putString(ARG_SHARE_NAME, shareName);
        args.putStringArrayList(ARG_INITIAL_IDS, initialIds == null ? new ArrayList<>() : new ArrayList<>(initialIds));
        sheet.setArguments(args);
        sheet.show(fragmentManager, TAG_PICK);
        return true;
    }

    /** Whether a picker is showing (or being restored). */
    public static boolean isPickerShowing(FragmentManager fragmentManager) {
        return fragmentManager.findFragmentByTag(TAG_PICK) != null;
    }

    /** Closes the normal sheet if it is showing (a pick takes over). Never throws for a saved state. */
    public static void dismissNormal(FragmentManager fragmentManager) {
        dismiss(fragmentManager.findFragmentByTag(TAG));
    }

    /** Closes the picker if it is showing. Its cancel answer, if still wanted, is sent from onDismiss. */
    public static void dismissPicker(FragmentManager fragmentManager) {
        dismiss(fragmentManager.findFragmentByTag(TAG_PICK));
    }

    private static void dismiss(Fragment fragment) {
        if (fragment instanceof DeviceSheetFragment) {
            ((DeviceSheetFragment) fragment).dismissAllowingStateLoss();
        }
    }

    private boolean isPickMode() {
        Bundle args = getArguments();
        return args != null && args.getBoolean(ARG_PICK, false);
    }

    /**
     * AppTheme sets no {@code bottomSheetDialogTheme}, so the default would be
     * the light {@code Theme.Design} dialog theme even in night mode. The
     * overlay keeps AppTheme's DayNight colors.
     */
    @Override
    public int getTheme() {
        return R.style.ThemeOverlay_App_DeviceSheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(isPickMode() ? R.layout.fragment_device_sheet_pick : R.layout.fragment_device_sheet,
                container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        upnpClient = ((Yaacc) requireContext().getApplicationContext()).getUpnpClient();
        RecyclerView list = view.findViewById(R.id.deviceSheetList);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setItemAnimator(null); // no flashing on live updates, as in the Receiver tab
        Bundle args = getArguments();
        adapter = isPickMode()
                ? new DeviceSheetAdapter(requireContext(), upnpClient, true,
                args == null ? null : args.getStringArrayList(ARG_INITIAL_IDS))
                : new DeviceSheetAdapter(requireContext(), upnpClient);
        if (!isTouchInput()) {
            adapter.requestInitialFocus();
        }
        list.setAdapter(adapter);
        adapter.refresh();
        listFresh = true;
        if (isPickMode()) {
            setUpPickViews(view);
        }
    }

    private void setUpPickViews(@NonNull View view) {
        Bundle args = requireArguments();
        TextView title = view.findViewById(R.id.deviceSheetTitle);
        ViewCompat.setAccessibilityHeading(title, true);
        // the cleaned name of the shared item, through the one cleaner, with a fixed fallback text
        String subtitle = PickTitle.subtitle(args.getBoolean(ARG_FOR_SHARE, false),
                args.getString(ARG_SHARE_NAME), getString(R.string.share_unnamed_file));
        TextView subtitleView = view.findViewById(R.id.deviceSheetPickSubtitle);
        if (subtitle != null) {
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(View.VISIBLE);
        }
        pickHint = view.findViewById(R.id.deviceSheetPickHint);
        View confirm = view.findViewById(R.id.deviceSheetPickConfirm);
        confirm.setOnClickListener(v -> onConfirmPressed());
        // the accessible name starts with the visible word, so "OK" works for Voice Access too
        confirm.setContentDescription(getString(R.string.receiver_pick_confirm_description,
                getString(R.string.receiver_pick_confirm)));
        // closing is reported as a cancel from onDismiss, the same as back, a tap outside or a swipe
        view.findViewById(R.id.deviceSheetPickCancel).setOnClickListener(v -> dismissAllowingStateLoss());
    }

    private void onConfirmPressed() {
        if (adapter == null) {
            return;
        }
        PickSheetResult.Action action = resultGate.confirm(adapter.pickedIds());
        if (action == PickSheetResult.Action.SEND_CONFIRM) {
            sendResult(true, resultGate.confirmedIds());
            dismissAllowingStateLoss(); // onDismiss follows; the gate keeps it silent
        } else if (action == PickSheetResult.Action.SHOW_HINT && pickHint != null) {
            pickHint.setVisibility(View.VISIBLE);
            pickHint.announceForAccessibility(pickHint.getText());
        }
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        // every way of closing ends here (back, outside tap, swipe, cancel, dismiss())
        if (isPickMode() && resultGate.dismissed() == PickSheetResult.Action.SEND_CANCEL) {
            sendResult(false, Collections.<String>emptySet());
        }
        super.onDismiss(dialog);
    }

    /** Pick mode: the one answer, to the host that is listening under the request key. Carries ids only. */
    private void sendResult(boolean confirmed, Set<String> receiverIds) {
        Bundle args = getArguments();
        String requestKey = args == null ? null : args.getString(ARG_REQUEST_KEY);
        String pickId = args == null ? null : args.getString(ARG_PICK_ID);
        if (requestKey == null || pickId == null) {
            return;
        }
        Bundle result = new Bundle();
        result.putString(RESULT_PICK_ID, pickId);
        result.putBoolean(RESULT_CONFIRMED, confirmed);
        result.putStringArrayList(RESULT_RECEIVER_IDS, new ArrayList<>(receiverIds));
        try {
            getParentFragmentManager().setFragmentResult(requestKey, result);
        } catch (IllegalStateException e) {
            // not attached any more: the host abandons the pick itself when it goes away
            YaaccLogger.w(TAG, "The receiver pick answer could not be sent (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Pick mode: does the host still know this pick? A restored sheet of a lost pick must not stay open. */
    private boolean isPickStillOpen() {
        Bundle args = getArguments();
        String pickId = args == null ? null : args.getString(ARG_PICK_ID);
        return getActivity() instanceof BrowserHost && ((BrowserHost) getActivity()).isPickOpen(pickId);
    }

    private void schedulePickRefresh() {
        // device events come from network threads
        handler.removeCallbacks(pickRefresher);
        handler.postDelayed(pickRefresher, PICK_REFRESH_DELAY_MS);
    }

    private void startPickRefresh() {
        if (pickRefreshRunning || upnpClient == null) {
            return;
        }
        pickRefreshRunning = true;
        if (!listFresh && adapter != null) {
            adapter.refresh(); // receivers may have come or gone while stopped
        }
        listFresh = false;
        upnpClient.addUpnpClientListener(pickDeviceListener);
    }

    private void stopPickRefresh() {
        if (!pickRefreshRunning) {
            return;
        }
        pickRefreshRunning = false;
        handler.removeCallbacks(pickRefresher);
        if (upnpClient != null) {
            upnpClient.removeUpnpClientListener(pickDeviceListener);
        }
    }

    @Override
    public void onStart() {
        super.onStart(); // shows the dialog
        if (isPickMode()) {
            if (!isPickStillOpen()) {
                // restored after a rotation or process death: the pending action is gone, so an answer
                // would be ignored anyway; close without sending one
                resultGate.silence();
                dismissAllowingStateLoss();
                return;
            }
            if (!sheetStateApplied) {
                sheetStateApplied = true;
                applyPickSheetState();
            }
            startPickRefresh();
            return;
        }
        if (!sheetStateApplied) {
            // Only once: coming back from a player activity keeps the user's drag state.
            sheetStateApplied = true;
            applyInitialSheetState();
        }
        startLiveUpdates();
    }

    @Override
    public void onStop() {
        // Home, or a player activity opened on top: no network polling while hidden.
        stopLiveUpdates();
        stopPickRefresh();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        stopLiveUpdates(); // backstop; normally already stopped in onStop
        stopPickRefresh();
        pickHint = null;
        if (adapter != null) {
            adapter.release(); // stops the binder's executor thread
            adapter = null;
        }
        View view = getView();
        if (view != null) {
            RecyclerView list = view.findViewById(R.id.deviceSheetList);
            list.setAdapter(null);
        }
        super.onDestroyView();
    }

    /**
     * Subscribes to the shared {@link RendererStatusMonitor} (which polls each
     * receiver right away), to player add/remove events, and starts the 2 s
     * local poll. Coming back after {@link #onStop()}, the list is rebuilt
     * once first, since players may have started or ended meanwhile.
     */
    private void startLiveUpdates() {
        if (liveUpdatesRunning || adapter == null || upnpClient == null) {
            return;
        }
        liveUpdatesRunning = true;
        if (!listFresh) {
            adapter.refresh();
        }
        listFresh = false;
        RendererStatusMonitor.getShared().subscribe(statusListener, upnpClient.getDevicesProvidingAvTransportService());
        upnpClient.addPlayerListChangeListener(playerListListener);
        handler.postDelayed(localPoller, LOCAL_POLL_INTERVAL_MS);
    }

    private void stopLiveUpdates() {
        if (!liveUpdatesRunning) {
            return;
        }
        liveUpdatesRunning = false;
        handler.removeCallbacks(localPoller);
        RendererStatusMonitor.getShared().unsubscribe(statusListener);
        if (upnpClient != null) {
            upnpClient.removePlayerListChangeListener(playerListListener);
        }
    }

    /**
     * Pick mode: the sheet is as high as its content (a few rows and the two buttons) and starts
     * expanded, so the buttons are always on screen; with many receivers the weighted list shrinks
     * and scrolls (fragment_device_sheet_pick.xml).
     */
    private void applyPickSheetState() {
        if (!(getDialog() instanceof BottomSheetDialog)) {
            return;
        }
        BottomSheetBehavior<FrameLayout> behavior = ((BottomSheetDialog) getDialog()).getBehavior();
        behavior.setFitToContents(true);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    /**
     * Half-expanded for touch, fully expanded for D-pad/TV: half-expanded rows
     * below the screen edge can take D-pad focus without scrolling into view
     * (docs/tech.md, "Unified device sheet" §5).
     */
    private void applyInitialSheetState() {
        if (!(getDialog() instanceof BottomSheetDialog)) {
            return;
        }
        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        // The container is wrap_content by default; full height makes
        // "expanded" fill the screen instead of pinning a short list to the top.
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            ViewGroup.LayoutParams params = sheet.getLayoutParams();
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            sheet.setLayoutParams(params);
        }
        BottomSheetBehavior<FrameLayout> behavior = dialog.getBehavior();
        // Must precede STATE_HALF_EXPANDED: with fitToContents true it turns into EXPANDED.
        behavior.setFitToContents(false);
        behavior.setHalfExpandedRatio(HALF_EXPANDED_RATIO);
        behavior.setSkipCollapsed(true); // dragging down from half dismisses
        behavior.setState(isTouchInput()
                ? BottomSheetBehavior.STATE_HALF_EXPANDED
                : BottomSheetBehavior.STATE_EXPANDED);
    }

    private boolean isTouchInput() {
        boolean television = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION;
        View activityDecor = requireActivity().getWindow().getDecorView();
        return !television && activityDecor.isInTouchMode();
    }
}
