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

/** Why the engine gave up. Typed; the user text is chosen from the type, never taken from the server. */
public enum EngineFailure {
    /** The stream has more than two channels (this client plays mono and stereo). */
    UNSUPPORTED_CHANNELS,
    /** The device cannot create an output for the sample rate. */
    UNSUPPORTED_SINK_FORMAT,
    /** The output could not be created or started. */
    SINK_FAILED,
    /** The device has no decoder for the codec. */
    DECODER_UNAVAILABLE,
    /** The decoder refused the header of the stream. */
    DECODER_BAD_HEADER,
    /** Chunks kept failing to decode. */
    DECODER_FAILED,
    /** The server sent too many different codec headers in a minute. */
    HEADER_FLOOD,
    /** The output never reported where it is, so the sync can not be controlled. */
    NO_PLAYBACK_POSITION,
    /** The audio thread did not move for too long. */
    STALLED,
    /** A bug on our side. */
    INTERNAL
}
