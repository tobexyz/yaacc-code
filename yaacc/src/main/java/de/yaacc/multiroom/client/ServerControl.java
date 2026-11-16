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
import de.yaacc.multiroom.control.SnapcastTransport;
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

    private static final int CALL_TIMEOUT_MS = 4_000;

    private ServerControl() {
    }

    private static SnapcastRpc open(SnapcastTransport transport, int callTimeoutMs) {
        SnapcastRpc.Config config = new SnapcastRpc.Config().callTimeoutMs(callTimeoutMs).connectTimeoutMs(3_000).maxFailures(1);
        SnapcastRpc rpc = new SnapcastRpc(transport, config, null, null, MonotonicClock.SYSTEM);
        rpc.start();
        return rpc;
    }

    /** {@code Server.GetStatus}, read for this client id; {@link ServerSays#unreachable} when the call fails. */
    public static ServerSays fetch(InetAddress address, String clientId) {
        SnapcastRpc rpc = open(new SocketTransport(address, CONTROL_PORT), CALL_TIMEOUT_MS);
        try {
            SnapcastServerStatus status = new SnapcastOperations(rpc).getStatus();
            return ServerSays.of(status, address, clientId);
        } catch (SnapcastException | RuntimeException failed) {
            return ServerSays.unreachable(address, clientId);
        } finally {
            rpc.close();
        }
    }

    /** What became of a request to move the group of this client. */
    public enum MoveResult {
        /** The server accepted it. */
        MOVED,
        /** The group of this client is not the one that was shown (it was dissolved or formed anew): nothing was changed. */
        GROUP_CHANGED,
        /** The server did not answer or refused. */
        FAILED
    }

    /**
     * {@code Group.SetStream}: the group of this client plays this stream. Changes the server: only on the owner's tap, with
     * a confirmation. The status is read again first and the move is made only when this client is still in the group that
     * was shown (the answer on the screen can be 30 s old, and a group that was dissolved or formed anew meanwhile would
     * move whoever is in it now). One connection, two calls.
     *
     * @param shownGroupId the group id of the {@link ServerSays} on the screen (raw)
     * @param streamId     the raw id of the chosen stream
     */
    public static MoveResult moveGroup(InetAddress address, String clientId, String shownGroupId, String streamId) {
        if (address == null) {
            return MoveResult.FAILED;
        }
        return moveGroup(new SocketTransport(address, CONTROL_PORT), clientId, shownGroupId, streamId, CALL_TIMEOUT_MS);
    }

    /** The same over any transport (the tests use one that talks to a script). */
    static MoveResult moveGroup(SnapcastTransport transport, String clientId, String shownGroupId, String streamId,
                                int callTimeoutMs) {
        if (transport == null || clientId == null || shownGroupId == null || shownGroupId.isEmpty() || streamId == null
                || streamId.isEmpty()) {
            return MoveResult.FAILED;
        }
        SnapcastRpc rpc = open(transport, callTimeoutMs);
        try {
            SnapcastOperations operations = new SnapcastOperations(rpc);
            switch (MoveDecision.decide(operations.getStatus(), clientId, shownGroupId, streamId)) {
                case MOVE:
                    operations.setGroupStream(shownGroupId, streamId);
                    return MoveResult.MOVED;
                case GROUP_CHANGED:
                    return MoveResult.GROUP_CHANGED;
                default:
                    return MoveResult.FAILED; // the stream is gone: the server would refuse; nothing is sent
            }
        } catch (SnapcastException | RuntimeException failed) {
            return MoveResult.FAILED;
        } finally {
            rpc.close();
        }
    }
}
