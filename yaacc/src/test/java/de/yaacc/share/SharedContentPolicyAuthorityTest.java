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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** {@link SharedContentPolicy#authorityOf}: the authority handed to the package check. */
public class SharedContentPolicyAuthorityTest {

    @Test
    public void returnsTheAuthorityAsWritten() {
        assertEquals("media", SharedContentPolicy.authorityOf("content://media/external/audio/media/1"));
        assertEquals("com.Example.Files", SharedContentPolicy.authorityOf("content://com.Example.Files/a/b?x=1#y"));
        assertEquals("a.b", SharedContentPolicy.authorityOf("content://a.b"));
        assertEquals("a.b", SharedContentPolicy.authorityOf("CONTENT://a.b/x"));
    }

    @Test
    public void returnsNullWhereThePolicyWouldRefuse() {
        assertNull(SharedContentPolicy.authorityOf(null));
        assertNull(SharedContentPolicy.authorityOf(""));
        assertNull(SharedContentPolicy.authorityOf("file:///x"));
        assertNull(SharedContentPolicy.authorityOf("content://"));
        assertNull(SharedContentPolicy.authorityOf("content:///x"));
        assertNull(SharedContentPolicy.authorityOf("content://0@media/x"));
        assertNull(SharedContentPolicy.authorityOf("content://me%64ia/x"));
        assertNull(SharedContentPolicy.authorityOf("content://media/a b"));
    }
}
