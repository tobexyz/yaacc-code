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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Unit tests for {@link MiniPlayerArtKeys}: the bookkeeping that lets a mini-player row ask for
 * its album art (and read {@code Player#getIcon()}) once per player-and-art key, however often
 * the row is refreshed, scrolled or rebound. Plain JVM, no Android runtime.
 */
public class MiniPlayerArtKeysTest {

    @Test
    public void aKeyIsNewTheFirstTimeOnly() {
        MiniPlayerArtKeys keys = new MiniPlayerArtKeys();

        assertTrue(keys.isNewKey(1, "1:http://a"));
        assertFalse(keys.isNewKey(1, "1:http://a"));
        assertFalse(keys.isNewKey(1, "1:http://a"));
    }

    @Test
    public void aChangedKeyOfTheSamePlayerIsNewOnce() {
        MiniPlayerArtKeys keys = new MiniPlayerArtKeys();
        keys.isNewKey(1, "1:http://a");

        assertTrue(keys.isNewKey(1, "1:http://b"));
        assertFalse(keys.isNewKey(1, "1:http://b"));
    }

    @Test
    public void goingBackToAnEarlierKeyIsNewAgain() {
        // only the last key per player is remembered: a track list that alternates art asks again
        MiniPlayerArtKeys keys = new MiniPlayerArtKeys();
        keys.isNewKey(1, "1:http://a");
        keys.isNewKey(1, "1:http://b");

        assertTrue(keys.isNewKey(1, "1:http://a"));
    }

    @Test
    public void playersAreIndependent() {
        MiniPlayerArtKeys keys = new MiniPlayerArtKeys();

        assertTrue(keys.isNewKey(1, "1:http://a"));
        assertTrue(keys.isNewKey(2, "2:http://a"));
        assertFalse(keys.isNewKey(1, "1:http://a"));
        assertFalse(keys.isNewKey(2, "2:http://a"));
    }

    @Test
    public void retainOnlyForgetsPlayersThatAreGone() {
        MiniPlayerArtKeys keys = new MiniPlayerArtKeys();
        keys.isNewKey(1, "1:x");
        keys.isNewKey(2, "2:x");
        keys.isNewKey(3, "3:x");

        keys.retainOnly(Arrays.asList(1, 3));

        assertEquals("1:x", keys.lastKey(1));
        assertNull(keys.lastKey(2));
        assertEquals("3:x", keys.lastKey(3));
        assertFalse(keys.isNewKey(1, "1:x"));
        assertTrue(keys.isNewKey(2, "2:x"));
    }

    @Test
    public void retainOnlyWithNoPlayersForgetsEverything() {
        MiniPlayerArtKeys keys = new MiniPlayerArtKeys();
        keys.isNewKey(1, "1:x");

        keys.retainOnly(Collections.<Integer>emptyList());
        assertNull(keys.lastKey(1));

        keys.isNewKey(1, "1:x");
        keys.retainOnly(null);
        assertNull(keys.lastKey(1));
    }

    @Test
    public void clearForgetsEverything() {
        MiniPlayerArtKeys keys = new MiniPlayerArtKeys();
        keys.isNewKey(1, "1:x");
        keys.isNewKey(2, "2:x");

        keys.clear();

        assertNull(keys.lastKey(1));
        assertTrue(keys.isNewKey(1, "1:x"));
    }

    @Test
    public void aNullKeyIsNeverNewAndNeverStored() {
        MiniPlayerArtKeys keys = new MiniPlayerArtKeys();

        assertFalse(keys.isNewKey(1, null));
        assertNull(keys.lastKey(1));
    }
}
