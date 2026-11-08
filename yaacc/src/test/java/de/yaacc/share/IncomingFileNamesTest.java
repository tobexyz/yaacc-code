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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for {@link IncomingFileNames}: names of the copies in the incoming directory are
 * random and never derived from the provider; the startup sweep deletes only files whose name
 * matches exactly what this class produces. Plain JVM.
 */
public class IncomingFileNamesTest {

    @Test
    public void aRandomNameHasTheFixedShape() {
        String name = IncomingFileNames.randomName(new Random(1L)::nextBytes);

        assertTrue(name, name.matches("share-[0-9a-f]{32}\\.bin"));
        assertEquals(42, name.length());
    }

    @Test
    public void theNameComesFromTheRandomSourceOnly() {
        String name = IncomingFileNames.randomName(bytes -> {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (0x10 + i);
            }
        });

        assertEquals("share-101112131415161718191a1b1c1d1e1f.bin", name);
    }

    @Test
    public void namesDiffer() {
        Random random = new Random(7L);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            names.add(IncomingFileNames.randomName(random::nextBytes));
        }

        assertEquals(50, names.size());
    }

    @Test
    public void aRandomNameIsAlwaysOurOwn() {
        Random random = new Random(3L);
        for (int i = 0; i < 100; i++) {
            assertTrue(IncomingFileNames.isOurIncomingName(IncomingFileNames.randomName(random::nextBytes)));
        }
    }

    @Test
    public void aRandomNameHasNoPathCharacters() {
        String name = IncomingFileNames.randomName(new Random(5L)::nextBytes);

        assertFalse(name.contains("/"));
        assertFalse(name.contains("\\"));
        assertFalse(name.contains(".."));
        assertFalse(name.startsWith("."));
    }

    @Test
    public void theNamesTheSweepKeepsOutOf() {
        for (String name : new String[]{
                null, "", "share-.bin", ".", "..", "song.mp3", "share-abc.bin",
                "share-101112131415161718191a1b1c1d1e1f", "share-101112131415161718191a1b1c1d1e1f.bin.tmp",
                "share-101112131415161718191a1b1c1d1e1f.BIN", "Share-101112131415161718191a1b1c1d1e1f.bin",
                "share-101112131415161718191A1B1C1D1E1F.bin", "share-101112131415161718191a1b1c1d1e1.bin",
                "share-101112131415161718191a1b1c1d1e1f0.bin", "share-101112131415161718191a1b1c1d1e1g.bin",
                " share-101112131415161718191a1b1c1d1e1f.bin", "share-101112131415161718191a1b1c1d1e1f.bin ",
                "share-101112131415161718191a1b1c1d1e1f.bin\n", "../share-101112131415161718191a1b1c1d1e1f.bin",
                "a/share-101112131415161718191a1b1c1d1e1f.bin", "share-101112131415161718191a1b1c1d1e1f.bin/",
                "share-1011121314151617١819 1a1b1c1d1e1f.bin", "share-١٢٣٤٥٦٧٨٩٠١٢٣٤٥٦١٢٣٤٥٦٧٨٩٠١٢٣٤٥٦.bin",
                "cache.xml", "shared_prefs", "incoming"}) {
            assertFalse("should not be ours: " + name, IncomingFileNames.isOurIncomingName(name));
        }
    }

    @Test
    public void aNameWithTheRightShapeIsOurs() {
        assertTrue(IncomingFileNames.isOurIncomingName("share-00000000000000000000000000000000.bin"));
        assertTrue(IncomingFileNames.isOurIncomingName("share-ffffffffffffffffffffffffffffffff.bin"));
        assertTrue(IncomingFileNames.isOurIncomingName("share-0123456789abcdef0123456789abcdef.bin"));
    }

    @Test
    public void aMissingRandomSourceIsRefused() {
        try {
            IncomingFileNames.randomName(null);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }
}
