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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for {@link RendererIdPolicy}: the device id in a proxy URL is untrusted input. */
public class RendererIdPolicyTest {

    @Test
    public void realisticUdnsAreValid() {
        assertTrue(RendererIdPolicy.isValid("5f9ec1b3-ed59-1900-4530-0011d9a2c2e1"));
        assertTrue(RendererIdPolicy.isValid("uuid:5f9ec1b3-ed59-1900-4530-0011d9a2c2e1"));
        assertTrue(RendererIdPolicy.isValid("RINCON_000E58F0123401400"));
        assertTrue(RendererIdPolicy.isValid("Upnp-MediaRenderer-1_0-abc.def"));
        assertTrue(RendererIdPolicy.isValid("a"));
    }

    @Test
    public void nullAndEmptyAreInvalid() {
        assertFalse(RendererIdPolicy.isValid(null));
        assertFalse(RendererIdPolicy.isValid(""));
    }

    @Test
    public void theLengthLimitIs200() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append('a');
        }
        assertTrue(RendererIdPolicy.isValid(sb.toString()));
        assertFalse(RendererIdPolicy.isValid(sb + "a"));
    }

    @Test
    public void everythingOutsideTheCharsetIsInvalid() {
        String[] bad = {"a b", "a/b", "a\\b", "a%2Fb", "a%", "../x", "a\n", "a\r\nb", "a\t", "a\u0000", "ä",
                "аbc", "a+b", "a@b", "a<b", "a>b", "a\"b", "a'b", "a&b", "a?b", "a#b", "a=b", "a,b", "a;b",
                "a*b", "{a}", "a|b", "‮a", "a​"};
        for (String id : bad) {
            assertFalse("should be invalid: " + id.replace("\n", "\\n"), RendererIdPolicy.isValid(id));
        }
    }
}
