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
package de.yaacc.fcast;

import de.yaacc.fcast.protocol.FCastReceiverState.Playback;

/**
 * What the play, pause and stop buttons of an FCast card do when <b>no player of this app</b> plays to the receiver (task 6.2: another
 * sender, for example a second YAACC, started the playback). The buttons then act on what the receiver reports: pause pauses
 * what plays, play resumes what is paused, stop stops. When the state is not known yet (the connection was just made for this tap) the
 * command is sent anyway: a receiver ignores a resume, a pause or a stop when there is nothing to resume, pause or stop. Pure.
 */
public final class FCastCardTransport {

    /** The button. */
    public enum Action {
        PLAY, PAUSE, STOP
    }

    /** What to send. */
    public enum Command {
        NONE, RESUME, PAUSE, STOP
    }

    private FCastCardTransport() {
    }

    public static Command commandFor(Action action, Playback playback) {
        Playback state = playback == null ? Playback.UNKNOWN : playback;
        switch (action) {
            case PLAY:
                return state == Playback.PAUSED || state == Playback.UNKNOWN ? Command.RESUME : Command.NONE;
            case PAUSE:
                return state == Playback.PLAYING || state == Playback.UNKNOWN ? Command.PAUSE : Command.NONE;
            case STOP:
            default:
                return state == Playback.IDLE ? Command.NONE : Command.STOP;
        }
    }
}
