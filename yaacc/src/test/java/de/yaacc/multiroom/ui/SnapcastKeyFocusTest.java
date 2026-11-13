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
package de.yaacc.multiroom.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Which Snapcast group row the hardware volume keys go to (11.4): the row that has the input focus (D-pad, keyboard), else the
 * row the user touched last, as long as it is on screen. Nothing is guessed when no row was touched. Plain JVM.
 */
public class SnapcastKeyFocusTest {

    @Test
    public void theRowWithTheInputFocusWins() {
        SnapcastKeyFocus focus = new SnapcastKeyFocus();
        focus.touched("snapcast:a");

        assertEquals("snapcast:b", focus.target(Arrays.asList("snapcast:a", "snapcast:b"), "snapcast:b"));
    }

    @Test
    public void withoutInputFocusTheRowTouchedLastIsTheTarget() {
        SnapcastKeyFocus focus = new SnapcastKeyFocus();
        focus.touched("snapcast:a");
        focus.touched("snapcast:b");

        assertEquals("snapcast:b", focus.target(Arrays.asList("snapcast:a", "snapcast:b"), null));
    }

    @Test
    public void aRowThatIsNotOnScreenIsNoTarget() {
        SnapcastKeyFocus focus = new SnapcastKeyFocus();
        focus.touched("snapcast:a");

        assertNull(focus.target(Collections.singletonList("snapcast:b"), null));
        assertNull(focus.target(Collections.<String>emptyList(), null));
    }

    @Test
    public void aFocusOnARowThatIsNotASnapcastRowFallsBackToTheTouchedOne() {
        SnapcastKeyFocus focus = new SnapcastKeyFocus();
        focus.touched("snapcast:a");

        assertEquals("snapcast:a", focus.target(Collections.singletonList("snapcast:a"), "uuid:some-renderer"));
    }

    @Test
    public void nothingTouchedAndNoFocusIsNoTargetEvenWithOneRowOnScreen() {
        SnapcastKeyFocus focus = new SnapcastKeyFocus();

        assertNull(focus.target(Collections.singletonList("snapcast:a"), null));
    }

    @Test
    public void whenTheHostIsLeftNothingIsRemembered() {
        SnapcastKeyFocus focus = new SnapcastKeyFocus();
        focus.touched("snapcast:a");

        focus.forget();

        assertNull(focus.target(Collections.singletonList("snapcast:a"), null));
    }

    @Test
    public void emptyOrNullIdsAreNotRemembered() {
        SnapcastKeyFocus focus = new SnapcastKeyFocus();
        focus.touched("snapcast:a");

        focus.touched(null);
        focus.touched("");

        assertEquals("snapcast:a", focus.target(Collections.singletonList("snapcast:a"), null));
    }
}
