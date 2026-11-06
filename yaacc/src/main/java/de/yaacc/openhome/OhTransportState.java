/*
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
package de.yaacc.openhome;

/** The values of the state variable {@code TransportState} of the playlist service. */
public enum OhTransportState {
    PLAYING("Playing"), PAUSED("Paused"), STOPPED("Stopped"), BUFFERING("Buffering");

    private final String value;

    OhTransportState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** @return the state of the text, {@code null} if it is none of the four */
    public static OhTransportState fromValue(String text) {
        for (OhTransportState state : values()) {
            if (state.value.equals(text)) {
                return state;
            }
        }
        return null;
    }
}
