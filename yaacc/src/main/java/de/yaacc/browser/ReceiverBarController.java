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

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import de.yaacc.R;
import de.yaacc.player.Player;
import de.yaacc.player.PlayerListChangeNotifier;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.YaaccLogger;

/**
 * Binds the receiver bar (a {@code TextView}): its label (the names of the receivers that have a
 * running player, built by {@link ReceiverBarLabel}; the receiver selection is not shown, it only
 * exists while the picker is open), the tap / D-pad select that opens the device sheet, and the
 * refresh whenever a player is added or removed or {@link UpnpClient} reports a receiver change.
 * It reads the player list the mini-player and the sheet already follow; it has no poller of its
 * own. Used by {@link BrowserActivity}, which owns the bar in the new layout; the
 * classic layout has no bar (its {@link ContentListFragment} shows a static footer).
 * <p>
 * Between {@link #bind()} and {@link #unbind()} it is a {@link UpnpClientListener} of the
 * client; the client lives as long as the application, so every {@code bind()} must be paired
 * with an {@code unbind()} (view destroyed / activity stopped). Events that arrive after
 * {@code unbind()} are ignored. Main thread only, except the listener callbacks, which hop to the
 * main thread themselves.
 */
final class ReceiverBarController implements UpnpClientListener, PlayerListChangeNotifier.Listener {

    private static final String TAG = ReceiverBarController.class.getName();

    private final TextView bar;
    private final UpnpClient upnpClient;
    private final Runnable openSheet;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateRunnable = this::update;
    private boolean bound;

    /**
     * @param bar       the receiver bar view
     * @param upnpClient the client whose player list the bar follows
     * @param openSheet what a tap on the bar does (opens the device sheet)
     */
    ReceiverBarController(@NonNull TextView bar, @NonNull UpnpClient upnpClient, @NonNull Runnable openSheet) {
        this.bar = bar;
        this.upnpClient = upnpClient;
        this.openSheet = openSheet;
    }

    /**
     * The bar opens the device sheet on tap or D-pad select (it is clickable and focusable in the
     * layouts). TalkBack announces the click action as "Choose receivers". Shows the current
     * label and starts following receiver changes.
     */
    void bind() {
        if (bound) {
            return;
        }
        bound = true;
        tintCompoundDrawables();
        bar.setOnClickListener(v -> openSheet.run());
        ViewCompat.setAccessibilityDelegate(bar, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_CLICK, host.getContext().getString(R.string.receiver_bar_description)));
            }
        });
        upnpClient.addUpnpClientListener(this);
        upnpClient.addPlayerListChangeListener(this);
        update();
    }

    /** Stops following receiver changes and drops what the bar's listeners hold. */
    void unbind() {
        if (!bound) {
            return;
        }
        bound = false;
        upnpClient.removeUpnpClientListener(this);
        upnpClient.removePlayerListChangeListener(this);
        mainHandler.removeCallbacks(updateRunnable);
        bar.setOnClickListener(null);
        ViewCompat.setAccessibilityDelegate(bar, null);
    }

    /** Shows the names of the receivers with a running player; call on the main thread. */
    void update() {
        if (!bound) {
            return;
        }
        List<String> names;
        try {
            // the player service hands out its live collection: copy before iterating
            names = receiverNames(new ArrayList<>(upnpClient.getCurrentPlayers()), this::resolveReceiverName);
        } catch (RuntimeException e) {
            // a player changing under the read; the next player or receiver event refreshes.
            // Never log names.
            YaaccLogger.w(TAG, "Receiver bar refresh skipped (" + e.getClass().getSimpleName() + ")");
            return;
        }
        // Untrusted LAN names: cleaned by the label, plain text only.
        bar.setText(ReceiverBarLabel.build(names, bar.getContext().getString(R.string.receiver_bar_none_playing)));
    }

    /**
     * One entry per player: the friendly name of the device it plays on, else the player's own name
     * (cleaned, see {@link MiniPlayerPresenter#receiverLabel}). Duplicates are NOT merged here
     * ({@link ReceiverBarLabel#build} does that), a null player is skipped.
     *
     * @param resolveName the friendly name of a device id, or null if it is not known
     */
    static List<String> receiverNames(Collection<? extends Player> players, Function<String, String> resolveName) {
        List<String> names = new ArrayList<>();
        for (Player player : players) {
            if (player != null) {
                names.add(MiniPlayerPresenter.receiverLabel(resolveName.apply(player.getDeviceId()), player.getName()));
            }
        }
        return names;
    }

    /** The receiver's friendly name as the device sheet resolves it; null while it is not known. */
    private String resolveReceiverName(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        try {
            Device<?, ?, ?> device = upnpClient.getDevice(deviceId);
            if (device != null && device.getDetails() != null) {
                return device.getDetails().getFriendlyName();
            }
        } catch (RuntimeException e) {
            // an id that is not a valid UDN; the player's own name is shown instead
            YaaccLogger.d(TAG, "Receiver not resolved (" + e.getClass().getSimpleName() + ")");
        }
        return null;
    }

    private void tintCompoundDrawables() {
        Drawable[] drawables = bar.getCompoundDrawablesRelative();
        for (int i = 0; i < drawables.length; i++) {
            if (drawables[i] != null) {
                drawables[i] = ThemeHelper.tintDrawable(drawables[i], bar.getContext().getTheme());
            }
        }
        bar.setCompoundDrawablesRelativeWithIntrinsicBounds(drawables[0], drawables[1], drawables[2], drawables[3]);
    }

    private void updateOnMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update();
        } else {
            mainHandler.post(updateRunnable);
        }
    }

    @Override
    public void onPlayerAdded(Player player) {
        update();
    }

    @Override
    public void onPlayerRemoved(Player player) {
        update();
    }

    @Override
    public void receiverDeviceAdded(Device<?, ?, ?> device) {
        updateOnMainThread();
    }

    @Override
    public void receiverDeviceRemoved(Device<?, ?, ?> device) {
        updateOnMainThread();
    }

    @Override
    public void deviceAdded(Device<?, ?, ?> device) {
    }

    @Override
    public void deviceRemoved(Device<?, ?, ?> device) {
    }

    @Override
    public void deviceUpdated(Device<?, ?, ?> device) {
    }
}
