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

/** The sink could not do what was asked; the reason is typed, the text is ours (never the server's). */
public final class AudioSinkException extends Exception {

    /** What went wrong. */
    public enum Reason {
        /** The device does not support this sample rate or channel count. */
        FORMAT_REFUSED,
        /** The output could not be created. */
        OPEN_FAILED,
        /** Playback could not be started. */
        START_FAILED,
        /** Writing failed (the output died, was released, or the route changed in a way that kills the track). */
        WRITE_FAILED
    }

    private final Reason reason;

    public AudioSinkException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
