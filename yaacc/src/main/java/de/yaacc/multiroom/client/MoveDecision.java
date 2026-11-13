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

import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;

/**
 * Decides, from a status that was read just now, whether the request to move the group of this client to a stream may be
 * sent to the server. The request changes the server for everybody in the group, so it is sent only when the picture the
 * owner confirmed is still true: this client is in the group that was shown, and the stream is still there. Pure.
 */
final class MoveDecision {

    enum Verdict {
        /** Send Group.SetStream. */
        MOVE,
        /** This client is in no group now, or in another one than was shown: nothing is changed. */
        GROUP_CHANGED,
        /** The chosen stream does not exist any more: nothing is changed. */
        STREAM_GONE
    }

    private MoveDecision() {
    }

    static Verdict decide(SnapcastServerStatus status, String clientId, String shownGroupId, String streamId) {
        if (status == null || clientId == null || shownGroupId == null || streamId == null) {
            return Verdict.GROUP_CHANGED;
        }
        SnapcastGroup now = status.findGroupOfClient(clientId);
        if (now == null || !shownGroupId.equals(now.getId())) {
            return Verdict.GROUP_CHANGED;
        }
        if (status.findStream(streamId) == null) {
            return Verdict.STREAM_GONE;
        }
        return Verdict.MOVE;
    }
}
