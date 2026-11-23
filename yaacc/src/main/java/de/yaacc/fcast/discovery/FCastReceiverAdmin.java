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
package de.yaacc.fcast.discovery;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Removes a receiver that was added by hand (task 5.4): it leaves the manual list, and its id leaves the stored selection of receivers
 * (a selected receiver that is gone would stay as an offline card and be sent to on every play), and the memory of the selected
 * receivers follows the selection. Like the Snapcast server admin; pure, the stores are behind interfaces. A receiver that is still
 * announced on the network is listed again as a discovered one: the user can remove what was typed in or remembered, not what is
 * on the network.
 */
public final class FCastReceiverAdmin {

    /** The manual list ({@link FCastHub#removeManual} in the app). */
    public interface Manual {
        boolean removeManual(FCastEndpoint endpoint);
    }

    /** Where the ids of the selected receivers live ({@code SharedPreferences} in the app). */
    public interface SelectionStore {
        Set<String> read();

        void write(Set<String> receiverIds);
    }

    /** The memory of the selected receivers ({@link FCastHub#selectionChanged} in the app). */
    public interface Remembered {
        void selectionChanged(Collection<String> receiverIds);
    }

    /** What a removal did. */
    public static final class Result {
        private final boolean wasManual;
        private final int selectionRemoved;

        private Result(boolean wasManual, int selectionRemoved) {
            this.wasManual = wasManual;
            this.selectionRemoved = selectionRemoved;
        }

        public boolean wasManual() {
            return wasManual;
        }

        public int getSelectionRemoved() {
            return selectionRemoved;
        }
    }

    private final Manual manual;
    private final SelectionStore selection;
    private final Remembered remembered;

    public FCastReceiverAdmin(Manual manual, SelectionStore selection, Remembered remembered) {
        this.manual = manual;
        this.selection = selection;
        this.remembered = remembered;
    }

    /** Removes the receiver as described; null or an unknown receiver changes nothing. */
    public Result remove(FCastEndpoint endpoint) {
        if (endpoint == null) {
            return new Result(false, 0);
        }
        boolean wasManual = manual.removeManual(endpoint);
        int removed = 0;
        try {
            Set<String> stored = selection.read();
            Set<String> remaining = new HashSet<>(stored);
            remaining.remove(FCastReceiverId.of(endpoint));
            removed = stored.size() - remaining.size();
            if (removed > 0) {
                selection.write(remaining);
            }
            remembered.selectionChanged(remaining);
        } catch (RuntimeException unreadable) {
            // the selection could not be read or written: the manual list is done all the same
            removed = 0;
        }
        return new Result(wasManual, removed);
    }
}
