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
package de.yaacc.share;

/**
 * Which message the user gets when a shared file could not be taken or opened. Plain Java, so the
 * mapping can be unit tested on the JVM. The messages are fixed texts: nothing the provider or the
 * file said ever reaches them.
 * <p>
 * "Busy" means the app turned the share away for lack of room (the provider already has a call
 * outstanding, too many providers are busy, or another copy is running), which the user can
 * understand and retry later; everything else stays the generic "could not play" message.
 */
public final class ShareFailureMessage {

    /** The message to show. */
    public enum Kind {
        /** "Could not play the shared file." */
        OPEN_FAILED,
        /** "Another shared file is still being processed. Try again in a moment." */
        BUSY
    }

    private ShareFailureMessage() {
    }

    /**
     * @param providerCallRefusedBusy the provider call was refused for lack of room
     *                                ({@link BoundedCall.Result#isRefusedBusy()})
     * @param copySlotBusy            the copy fallback was refused because another copy runs or
     *                                too many stuck copies are left
     */
    public static Kind of(boolean providerCallRefusedBusy, boolean copySlotBusy) {
        return providerCallRefusedBusy || copySlotBusy ? Kind.BUSY : Kind.OPEN_FAILED;
    }
}
