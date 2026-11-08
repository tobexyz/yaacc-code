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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What happens to the receiver selection once a picked action has started. Plain Java so the
 * decision is unit tested on the JVM, and so "clear or restore" is decided in ONE place.
 * <ul>
 * <li>New layout: the selection only exists while the picker is open, so it is cleared once the
 * action has started ({@link Mode#CLEAR}).</li>
 * <li>Classic: the layout keeps its tab-based selection, and only a shared file opens the picker
 * (initially ticked with the classic selection). Owner decision 2026-10-25: afterwards the previous
 * selection is restored ({@link Mode#RESTORE_PREVIOUS}). To clear there too, change
 * {@link #CLASSIC_MODE} (and the one test that pins it); nothing else.</li>
 * </ul>
 */
public final class AfterStartSelection {

    /** What to do with the stored receiver selection after the action started. */
    public enum Mode {
        CLEAR, RESTORE_PREVIOUS
    }

    /** The classic layout's choice: the single place to switch between restoring and clearing. */
    public static final Mode CLASSIC_MODE = Mode.RESTORE_PREVIOUS;

    private AfterStartSelection() {
    }

    /** @param newLayout true for the new layout (it owns the receiver bar) */
    public static Mode modeFor(boolean newLayout) {
        return newLayout ? Mode.CLEAR : CLASSIC_MODE;
    }

    /** What the caller applies to the stored selection. Immutable. */
    public static final class Plan {
        private final boolean clear;
        private final Set<String> restoreTo;

        private Plan(boolean clear, Set<String> restoreTo) {
            this.clear = clear;
            this.restoreTo = restoreTo;
        }

        /** True: clear the selection (and fire the events that tell the views). */
        public boolean clear() {
            return clear;
        }

        /** When {@link #clear()} is false: the ids to store again. Unmodifiable, may be empty. */
        public Set<String> restoreTo() {
            return restoreTo;
        }
    }

    /**
     * @param mode     what to do; null clears (fail safe: a picked selection is never left behind)
     * @param previous the selection before the pick, may be null
     */
    public static Plan plan(Mode mode, Set<String> previous) {
        if (mode != Mode.RESTORE_PREVIOUS) {
            return new Plan(true, Collections.<String>emptySet());
        }
        Set<String> copy = new LinkedHashSet<>();
        if (previous != null) {
            for (String id : previous) {
                if (id != null && !id.isEmpty()) {
                    copy.add(id);
                }
            }
        }
        return new Plan(false, Collections.unmodifiableSet(copy));
    }
}
