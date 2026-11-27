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
package de.yaacc.sendspin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Base64;

/** The ids of the groups, as the protocol wants them: 32 bytes chosen by the server, base64url without padding, 43 characters. */
public class GroupIdsTest {

    private final GroupIds ids = new GroupIds(new SecureRandom());

    @Test
    public void anIdIs43Base64urlCharactersOf32Bytes() {
        String id = ids.idOf("leader");
        assertEquals(43, id.length());
        assertTrue(id.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(32, Base64.getUrlDecoder().decode(id).length);
    }

    @Test
    public void theIdOfAGroupDoesNotChangeWhileTheAppRuns() {
        assertEquals(ids.idOf("a"), ids.idOf("a"));
    }

    @Test
    public void everyGroupHasItsOwnId() {
        assertNotEquals(ids.idOf("a"), ids.idOf("b"));
    }

    @Test
    public void anotherRunMakesOtherIds() {
        assertNotEquals(ids.idOf("a"), new GroupIds(new SecureRandom()).idOf("a"));
    }
}
