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

/** Where the engine is in its life. */
public enum EngineState {
    /** Created, nothing received yet. */
    WAITING_FOR_STREAM,
    /** A codec header was accepted; waiting for the clock to synchronise and for audio. */
    BUFFERING,
    /** The output runs; the first audio is being aligned to its time. */
    ALIGNING,
    /** Audio plays and the drift is controlled. */
    PLAYING,
    /** The connection to the server was lost; the output was silenced; a reconnect may follow. */
    CONNECTION_LOST,
    /** Ended with a failure, see {@link EngineFailure}. */
    FAILED,
    /** Stopped on request. */
    STOPPED
}
