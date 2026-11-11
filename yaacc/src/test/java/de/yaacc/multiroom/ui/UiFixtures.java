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
package de.yaacc.multiroom.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.control.SnapcastStream;
import de.yaacc.multiroom.control.SnapcastVolume;

/** Small builders for the models the presentation tests need. */
final class UiFixtures {

    private UiFixtures() {
    }

    static SnapcastClient client(String id, String name, boolean connected, int volume, boolean muted) {
        return new SnapcastClient(id, name, "", connected, new SnapcastVolume(volume, muted), 0);
    }

    static SnapcastClient client(String id, String name, String host, boolean connected, int volume, int latency) {
        return new SnapcastClient(id, name, host, connected, new SnapcastVolume(volume, false), latency);
    }

    static SnapcastGroup group(String id, String name, String streamId, SnapcastClient... clients) {
        return new SnapcastGroup(id, name, false, streamId, Arrays.asList(clients));
    }

    static SnapcastStream stream(String id, String status) {
        return new SnapcastStream(id, status, "", "tcp", "0.0.0.0", 4953, "", Collections.<String, String>emptyMap());
    }

    static SnapcastServerStatus status(List<SnapcastGroup> groups, SnapcastStream... streams) {
        return new SnapcastServerStatus(null, groups, new ArrayList<>(Arrays.asList(streams)));
    }

    static SnapcastServerStatus status(SnapcastGroup group, SnapcastStream... streams) {
        return status(Collections.singletonList(group), streams);
    }
}
