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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import de.yaacc.browser.BrowserLayoutChoice.Layout;

/**
 * Unit tests for {@link BrowserLayoutChoice}, which turns the stored "Layout"
 * preference into the activity to launch. Everything except an exact "new"
 * must give classic, so existing installs and Android TV never change layout
 * by accident. Plain JVM, no Android runtime.
 */
public class BrowserLayoutChoiceTest {

    @Test
    public void storedNewResolvesToNew() {
        assertEquals(Layout.NEW, BrowserLayoutChoice.resolve("new"));
    }

    @Test
    public void storedClassicResolvesToClassic() {
        assertEquals(Layout.CLASSIC, BrowserLayoutChoice.resolve("classic"));
    }

    @Test
    public void nullFallsBackToClassic() {
        assertEquals(Layout.CLASSIC, BrowserLayoutChoice.resolve(null));
    }

    @Test
    public void emptyFallsBackToClassic() {
        assertEquals(Layout.CLASSIC, BrowserLayoutChoice.resolve(""));
    }

    @Test
    public void blankFallsBackToClassic() {
        assertEquals(Layout.CLASSIC, BrowserLayoutChoice.resolve("  "));
    }

    @Test
    public void unknownValueFallsBackToClassic() {
        assertEquals(Layout.CLASSIC, BrowserLayoutChoice.resolve("bogus"));
    }

    @Test
    public void upperCaseNewFallsBackToClassic() {
        assertEquals(Layout.CLASSIC, BrowserLayoutChoice.resolve("NEW"));
    }

    @Test
    public void capitalizedClassicResolvesToClassic() {
        assertEquals(Layout.CLASSIC, BrowserLayoutChoice.resolve("Classic"));
    }

    @Test
    public void newWithSurroundingWhitespaceFallsBackToClassic() {
        assertEquals(Layout.CLASSIC, BrowserLayoutChoice.resolve(" new "));
    }

    @Test
    public void defaultStoredValueIsClassic() {
        assertEquals("classic", BrowserLayoutChoice.DEFAULT_STORED_VALUE);
    }

    @Test
    public void defaultStoredValueResolvesToClassic() {
        assertEquals(Layout.CLASSIC,
                BrowserLayoutChoice.resolve(BrowserLayoutChoice.DEFAULT_STORED_VALUE));
    }

    @Test
    public void storedConstantsMatchTheirResolvedLayouts() {
        assertEquals(Layout.NEW, BrowserLayoutChoice.resolve(BrowserLayoutChoice.STORED_NEW));
        assertEquals(Layout.CLASSIC, BrowserLayoutChoice.resolve(BrowserLayoutChoice.STORED_CLASSIC));
    }
}
