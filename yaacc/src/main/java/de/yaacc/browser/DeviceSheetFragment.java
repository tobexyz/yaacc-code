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

import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.player.Player;
import de.yaacc.player.PlayerListChangeNotifier;
import de.yaacc.upnp.UpnpClient;

/**
 * Bottom sheet listing every receiver with its device controls and, under
 * each, the yaacc players running on it (spec 2026-10-23-unified-device-sheet).
 * <p>
 * While started (visible) it is subscribed to the shared
 * {@link RendererStatusMonitor}, to player add/remove events and to a 2 s
 * local-device poll; all three are dropped in {@link #onStop()} (and in
 * {@link #onDestroyView()} as a backstop), and callbacks that still arrive
 * afterwards are ignored. An in-app dialog only:
 * nothing is exported and it is opened only through {@link #show(FragmentManager)}.
 * See {@code docs/tech.md}, "Unified device sheet", for the Material API details.
 */
public class DeviceSheetFragment extends BottomSheetDialogFragment {
    public static final String TAG = "DeviceSheetFragment";
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
     * Shows the sheet unless it is already showing or the state is saved
     * (a commit then would throw).
     */
    public static void show(FragmentManager fragmentManager) {
        if (fragmentManager.isStateSaved() || fragmentManager.findFragmentByTag(TAG) != null) {
            return;
        }
        new DeviceSheetFragment().show(fragmentManager, TAG);
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
        return inflater.inflate(R.layout.fragment_device_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        upnpClient = ((Yaacc) requireContext().getApplicationContext()).getUpnpClient();
        RecyclerView list = view.findViewById(R.id.deviceSheetList);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setItemAnimator(null); // no flashing on live updates, as in the Receiver tab
        adapter = new DeviceSheetAdapter(requireContext(), upnpClient);
        if (!isTouchInput()) {
            adapter.requestInitialFocus();
        }
        list.setAdapter(adapter);
        adapter.refresh();
        listFresh = true;
    }

    @Override
    public void onStart() {
        super.onStart(); // shows the dialog
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
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        stopLiveUpdates(); // backstop; normally already stopped in onStop
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
