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
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SendspinNamesTest {

    private static final String ID_A = "A" + "a".repeat(42);
    private static final String ID_B = "B" + "b".repeat(42);

    private static final class MapStore implements StringSetStore {
        final Map<String, Set<String>> map = new HashMap<>();

        @Override
        public Set<String> get(String key) {
            Set<String> found = map.get(key);
            return found == null ? new HashSet<String>() : new HashSet<>(found);
        }

        @Override
        public void put(String key, Set<String> values) {
            map.put(key, new HashSet<>(values));
        }
    }

    @Test
    public void aRememberedNameIsFoundAgainAfterARestart() {
        MapStore store = new MapStore();
        new SendspinNames(store).remember(ID_A, "Living room");
        assertEquals("Living room", new SendspinNames(store).nameOf(ID_A));
        assertNull(new SendspinNames(store).nameOf(ID_B));
    }

    @Test
    public void aNewNameReplacesTheOldOneAndAForgottenOneIsGone() {
        SendspinNames names = new SendspinNames(new MapStore());
        names.remember(ID_A, "Old");
        names.remember(ID_A, "New");
        assertEquals("New", names.nameOf(ID_A));
        names.forget(ID_A);
        assertNull(names.nameOf(ID_A));
    }

    @Test
    public void aNameIsCleanedAndAnEmptyOneIsNotKept() {
        SendspinNames names = new SendspinNames(new MapStore());
        names.remember(ID_A, "  Kit\u0000chen\n ");
        assertEquals("a control character becomes a space", "Kit chen", names.nameOf(ID_A));
        names.remember(ID_B, "  \u0001 ");
        assertNull(names.nameOf(ID_B));
        names.remember(null, "x");
        names.remember("short", "x");
        assertNull(names.nameOf("short"));
    }

    @Test
    public void atMostTheLimitOfNamesIsKept() {
        MapStore store = new MapStore();
        SendspinNames names = new SendspinNames(store);
        for (int i = 0; i < SendspinNames.MAX_NAMES + 20; i++) {
            names.remember(String.format("%043d", i), "n" + i);
        }
        assertEquals(SendspinNames.MAX_NAMES, store.get(SendspinSettings.KEY_NAMES).size());
        assertEquals("the newest is kept", "n" + (SendspinNames.MAX_NAMES + 19), names.nameOf(String.format("%043d", SendspinNames.MAX_NAMES + 19)));
        assertNull("the oldest is dropped", names.nameOf(String.format("%043d", 0)));
    }
}
