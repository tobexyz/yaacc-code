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

import java.util.List;

import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.protocol.SnapcastConnection;

/**
 * Which server the phone's Snapcast client uses (task 12.10, the owner's rule of 2026-11-16): the server it was <b>last connected</b>
 * to; if it was connected to none (or that one is not known any more), the <b>first</b> known server, in the order of
 * {@link SnapcastServerChoices} (last used, typed in, remembered, found); none when no server is known. No automatic failover: a
 * server that does not answer is retried by the client, and the user chooses another one. Pure.
 *
 * <p>The "known servers" are the choices that {@link SnapcastServerChoices#of} builds from the server list: the host only, the client
 * connects to the stream port 1704 (a server on another stream port has to be typed in the listen test). The remembered key is
 * validated here, on read: a text that is no server is the same as none.
 */
public final class SnapcastClientServerChoice {

    private SnapcastClientServerChoice() {
    }

    /**
     * @param lastConnectedKey the remembered text of the server the client was last connected to (may be null or damaged)
     * @param known            the known servers in their order (may be null)
     * @return the server to use; null when none is known
     */
    public static SnapcastServerChoices.Choice resolve(String lastConnectedKey, List<SnapcastServerChoices.Choice> known) {
        int index = currentIndex(lastConnectedKey, known);
        return index < 0 ? null : known.get(index);
    }

    /** @return the position of the server that {@link #resolve} returns, to mark it in the list; -1 when none is known */
    public static int currentIndex(String lastConnectedKey, List<SnapcastServerChoices.Choice> known) {
        if (known == null || known.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < known.size(); i++) {
            if (sameServer(lastConnectedKey, known.get(i).hostText())) {
                return i;
            }
        }
        return 0;
    }

    /**
     * @return true when both texts are valid server addresses with the same target (the default stream port when none is written, the
     * name or number as the endpoint parser reads it); false when either is not a server
     */
    public static boolean sameServer(String hostText, String otherHostText) {
        SnapcastEndpoint a = parse(hostText);
        SnapcastEndpoint b = parse(otherHostText);
        return a != null && b != null && a.toKey().asString().equals(b.toKey().asString());
    }

    private static SnapcastEndpoint parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TEXT) {
            return null;
        }
        return SnapcastEndpoint.parse(trimmed, SnapcastConnection.DEFAULT_PORT);
    }

    private static final int MAX_TEXT = 300; // the length of the address field of the listen test
}
