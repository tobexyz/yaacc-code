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
package de.yaacc.browser;

import android.view.View;

import java.util.List;

/**
 * Which action button of a content item gets the focus next when the remote control (D-pad) moves
 * left or right between them. Buttons that are not shown ({@code View.VISIBLE}) or not focusable are
 * skipped. The default focus search cannot do this: it looks for views next to the focused one, and
 * the buttons lie inside the item, so from the item itself it never lands on them.
 */
final class DpadActionNavigation {

    private DpadActionNavigation() {
    }

    /**
     * @param current the focused button, or null to take the first one
     * @return the next shown button after {@code current}, or null if there is none
     */
    static View next(List<View> actions, View current) {
        int start = current == null ? 0 : actions.indexOf(current) + 1;
        if (current != null && start == 0) {
            return null;
        }
        for (int i = start; i < actions.size(); i++) {
            if (usable(actions.get(i))) {
                return actions.get(i);
            }
        }
        return null;
    }

    /** @return the shown button before {@code current}, or null if there is none */
    static View previous(List<View> actions, View current) {
        int index = actions.indexOf(current);
        for (int i = (index < 0 ? actions.size() : index) - 1; i >= 0; i--) {
            if (usable(actions.get(i))) {
                return actions.get(i);
            }
        }
        return null;
    }

    private static boolean usable(View view) {
        return view != null && view.getVisibility() == View.VISIBLE && view.isFocusable() && view.isEnabled();
    }
}
