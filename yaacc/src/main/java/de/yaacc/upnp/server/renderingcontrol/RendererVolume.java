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

/**
 * The volume and the mute state of what the renderer plays on: the system's music stream of this device
 * ({@link SystemVolume}) or the group of a Snapcast server ({@link OutputRoutedVolume} decides). The volume is 0 to 100.
 */
public interface RendererVolume {

    /** @return the volume, 0 to 100 */
    int getPercent();

    boolean isMuted();

    /** @param desired 0 to 100, other values are cut to it */
    void setPercent(int desired);

    void setMuted(boolean mute);

    /**
     * As {@link #getPercent()}, for the check that reports a change to the controllers (the RenderingControl event): it reads
     * and never wakes a connection. A read that a change event made must not cause the next change event.
     */
    default int peekPercent() {
        return getPercent();
    }

    /**
     * A control point subscribed to the renderer's events: if the volume lives on a server, make sure its connection is up (once,
     * not at every change). Nothing for the system volume.
     */
    default void wakeNow() {
    }

    /** As {@link #isMuted()}, without waking anything; see {@link #peekPercent()}. */
    default boolean peekMuted() {
        return isMuted();
    }
}
