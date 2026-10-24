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
import java.util.List;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpClientListener;
import de.yaacc.util.ThemeHelper;

/**
 * Binds the receiver bar (a {@code TextView}): its label (the selected receivers' names, built by
 * {@link ReceiverBarLabel}), the tap / D-pad select that opens the device sheet, and the refresh
 * whenever {@link UpnpClient} reports that the receiver selection changed, including changes made
 * in the device sheet. Used by {@link BrowserActivity}, which owns the bar in the new layout; the
 * classic layout has no bar (its {@link ContentListFragment} shows a static footer).
 * <p>
 * Between {@link #bind()} and {@link #unbind()} it is a {@link UpnpClientListener} of the
 * client; the client lives as long as the application, so every {@code bind()} must be paired
 * with an {@code unbind()} (view destroyed / activity stopped). Events that arrive after
 * {@code unbind()} are ignored. Main thread only, except the listener callbacks, which hop to the
 * main thread themselves.
 */
final class ReceiverBarController implements UpnpClientListener {

    private final TextView bar;
    private final UpnpClient upnpClient;
    private final Runnable openSheet;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateRunnable = this::update;
    private boolean bound;

    /**
     * @param bar       the receiver bar view
     * @param upnpClient the client that knows the selected receivers
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
        update();
    }

    /** Stops following receiver changes and drops what the bar's listeners hold. */
    void unbind() {
        if (!bound) {
            return;
        }
        bound = false;
        upnpClient.removeUpnpClientListener(this);
        mainHandler.removeCallbacks(updateRunnable);
        bar.setOnClickListener(null);
        ViewCompat.setAccessibilityDelegate(bar, null);
    }

    /** Shows the selected receivers' names; call on the main thread. */
    void update() {
        if (!bound) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (Device<?, ?, ?> receiver : upnpClient.getReceiverDevices()) {
            if (receiver.getDetails() != null) {
                names.add(receiver.getDetails().getFriendlyName());
            }
        }
        // Untrusted LAN names: plain text only.
        bar.setText(ReceiverBarLabel.build(names, bar.getContext().getString(R.string.receiver_bar_none_selected)));
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
