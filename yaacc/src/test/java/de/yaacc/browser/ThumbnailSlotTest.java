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

public class ThumbnailSlotTest {

    private static final String K1 = ThumbnailSlot.key("http://s/a.jpg", 320, 320);
    private static final String K2 = ThumbnailSlot.key("http://s/b.jpg", 320, 320);

    @Test
    public void keyContainsUriAndSize() {
        assertEquals("http://s/a.jpg@320x320", K1);
        assertFalse(K1.equals(ThumbnailSlot.key("http://s/a.jpg", 48, 48)));
    }

    @Test
    public void noThumbnailIsNone() {
        ThumbnailSlot slot = new ThumbnailSlot();
        assertEquals(ThumbnailSlot.Decision.NONE, slot.onBind(null, false));
        assertNull(slot.key());
    }

    @Test
    public void firstBindLoadsAndTheSameKeyWhileLoadingKeeps() {
        ThumbnailSlot slot = new ThumbnailSlot();
        assertEquals(ThumbnailSlot.Decision.LOAD, slot.onBind(K1, false));
        assertTrue(slot.isLoading());
        assertEquals(ThumbnailSlot.Decision.KEEP, slot.onBind(K1, false));
    }

    @Test
    public void sameKeyOnceShownKeeps() {
        ThumbnailSlot slot = new ThumbnailSlot();
        slot.onBind(K1, false);
        slot.onFinished(K1, true);
        assertFalse(slot.isLoading());
        assertEquals(ThumbnailSlot.Decision.KEEP, slot.onBind(K1, true));
    }

    @Test
    public void aCachedBitmapIsShownAtOnceAndThenKept() {
        ThumbnailSlot slot = new ThumbnailSlot();
        assertEquals(ThumbnailSlot.Decision.SHOW_CACHED, slot.onBind(K1, true));
        assertEquals(ThumbnailSlot.Decision.KEEP, slot.onBind(K1, true));
    }

    @Test
    public void anotherKeyReplacesTheRequest() {
        ThumbnailSlot slot = new ThumbnailSlot();
        slot.onBind(K1, false);
        assertEquals(ThumbnailSlot.Decision.LOAD, slot.onBind(K2, false));
        assertEquals(K2, slot.key());
        // the late answer of the first request changes nothing
        slot.onFinished(K1, true);
        assertTrue(slot.isLoading());
    }

    @Test
    public void rowWithoutThumbnailAfterOneWithThumbnailResets() {
        ThumbnailSlot slot = new ThumbnailSlot();
        slot.onBind(K1, false);
        assertEquals(ThumbnailSlot.Decision.NONE, slot.onBind(null, false));
        assertEquals(ThumbnailSlot.Decision.LOAD, slot.onBind(K1, false));
    }

    @Test
    public void failedOrCancelledLoadIsTriedAgainOnTheNextBind() {
        ThumbnailSlot slot = new ThumbnailSlot();
        slot.onBind(K1, false);
        slot.onFinished(K1, false);
        assertEquals(ThumbnailSlot.Decision.LOAD, slot.onBind(K1, false));
    }

    @Test
    public void releaseForgetsTheRequest() {
        ThumbnailSlot slot = new ThumbnailSlot();
        slot.onBind(K1, false);
        slot.onFinished(K1, true);
        slot.release();
        assertNull(slot.key());
        assertEquals(ThumbnailSlot.Decision.LOAD, slot.onBind(K1, false));
    }
}
