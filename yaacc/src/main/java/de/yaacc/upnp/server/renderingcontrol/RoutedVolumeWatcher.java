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
package de.yaacc.upnp.server.renderingcontrol;

import de.yaacc.util.YaaccLogger;

/**
 * The {@link VolumeWatcher} of the RenderingControl service while the renderer can play into a Snapcast group (task 9.1):
 * the system's volume broadcast ({@link SystemVolumeBroadcastWatcher}) as before, and the changes of the groups and of the
 * output setting, all calling the same "the volume or mute may have changed" callback. That callback only <i>reads</i> the
 * routed volume and puts a difference into the LastChange: a volume change that the server reports can never cause a set.
 */
final class RoutedVolumeWatcher implements VolumeWatcher {

    /** The changes of the groups and of the setting. */
    interface ChangeSource {
        /** Calls the callback (any thread) on every change. @return what lets go of the subscription */
        Runnable subscribe(Runnable onChange);
    }

    private final VolumeWatcher system;
    private final ChangeSource others;
    private final Object lock = new Object();
    private Runnable unsubscribe;

    RoutedVolumeWatcher(VolumeWatcher system, ChangeSource others) {
        this.system = system;
        this.others = others;
    }

    @Override
    public void start(Runnable onChange) {
        try {
            system.start(onChange);
        } catch (RuntimeException e) {
            // the changes of the groups and the in-process changes are still seen
            YaaccLogger.w(getClass().getName(), "System volume broadcast not watched", e);
        }
        Runnable previous;
        Runnable next = others.subscribe(onChange);
        synchronized (lock) {
            previous = unsubscribe;
            unsubscribe = next;
        }
        if (previous != null) {
            previous.run();
        }
    }

    @Override
    public void stop() {
        Runnable current;
        synchronized (lock) {
            current = unsubscribe;
            unsubscribe = null;
        }
        if (current != null) {
            current.run();
        }
        system.stop();
    }
}
