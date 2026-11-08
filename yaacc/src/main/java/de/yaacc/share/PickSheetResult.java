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
 * The receiver sheet's own gate in pick mode: it makes "exactly one answer" true. Plain Java, used
 * from the main thread only.
 * <p>
 * The sheet reports a confirm when the confirm button is pressed with at least one receiver
 * ({@link Action#SEND_CONFIRM}); a confirm without a receiver only shows a hint
 * ({@link Action#SHOW_HINT}, see {@link ReceiverPickResult.Decision#keepPickerOpen()}). Every way of
 * closing the sheet ends in {@code onDismiss}, which reports a cancel unless an answer was already
 * sent ({@link #dismissed()}), so the dismiss that follows a confirm is silent and nothing is
 * reported twice. A sheet that has no pick behind it any more (restored after a rotation or process
 * death) is {@link #silence() silenced}.
 */
public final class PickSheetResult {

    /** What the sheet has to do. */
    public enum Action {
        /** Nothing (already answered). */
        NONE,
        /** Report a confirm with {@link #confirmedIds()}, then dismiss. */
        SEND_CONFIRM,
        /** Report a cancel. */
        SEND_CANCEL,
        /** Stay open and show the "choose a receiver" hint. */
        SHOW_HINT
    }

    private boolean delivered;
    private Set<String> confirmedIds = Collections.emptySet();

    /** @param ticked the receivers ticked in the list, may be null */
    public Action confirm(Set<String> ticked) {
        if (delivered) {
            return Action.NONE;
        }
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(true, ticked);
        if (decision.keepPickerOpen()) {
            return Action.SHOW_HINT;
        }
        delivered = true;
        confirmedIds = Collections.unmodifiableSet(new LinkedHashSet<>(decision.receiversToUse()));
        return Action.SEND_CONFIRM;
    }

    /** The sheet was closed (called from {@code onDismiss}, which runs for every way of closing). */
    public Action dismissed() {
        if (delivered) {
            return Action.NONE;
        }
        delivered = true;
        return Action.SEND_CANCEL;
    }

    /** Nothing may be reported from now on. */
    public void silence() {
        delivered = true;
    }

    /** True once an answer was sent (or the gate was silenced). */
    public boolean isDelivered() {
        return delivered;
    }

    /** The receivers of the sent confirm; empty before it. Unmodifiable. */
    public Set<String> confirmedIds() {
        return confirmedIds;
    }
}
