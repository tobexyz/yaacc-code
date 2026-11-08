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
package de.yaacc.upnp.server.contentdirectory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Grouping the rows of the media store to galleries: one per bucket, counted, with names that tell them apart. */
public class MediaGalleriesTest {

    private static List<String[]> rows(String... idAndName) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < idAndName.length; i += 2) {
            rows.add(new String[]{idAndName[i], idAndName[i + 1]});
        }
        return rows;
    }

    private static List<String> names(List<MediaGalleries.Gallery> galleries) {
        List<String> names = new ArrayList<>();
        for (MediaGalleries.Gallery gallery : galleries) {
            names.add(gallery.name);
        }
        return names;
    }

    @Test
    public void everyBucketIsOneGalleryInTheOrderItWasSeenAndIsCounted() {
        List<MediaGalleries.Gallery> galleries = MediaGalleries.group(
                rows("1", "Camera", "1", "Camera", "2", "CapCut", "1", "Camera"), "(root)");

        assertEquals(Arrays.asList("Camera", "CapCut"), names(galleries));
        assertEquals("1", galleries.get(0).id);
        assertEquals(3, galleries.get(0).count);
        assertEquals("2", galleries.get(1).id);
        assertEquals(1, galleries.get(1).count);
    }

    @Test
    public void aSecondGalleryWithTheNameOfAnotherOneGetsACounterInTheOrderOfTheIds() {
        // the cursor may deliver the buckets in any order: the names must not depend on it
        List<MediaGalleries.Gallery> forwards = MediaGalleries.group(rows("5", "Camera", "9", "Camera", "7", "Camera"), "(root)");
        List<MediaGalleries.Gallery> backwards = MediaGalleries.group(rows("7", "Camera", "9", "Camera", "5", "Camera"), "(root)");

        assertEquals("Camera", forwards.get(0).name);
        assertEquals("Camera (2)", forwards.get(2).name);   // id 7
        assertEquals("Camera (3)", forwards.get(1).name);   // id 9
        assertEquals("Camera", backwards.get(2).name);
        assertEquals("Camera (2)", backwards.get(0).name);
        assertEquals("Camera (3)", backwards.get(1).name);
    }

    @Test
    public void idsAreComparedAsNumbersAlsoWhenNegative() {
        // a bucket id is the hash of the path, an int: "-5" < "10" as numbers, but not as text
        List<MediaGalleries.Gallery> galleries = MediaGalleries.group(rows("10", "Movies", "-5", "Movies"), "(root)");

        assertEquals("Movies", galleries.get(1).name);       // id -5
        assertEquals("Movies (2)", galleries.get(0).name);   // id 10
    }

    @Test
    public void aBucketWithoutANameGetsTheGivenText() {
        List<MediaGalleries.Gallery> galleries = MediaGalleries.group(rows("4", null, "6", ""), "(root)");

        assertEquals(Arrays.asList("(root)", "(root) (2)"), names(galleries));
    }

    @Test
    public void noRowsNoGalleries() {
        assertTrue(MediaGalleries.group(Collections.emptyList(), "(root)").isEmpty());
    }

    @Test
    public void aPageIsCutFromTheList() {
        List<Integer> all = Arrays.asList(1, 2, 3, 4, 5);

        assertEquals(Arrays.asList(1, 2), MediaGalleries.page(all, 0, 2));
        assertEquals(Arrays.asList(3, 4, 5), MediaGalleries.page(all, 2, Long.MAX_VALUE));
        assertEquals(Arrays.asList(5), MediaGalleries.page(all, 4, 10));
        assertTrue(MediaGalleries.page(all, 5, 10).isEmpty());
        assertTrue(MediaGalleries.page(all, 99, 10).isEmpty());
        assertTrue(MediaGalleries.page(all, 0, 0).isEmpty());
        assertEquals(all, MediaGalleries.page(all, -3, 99));
    }
}
