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
package de.yaacc.multiroom.feed;

/**
 * Where decoded PCM goes: the {@link PcmFeeder}, or a fake in a test. The bytes are raw little-endian PCM in the
 * format of the feed (for a Snapcast stream 48000 Hz, 16 bit, stereo).
 */
public interface PcmSink {

    /**
     * Hands over PCM. May wait for a short, bounded time when the sink is full (back pressure); never grows without
     * limit. The data is copied before this returns.
     *
     * @return false if the sink is closed or failed, or if (a part of) the data had to be dropped because the sink
     * stayed full
     */
    boolean write(byte[] data, int offset, int length);

    /** The audio handed over so far is stale (a seek, a stop, a flush of the player): drop what is waiting. */
    void discard();
}
