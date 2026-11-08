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
package de.yaacc.upnp.server;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.yaacc.util.YaaccLogger;

/**
 * Keeps {@link NetworkRecoveryListener}s and passes a "network restored" on to them. Listeners are
 * held weakly, so a screen that forgets to unregister is not kept alive; a listener must be held
 * by its owner (a lambda that only the registry references is collected). No Android classes.
 * Itself a listener, so it can be chained behind another notifier.
 */
public class NetworkRecoveryNotifier implements NetworkRecoveryListener {

    private final List<WeakReference<NetworkRecoveryListener>> listeners = new CopyOnWriteArrayList<>();

    public void add(NetworkRecoveryListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (listeners) {
            prune();
            for (WeakReference<NetworkRecoveryListener> reference : listeners) {
                if (reference.get() == listener) {
                    return;
                }
            }
            listeners.add(new WeakReference<>(listener));
        }
    }

    public void remove(NetworkRecoveryListener listener) {
        synchronized (listeners) {
            for (WeakReference<NetworkRecoveryListener> reference : listeners) {
                NetworkRecoveryListener current = reference.get();
                if (current == null || current == listener) {
                    listeners.remove(reference);
                }
            }
        }
    }

    /** Number of live listeners. */
    public int size() {
        synchronized (listeners) {
            prune();
            return listeners.size();
        }
    }

    @Override
    public void onNetworkRestored() {
        for (WeakReference<NetworkRecoveryListener> reference : listeners) {
            NetworkRecoveryListener listener = reference.get();
            if (listener == null) {
                continue;
            }
            try {
                listener.onNetworkRestored();
            } catch (RuntimeException e) {
                // one broken listener must not stop the others or the network callback
                YaaccLogger.w(getClass().getName(), "NetworkRecoveryListener failed", e);
            }
        }
    }

    private void prune() {
        for (WeakReference<NetworkRecoveryListener> reference : listeners) {
            if (reference.get() == null) {
                listeners.remove(reference);
            }
        }
    }
}
