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

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import de.yaacc.R;
import de.yaacc.share.ActionFlight;
import de.yaacc.share.AfterStartSelection;
import de.yaacc.share.GuardedPickedAction;
import de.yaacc.share.PendingPick;
import de.yaacc.share.PickedAction;
import de.yaacc.share.ShareLimits;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * The thin Android side of the receiver picker, shared by both browser activities: it owns the
 * {@link PendingPick} (the pending action lives in the activity and is lost with it, process death
 * included), shows {@link DeviceSheetFragment} in pick mode, receives its one answer through the
 * fragment-result API and hands it to the pick. All decisions are in {@link PendingPick},
 * {@link de.yaacc.share.PickSheetResult} and {@link AfterStartSelection}.
 * <p>
 * Main thread only. The activity creates it in {@code onCreate} (the result listener must be
 * registered again for every new activity) and calls {@link #release()} from {@code onDestroy}: an
 * open pick is cancelled then, which releases a pending share (a rotation with the picker open
 * therefore closes it; the restored sheet sees that its pick is gone and closes without an answer).
 */
final class ReceiverPickController {

    private static final String TAG = ReceiverPickController.class.getName();
    static final String REQUEST_KEY = "de.yaacc.receiverPick";

    /**
     * The one picked action that may be between its confirm and its end, process-wide: the receiver
     * selection it stores is global, so a second action must wait (see {@link ActionFlight}).
     */
    private static final ActionFlight FLIGHT = ActionFlight.createDefault();

    private final FragmentActivity activity;
    private final PendingPick pick = PendingPick.createDefault();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable expiry;

    ReceiverPickController(FragmentActivity activity) {
        this.activity = activity;
        activity.getSupportFragmentManager().setFragmentResultListener(REQUEST_KEY, activity,
                (requestKey, result) -> onResult(result));
    }

    /**
     * {@link BrowserHost#pickReceivers}: opens the picker, unless one is open or the screen cannot
     * show it now, or another picked action is still starting (see {@link ActionFlight}). For a
     * pending share (a name is given) the picker closes itself after the share's own timeout, which
     * cancels the pick.
     *
     * @return false if nothing was opened; the action is then never called
     */
    boolean start(PickedAction action, String shareName, Collection<String> initialIds) {
        FragmentManager fragmentManager = activity.getSupportFragmentManager();
        if (action == null || FLIGHT.isActive() || activity.isFinishing() || activity.isDestroyed()
                || fragmentManager.isStateSaved() || DeviceSheetFragment.isPickerShowing(fragmentManager)) {
            return false;
        }
        String pickId = pick.open(new GuardedPickedAction(FLIGHT, action));
        if (pickId == null) {
            return false;
        }
        boolean shown = false;
        try {
            // a normal sheet that is open must not stay behind the picker
            DeviceSheetFragment.dismissNormal(fragmentManager);
            shown = DeviceSheetFragment.showPicker(fragmentManager, REQUEST_KEY, pickId, shareName != null,
                    shareName, initialIds);
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "The receiver picker could not be shown (" + e.getClass().getSimpleName() + ")");
        }
        if (!shown) {
            // the callbacks own the outcome now: a cancel releases what the action had reserved
            pick.abandon();
            return true;
        }
        if (shareName != null) {
            scheduleExpiry(pickId);
        }
        return true;
    }

    /** Whether the pick with this id is the open one. */
    boolean isOpen(String pickId) {
        return pick.isOpen(pickId);
    }

    /**
     * The picker for a pending share: a confirm hands the chosen receivers to
     * {@link ShareIntentHandler#confirm} with the hook that clears (new layout) or restores
     * (classic) the receiver selection once the playback has been attempted; every other way out
     * releases the share with {@link ShareIntentHandler#cancel}. If the picker cannot open (one is
     * already open, the screen is not ready) the new share is dropped, never replacing the open pick.
     *
     * @param initialIds receivers that start ticked (classic: its selection), may be null
     */
    void offerShare(ShareIntentHandler shareHandler, String token, String displayName, UpnpClient upnpClient,
                    boolean newLayout, Collection<String> initialIds) {
        PickedAction action = new PickedAction() {
            @Override
            public void onConfirmed(Set<String> receiverIds, Runnable done) {
                // done ends the single flight, whatever becomes of the share
                shareHandler.confirm(token, receiverIds, afterStartHook(upnpClient, newLayout), done);
            }

            @Override
            public void onCancelled() {
                shareHandler.cancel(token);
            }
        };
        if (!start(action, displayName == null ? "" : displayName, initialIds)) {
            shareHandler.cancel(token);
            YaaccLogger.w(TAG, "Dropped a shared item: the receiver picker is busy or not available");
            toast(R.string.receiver_pick_busy);
        }
    }

    /**
     * What runs once a picked action has been started: clears the receiver selection (new layout)
     * or puts the selection back that was there when the pick was confirmed (classic), as
     * {@link AfterStartSelection} decides. The previous selection is read now, at confirm time.
     */
    static Runnable afterStartHook(UpnpClient upnpClient, boolean newLayout) {
        AfterStartSelection.Plan plan = AfterStartSelection.plan(AfterStartSelection.modeFor(newLayout),
                upnpClient.getReceiverDeviceIds());
        return () -> {
            if (plan.clear()) {
                upnpClient.clearReceiverDevices();
            } else {
                upnpClient.setReceiverDeviceIds(new HashSet<>(plan.restoreTo()));
            }
        };
    }

    /** The activity goes away: an open pick is cancelled (releases a pending share). */
    void release() {
        handler.removeCallbacksAndMessages(null);
        expiry = null;
        try {
            pick.abandon();
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Cancelling the receiver pick failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void onResult(Bundle result) {
        if (result == null) {
            return;
        }
        String pickId = result.getString(DeviceSheetFragment.RESULT_PICK_ID);
        boolean confirmed = result.getBoolean(DeviceSheetFragment.RESULT_CONFIRMED, false);
        ArrayList<String> ids = result.getStringArrayList(DeviceSheetFragment.RESULT_RECEIVER_IDS);
        try {
            PendingPick.Outcome outcome = pick.resolve(pickId, confirmed, ids == null ? null : new LinkedHashSet<>(ids));
            if (outcome == PendingPick.Outcome.STARTED || outcome == PendingPick.Outcome.CANCELLED) {
                cancelExpiry();
            }
        } catch (RuntimeException e) {
            // an action that failed must not take the activity down; the pick is closed already
            YaaccLogger.w(TAG, "The action after the receiver pick failed (" + e.getClass().getSimpleName() + ")");
            cancelExpiry();
        }
    }

    private void toast(int resId) {
        try {
            Toast.makeText(activity.getApplicationContext(), resId, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not show a message (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void scheduleExpiry(String pickId) {
        cancelExpiry();
        expiry = () -> {
            expiry = null;
            if (pick.isOpen(pickId)) {
                pick.abandon();
                DeviceSheetFragment.dismissPicker(activity.getSupportFragmentManager());
            }
        };
        handler.postDelayed(expiry, ShareLimits.PENDING_SHARE_TIMEOUT_MILLIS);
    }

    private void cancelExpiry() {
        if (expiry != null) {
            handler.removeCallbacks(expiry);
            expiry = null;
        }
    }
}
