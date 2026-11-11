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
package de.yaacc.multiroom.feed;

import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastGroup;

import java.net.InetAddress;
import java.util.Map;

/**
 * What {@link SnapcastFeedController} needs to know about the server and its control connection: whether it is up,
 * its peer address, the group as the model has it, and the group's volume and mute. The session of the app implements
 * it; a test brings a fake.
 */
public interface FeedServer {

    /**
     * Waits until the control connection is up (and asks it to connect if it is not).
     *
     * @return true if it is up now; false after the time is over
     */
    boolean awaitControl(long timeoutMillis);

    /**
     * The address that the control connection is connected to, or null while there is none. Read it right before the
     * feed connects, after the last control call: it is the only address a feed may connect to. It is an address of
     * the local network: never log it, never put it into a message.
     */
    InetAddress peerAddress();

    /** The group as the model has it now (no network), or null if the server has no such group. */
    SnapcastGroup findGroup(String groupId);

    /** See {@code SnapcastOperations#setGroupVolume(SnapcastGroup, Map, int)}. Blocks. */
    void setGroupVolume(SnapcastGroup current, Map<String, Integer> startLevels, int percent) throws SnapcastException;

    /** {@code Group.SetMute}. Blocks. */
    void setGroupMuted(String groupId, boolean muted) throws SnapcastException;
}
