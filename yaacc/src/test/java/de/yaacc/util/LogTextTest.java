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
package de.yaacc.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Text from another device in the log: one line, not too long. */
public class LogTextTest {

    @Test
    public void lineBreaksAndOtherControlCharactersBecomeSpaces() {
        assertEquals("Living room  INFO fake line", LogText.clean("Living room\r\nINFO fake line"));
        assertEquals("a b c", LogText.clean("a\tb\u0000c"));
    }

    @Test
    public void longTextIsShortenedAndNullIsEmpty() {
        assertEquals(200, LogText.clean("x".repeat(5000)).length());
        assertEquals("", LogText.clean(null));
        assertEquals("plain", LogText.clean("plain"));
    }
}
