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
package de.yaacc.multiroom.client;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastOperations;
import de.yaacc.multiroom.control.SnapcastRpc;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.control.SocketTransport;

import java.net.InetAddress;

/**
 * The few calls on the control port of the server that the client spike makes: read the status (to show which group and
 * stream the phone is in) and, on the owner's request, move the phone's group to another stream. Each call opens its own
 * short connection to the address the session resolved (nothing is looked up), runs one call with the usual limits of the
 * control library, and closes. Blocking: never call it on the main thread.
 */
public final class ServerControl {

    /** The control port of Snapcast. */
    public static final int CONTROL_PORT = SocketTransport.DEFAULT_PORT;

    private ServerControl() {
    }

    private static SnapcastRpc open(InetAddress address) {
        SnapcastRpc.Config config = new SnapcastRpc.Config().callTimeoutMs(4_000).connectTimeoutMs(3_000).maxFailures(1);
        SnapcastRpc rpc = new SnapcastRpc(new SocketTransport(address, CONTROL_PORT), config, null, null, MonotonicClock.SYSTEM);
        rpc.start();
        return rpc;
    }

    /** {@code Server.GetStatus}, read for this client id; {@link ServerSays#unreachable} when the call fails. */
    public static ServerSays fetch(InetAddress address, String clientId) {
        SnapcastRpc rpc = open(address);
        try {
            SnapcastServerStatus status = new SnapcastOperations(rpc).getStatus();
            return ServerSays.of(status, address, clientId);
        } catch (SnapcastException | RuntimeException failed) {
            return ServerSays.unreachable(address, clientId);
        } finally {
            rpc.close();
        }
    }

    /**
     * {@code Group.SetStream}: the group plays this stream. Changes the server: only on the owner's tap, with a
     * confirmation.
     *
     * @return true when the server accepted it
     */
    public static boolean moveGroup(InetAddress address, String groupId, String streamId) {
        if (address == null || groupId == null || groupId.isEmpty() || streamId == null || streamId.isEmpty()) {
            return false;
        }
        SnapcastRpc rpc = open(address);
        try {
            new SnapcastOperations(rpc).setGroupStream(groupId, streamId);
            return true;
        } catch (SnapcastException | RuntimeException failed) {
            return false;
        } finally {
            rpc.close();
        }
    }
}
