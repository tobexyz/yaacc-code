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

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PickedQueueTest {

    private static PickedQueue.Entry e(String name, String mime) {
        return new PickedQueue.Entry("content://x/" + name, name, mime, 1L);
    }

    private static List<String> names(List<PickedQueue.Entry> list) {
        List<String> result = new ArrayList<>();
        for (PickedQueue.Entry entry : list) {
            result.add(entry.getDisplayName());
        }
        return result;
    }

    @Test
    public void splitsTheMediaFilesByTypeAndSkipsEverythingElse() {
        List<PickedQueue.Entry> in = new ArrayList<>();
        in.add(e("a.mp3", "audio/mpeg"));
        in.add(e("b.mp4", "video/mp4"));
        in.add(e("c.jpg", "image/jpeg"));
        in.add(e("d.pdf", "application/pdf"));
        in.add(e("e.bin", null));
        in.add(e("f.txt", "text/plain"));
        PickedQueue.Result result = PickedQueue.build(in, true);
        assertEquals(1, result.audio().size());
        assertEquals(1, result.video().size());
        assertEquals(1, result.image().size());
        assertEquals(3, result.skippedNotMedia());
        assertEquals(0, result.skippedOverCap());
        assertEquals(3, result.queuedCount());
        assertFalse(result.isEmpty());
    }

    @Test
    public void nothingToPlayIsEmpty() {
        List<PickedQueue.Entry> in = new ArrayList<>();
        in.add(e("d.pdf", "application/pdf"));
        PickedQueue.Result result = PickedQueue.build(in, true);
        assertTrue(result.isEmpty());
        assertEquals(1, result.skippedNotMedia());
        assertTrue(PickedQueue.build(new ArrayList<>(), true).isEmpty());
    }

    @Test
    public void eachTypeIsSortedByNameIgnoringCaseWithNumbersInNumericOrder() {
        List<PickedQueue.Entry> in = new ArrayList<>();
        in.add(e("track10.mp3", "audio/mpeg"));
        in.add(e("Track2.mp3", "audio/mpeg"));
        in.add(e("track1.mp3", "audio/mpeg"));
        in.add(e("b.mp3", "audio/mpeg"));
        in.add(e("A.mp3", "audio/mpeg"));
        PickedQueue.Result result = PickedQueue.build(in, true);
        assertEquals(java.util.Arrays.asList("A.mp3", "b.mp3", "track1.mp3", "Track2.mp3", "track10.mp3"),
                names(result.audio()));
    }

    @Test
    public void leadingZerosAndHugeNumbersDoNotOverflow() {
        List<PickedQueue.Entry> in = new ArrayList<>();
        in.add(e("x99999999999999999999999.mp3", "audio/mpeg"));
        in.add(e("x2.mp3", "audio/mpeg"));
        in.add(e("x002.mp3", "audio/mpeg"));
        PickedQueue.Result result = PickedQueue.build(in, true);
        assertEquals(3, result.audio().size());
        assertEquals("x99999999999999999999999.mp3", result.audio().get(2).getDisplayName());
    }

    @Test
    public void thePickersOrderIsKeptWhenNotSorting() {
        List<PickedQueue.Entry> in = new ArrayList<>();
        in.add(e("z.mp3", "audio/mpeg"));
        in.add(e("a.mp3", "audio/mpeg"));
        PickedQueue.Result result = PickedQueue.build(in, false);
        assertEquals(java.util.Arrays.asList("z.mp3", "a.mp3"), names(result.audio()));
    }

    @Test
    public void theCapCountsOverAllTypesAudioFirstThenVideoThenImage() {
        List<PickedQueue.Entry> in = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            in.add(e("i" + i + ".jpg", "image/jpeg"));
        }
        for (int i = 0; i < 300; i++) {
            in.add(e("a" + i + ".mp3", "audio/mpeg"));
        }
        for (int i = 0; i < 100; i++) {
            in.add(e("v" + i + ".mp4", "video/mp4"));
        }
        PickedQueue.Result result = PickedQueue.build(in, true);
        assertEquals(PickedQueue.MAX_FILES, result.queuedCount());
        assertEquals(300, result.audio().size());
        assertEquals(100, result.video().size());
        assertEquals(100, result.image().size());
        assertEquals(200, result.skippedOverCap());
    }

    @Test
    public void theCapIsFiveHundred() {
        assertEquals(500, PickedQueue.MAX_FILES);
    }

    @Test
    public void mimeTypesAreMatchedByTheirTypePartOnly() {
        List<PickedQueue.Entry> in = new ArrayList<>();
        in.add(e("a", "audio/"));
        in.add(e("b", "AUDIO/mpeg"));
        in.add(e("c", "audiox/mpeg"));
        in.add(e("d", "audio/ogg; codecs=opus"));
        PickedQueue.Result result = PickedQueue.build(in, true);
        assertEquals(1, result.audio().size());
        assertEquals("audio/mpeg", result.audio().get(0).getMimeType());
        assertEquals(3, result.skippedNotMedia());
    }
}
