/*
 *
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

import android.os.Handler;
import android.os.Looper;

import org.fourthline.cling.model.meta.Device;

import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.openhome.OhControlException;
import de.yaacc.openhome.OhRemote;
import de.yaacc.upnp.callback.openhome.ClingOhControl;
import de.yaacc.upnp.server.http.HttpRequestSender;

/**
 * Keeps the {@code Standby} state of the OpenHome devices on screen current (task 12.4), so that the power button of a card shows
 * it and follows a change made elsewhere (the device's own button, another controller, the settings sheet). A remote OpenHome device
 * is not subscribed to (the app reads the Product service's state, like the status monitor reads the transport state): every
 * {@value #POLL_INTERVAL_MS} ms for each device whose description offers {@code Standby} and {@code SetStandby}
 * ({@link OpenHomePower#offeredBy}), while a screen is subscribed, at once when it subscribes. A listener is told, on the main thread,
 * only when the value changed. The bookkeeping is {@link MonitorSubscriptions} (one generation per subscription period, a poll of an
 * earlier one is dropped) and {@link OpenHomePowerBook}. A device that does not answer has an unknown state.
 * <p>
 * The same pattern as {@link RendererStatusMonitor}; all public methods on the main thread.
 */
final class OpenHomePowerMonitor {

    static final int POLL_INTERVAL_MS = 10_000;

    /** Told on the main thread that the standby state of a device changed (UDN as the rows use it). */
    interface Listener {
        void onPowerChanged(String udn);
    }

    private static OpenHomePowerMonitor shared;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "openhome-power");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpRequestSender sender = new HttpRequestSender();
    private final OpenHomePowerBook book = new OpenHomePowerBook();
    private final MonitorSubscriptions<Listener, Device<?, ?, ?>> subscriptions = new MonitorSubscriptions<>(this::poll);

    static OpenHomePowerMonitor getShared() {
        if (shared == null) {
            shared = new OpenHomePowerMonitor();
        }
        return shared;
    }

    private OpenHomePowerMonitor() {
    }

    /** Starts reading every device of the list that offers power and is not read yet; the same listener again adds devices. */
    void subscribe(Listener listener, Collection<? extends Device<?, ?, ?>> devices) {
        java.util.List<Device<?, ?, ?>> offering = new java.util.ArrayList<>();
        if (devices != null) {
            for (Device<?, ?, ?> device : devices) {
                if (OpenHomePower.offeredBy(device)) {
                    offering.add(device);
                }
            }
        }
        subscriptions.subscribe(listener, offering);
    }

    void unsubscribe(Listener listener) {
        if (subscriptions.unsubscribe(listener)) {
            handler.removeCallbacksAndMessages(null);
        }
    }

    /** @return the last value read: true is standby, false is on, null when it is not known */
    Boolean standbyOf(String udn) {
        return book.standbyOf(udn);
    }

    /** What a power change found out (the toggle read the device after the change): the rows are told if it is news. */
    void applyOutcome(String udn, RendererPower.State state) {
        Boolean value = state == RendererPower.State.STANDBY ? Boolean.TRUE : state == RendererPower.State.ON ? Boolean.FALSE : null;
        if (book.update(udn, value)) {
            subscriptions.deliver(subscriptions.generation(), listener -> listener.onPowerChanged(udn));
        }
    }

    private void poll(final Device<?, ?, ?> device, final int generation) {
        if (!subscriptions.isCurrent(generation)) {
            return;
        }
        final String udn = device.getIdentity().getUdn().getIdentifierString();
        executor.execute(() -> {
            Boolean value;
            try {
                value = new OhRemote(new ClingOhControl(device, sender)).standby();
            } catch (OhControlException | RuntimeException unreadable) {
                value = null; // not logged: the message holds the device's address
            }
            final boolean changed = book.update(udn, value);
            handler.post(() -> {
                if (!subscriptions.isCurrent(generation)) {
                    return;
                }
                if (changed) {
                    subscriptions.deliver(generation, listener -> listener.onPowerChanged(udn));
                }
                handler.postDelayed(() -> poll(device, generation), POLL_INTERVAL_MS);
            });
        });
    }
}
