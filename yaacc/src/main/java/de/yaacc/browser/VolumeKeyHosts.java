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

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.multiroom.ui.SnapcastVolumeKeys;
import de.yaacc.util.YaaccLogger;

/**
 * The hosts of receiver rows that are on screen (the Receiver tab while it is resumed, the device sheet while it is shown) and
 * the hardware volume keys (11.4). The activity and the sheet hand the keys here; the host that started last is asked first (the
 * sheet is above the tab), and a host that does not take the key leaves it to the next one and, at the end, to the system, which
 * changes the volume of the phone as before. The up of a key whose down was taken is taken too, so the system does not play its
 * sound or show its panel for it. Main thread only (key events and the lifecycle of the hosts).
 */
final class VolumeKeyHosts {

    /** A host of receiver rows. */
    interface Host {
        /** @return true when the key is taken: a Snapcast group row of this host is the target */
        boolean onVolumeKey(SnapcastVolumeKeys.Direction direction);
    }

    private static final List<Host> HOSTS = new ArrayList<>();
    private static final Set<Integer> TAKEN_DOWN = new HashSet<>();

    private VolumeKeyHosts() {
    }

    /** The host's rows are on screen from now on; registering twice counts once, the latest registration is asked first. */
    static void register(Host host) {
        synchronized (HOSTS) {
            HOSTS.remove(host);
            HOSTS.add(host);
        }
    }

    static void unregister(Host host) {
        synchronized (HOSTS) {
            HOSTS.remove(host);
        }
    }

    private static SnapcastVolumeKeys.Direction directionOf(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return SnapcastVolumeKeys.Direction.UP;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return SnapcastVolumeKeys.Direction.DOWN;
        }
        return null;
    }

    /** @return true when a Snapcast group took the key; false leaves it to the system */
    static boolean onKeyDown(int keyCode) {
        SnapcastVolumeKeys.Direction direction = directionOf(keyCode);
        if (direction == null) {
            return false;
        }
        List<Host> asked;
        synchronized (HOSTS) {
            asked = new ArrayList<>(HOSTS);
        }
        for (int i = asked.size() - 1; i >= 0; i--) {
            try {
                if (asked.get(i).onVolumeKey(direction)) {
                    synchronized (TAKEN_DOWN) {
                        TAKEN_DOWN.add(keyCode);
                    }
                    return true;
                }
            } catch (RuntimeException failed) {
                // a host whose views are gone must not take the key away from the system
                YaaccLogger.w(VolumeKeyHosts.class.getName(), "Volume key host failed (" + failed.getClass().getSimpleName() + ")");
            }
        }
        return false;
    }

    /** @return true for the up of a key whose down was taken */
    static boolean onKeyUp(int keyCode) {
        synchronized (TAKEN_DOWN) {
            return TAKEN_DOWN.remove(keyCode);
        }
    }
}
