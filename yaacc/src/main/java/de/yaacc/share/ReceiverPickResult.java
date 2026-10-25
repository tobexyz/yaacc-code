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

import java.util.HashSet;
import java.util.Set;

/**
 * Decides what the receiver picker does with the user's answer (new layout: play, play-all and
 * share open the device sheet in a pick mode). Plain Java so it can be unit tested on the JVM.
 * <p>
 * Rules:
 * <ul>
 * <li>confirm with at least one receiver: start the playback on exactly those receivers, then
 * clear the receiver selection (the selection is per action, not sticky);</li>
 * <li>confirm with no receiver: do not start and do not clear; the picker stays open so the user
 * can choose (see {@link Decision#keepPickerOpen()});</li>
 * <li>cancel: nothing starts, nothing is cleared, the receivers stay as they were. The decision
 * never carries the ids the user had ticked, so a caller cannot apply them by accident.</li>
 * </ul>
 * A null set counts as empty; null or empty ids inside the set are ignored. The returned set is
 * an unmodifiable copy.
 */
public final class ReceiverPickResult {

    private ReceiverPickResult() {
    }

    /** What the caller has to do after the pick. Immutable. */
    public static final class Decision {
        private final boolean startPlayback;
        private final boolean clearSelectionAfterStart;
        private final boolean keepPickerOpen;
        private final Set<String> receiversToUse;

        Decision(boolean startPlayback, boolean clearSelectionAfterStart, boolean keepPickerOpen,
                 Set<String> receiversToUse) {
            this.startPlayback = startPlayback;
            this.clearSelectionAfterStart = clearSelectionAfterStart;
            this.keepPickerOpen = keepPickerOpen;
            this.receiversToUse = receiversToUse;
        }

        /** True if the playback has to be started now. */
        public boolean startPlayback() {
            return startPlayback;
        }

        /** True if the receiver selection has to be cleared once the playback has started. */
        public boolean clearSelectionAfterStart() {
            return clearSelectionAfterStart;
        }

        /** True if the picker must stay open (confirmed without a receiver). */
        public boolean keepPickerOpen() {
            return keepPickerOpen;
        }

        /**
         * The receivers to play on: the chosen ids when {@link #startPlayback()} is true,
         * otherwise empty. Unmodifiable.
         */
        public Set<String> receiversToUse() {
            return receiversToUse;
        }
    }

    /**
     * @param confirmed         true if the user confirmed the pick, false if they cancelled it
     * @param chosenReceiverIds the receivers ticked in the picker, may be null
     * @return what to do next, never null
     */
    public static Decision decide(boolean confirmed, Set<String> chosenReceiverIds) {
        // STUB (red phase): wrong on purpose
        return new Decision(false, false, false, new HashSet<>());
    }
}
