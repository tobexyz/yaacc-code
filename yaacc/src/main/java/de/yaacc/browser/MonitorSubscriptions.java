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

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Subscription bookkeeping behind {@link RendererStatusMonitor}: which
 * listeners are subscribed, which devices are being polled, and a generation
 * counter that invalidates polls once monitoring stops.
 * <p>
 * Polling starts for a device the first time any subscriber asks for it, and
 * stops for all devices when the last subscriber leaves; a poll chain or
 * result from an earlier generation is then dropped. Plain Java (no Android
 * types) so it is unit-tested on the JVM, the same pattern as
 * {@code PlayerListChangeNotifier}. Not thread-safe: the monitor uses it on the
 * main thread only.
 *
 * @param <L> listener type
 * @param <D> device type
 */
final class MonitorSubscriptions<L, D> {

    /** Starts one poll chain for a device; the chain must stop once {@code generation} is no longer current. */
    interface PollStarter<D> {
        void startPolling(D device, int generation);
    }

    // Copy-on-write: a listener may unsubscribe (itself or others) during delivery.
    private final CopyOnWriteArrayList<L> listeners = new CopyOnWriteArrayList<>();
    private final Set<D> polledDevices = new HashSet<>();
    private final PollStarter<D> pollStarter;
    private int generation;

    MonitorSubscriptions(PollStarter<D> pollStarter) {
        this.pollStarter = Objects.requireNonNull(pollStarter, "pollStarter");
    }

    /**
     * Registers the listener (once) and starts polling every given device that
     * isn't polled yet. A device already polled is never started twice.
     */
    void subscribe(L listener, Collection<? extends D> devices) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
        if (devices == null) {
            return;
        }
        for (D device : devices) {
            if (device != null && polledDevices.add(device)) {
                pollStarter.startPolling(device, generation);
            }
        }
    }

    /**
     * Removes the listener. When no listener is left, monitoring stops: the
     * generation moves on and no device counts as polled any more.
     *
     * @return true if this call stopped monitoring; false if other listeners
     * remain or the listener was unknown and nothing was being monitored
     */
    boolean unsubscribe(L listener) {
        boolean removed = listeners.remove(listener);
        if (!listeners.isEmpty() || (!removed && polledDevices.isEmpty())) {
            return false;
        }
        generation++;
        polledDevices.clear();
        return true;
    }

    /** Whether a poll or result tagged with this generation is still wanted. */
    boolean isCurrent(int pollGeneration) {
        return pollGeneration == generation;
    }

    /**
     * Calls {@code action} for every subscribed listener, unless the result
     * comes from a stopped generation. A listener that unsubscribes during
     * delivery is not called afterwards; the others still are.
     *
     * @return false if the result was stale and nobody was called
     */
    boolean deliver(int pollGeneration, Consumer<L> action) {
        if (!isCurrent(pollGeneration)) {
            return false;
        }
        for (L listener : listeners) {
            if (listeners.contains(listener)) {
                action.accept(listener);
            }
        }
        return true;
    }
}
