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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which server a shown folder belongs to: the stored provider id and the id of a device event name
 * the same server with or without the "uuid:" prefix. Plain JVM.
 */
public class FolderServerMatchTest {

    @Test
    public void sameIdMatches() {
        assertTrue(FolderServerMatch.sameServer("1234-abcd", "1234-abcd"));
    }

    @Test
    public void thePrefixDoesNotMatter() {
        assertTrue(FolderServerMatch.sameServer("uuid:1234-abcd", "1234-abcd"));
        assertTrue(FolderServerMatch.sameServer("1234-abcd", "UUID:1234-abcd"));
    }

    @Test
    public void anotherServerDoesNotMatch() {
        assertFalse(FolderServerMatch.sameServer("1234-abcd", "1234-abce"));
    }

    @Test
    public void anUnsetIdNeverMatches() {
        assertFalse(FolderServerMatch.sameServer(null, null));
        assertFalse(FolderServerMatch.sameServer("", ""));
        assertFalse(FolderServerMatch.sameServer("uuid:", "uuid:"));
        assertFalse(FolderServerMatch.sameServer("1234-abcd", null));
        assertFalse(FolderServerMatch.sameServer(null, "1234-abcd"));
    }
}
