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

/** What made the engine flush the output; counted separately so a listening test can say which one it was. */
public enum FlushReason {
    /** Playback was later than the hard bound (the output holds old audio). */
    HARD_RESYNC,
    /** The platform counted an underrun while the stream was aligned. */
    UNDERRUN,
    /** The engine found it had written too late (the output ran dry without saying so). */
    WRITER_BEHIND,
    /** A new codec header: another stream. */
    HEADER,
    /** The connection was lost. */
    CONNECTION_LOST,
    /** The position of the output stood still although data was queued (the output froze). */
    POSITION_STALL
}
