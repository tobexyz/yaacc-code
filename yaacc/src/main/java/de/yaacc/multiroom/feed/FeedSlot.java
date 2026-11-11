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
 * The right to feed one server: a snapserver's tcp source takes one producer at a time, and a phone has one stream
 * per server, so only one feed per server may be open. {@link FeedSlots} hands them out.
 */
public interface FeedSlot {

    /**
     * Takes the slot, waiting for the time given if somebody else (the feed of a player that is just ending) holds it.
     * Taking it again while this holder has it is fine.
     *
     * @return false if it stayed taken
     */
    boolean tryAcquire(long waitMillis);

    /** Gives the slot back; a no-op if this holder does not have it. */
    void release();
}
