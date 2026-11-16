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
package de.yaacc.multiroom;

import java.io.Closeable;
import java.net.InetAddress;

import de.yaacc.multiroom.control.SnapcastOperations;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * The live view of one snapserver that the receivers need: the key, the last status that was read (immutable), and
 * whether the control connection is up. {@link SnapcastSession} is the real one; the lists and the live source work
 * on this interface, so that they are tested with a fake.
 */
public interface SnapcastServerLink extends Closeable {

    /** Told when the status or the connection state changed; may be called on any thread, with no lock held. */
    interface Listener {
        void onLinkChanged(SnapcastServerLink link);
    }

    SnapcastServerKey getKey();

    /** The last status that was read; null before the first one. It is kept while the server is offline. */
    SnapcastServerStatus getStatus();

    /** True while the control connection is up. */
    boolean isOnline();

    void addListener(Listener listener);

    void removeListener(Listener listener);

    /**
     * The control calls of the server (they block: never on the main thread), or null for a link that has none (a fake
     * in a test). {@link SnapcastSession} has them for as long as it is open.
     */
    default SnapcastOperations getOperations() {
        return null;
    }

    /**
     * The address the control connection is connected to, or null without one. The feed of a stream connects to this
     * address and to no other. It is an address of the local network: never log it. A link that has none (a fake)
     * answers null.
     */
    default InetAddress getPeerAddress() {
        return null;
    }

    /** A number that changes with every new control connection of this link and never goes back; 0 for a fake. */
    default long getConnectionGeneration() {
        return 0;
    }

    /** Opens the connection (does nothing when started already). */
    void start();

    /** Connects now if the link waits for a retry or gave up. */
    void retry();

    /**
     * Releases the connection and every thread; the link can not be used again. Tells its listeners once, before it drops
     * them, that it is offline (the owner of a feed to this server must hear of it), and does nothing the second time.
     */
    @Override
    void close();
}
