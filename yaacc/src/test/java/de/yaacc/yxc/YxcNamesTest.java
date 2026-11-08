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
package de.yaacc.yxc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class YxcNamesTest {
    @Test
    public void namesAreMadeFitForOneLine() {
        assertNull(YxcNames.clean(null));
        assertNull(YxcNames.clean(" \u202e \n "));
        assertEquals("Kitchen", YxcNames.clean("Kit\u202echen\n"));
        String longName = new String(new char[500]).replace('\0', 'x');
        assertEquals(YxcNames.MAX_NAME_CHARS + 1, YxcNames.clean(longName).length());
    }
}
