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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.view.View;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Left/right between the action buttons of a content item with the remote control. */
public class DpadActionNavigationTest {

    private static View button(boolean shown) {
        View view = mock(View.class);
        when(view.getVisibility()).thenReturn(shown ? View.VISIBLE : View.GONE);
        when(view.isFocusable()).thenReturn(true);
        when(view.isEnabled()).thenReturn(true);
        return view;
    }

    @Test
    public void rightFromTheItemTakesTheFirstShownButton() {
        View hidden = button(false);
        View first = button(true);
        List<View> actions = Arrays.asList(hidden, first, button(true));
        assertSame(first, DpadActionNavigation.next(actions, null));
    }

    @Test
    public void rightSkipsHiddenButtonsAndEndsAfterTheLastOne() {
        View a = button(true);
        View hidden = button(false);
        View c = button(true);
        List<View> actions = Arrays.asList(a, hidden, c);
        assertSame(c, DpadActionNavigation.next(actions, a));
        assertNull(DpadActionNavigation.next(actions, c));
    }

    @Test
    public void leftGoesToThePreviousShownButtonAndEndsAtTheFirst() {
        View a = button(true);
        View hidden = button(false);
        View c = button(true);
        List<View> actions = Arrays.asList(a, hidden, c);
        assertSame(a, DpadActionNavigation.previous(actions, c));
        assertNull(DpadActionNavigation.previous(actions, a));
    }

    @Test
    public void aUnknownFocusedViewHasNoNextButWhenNothingIsShownThereIsNoFirst() {
        List<View> actions = Arrays.asList(button(false), button(false));
        assertNull(DpadActionNavigation.next(actions, null));
        assertNull(DpadActionNavigation.next(actions, button(true)));
    }

    @Test
    public void aDisabledOrUnfocusableButtonIsSkipped() {
        View disabled = button(true);
        when(disabled.isEnabled()).thenReturn(false);
        View ok = button(true);
        assertSame(ok, DpadActionNavigation.next(Arrays.asList(disabled, ok), null));
    }
}
