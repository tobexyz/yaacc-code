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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The one rule for the type of what a controller sent to the renderer (Fix Group 9b S5): the type its description gives, else
 * the one of the file extension. Creating the player and finding it again must use the same type, or the lookup by kind looks
 * for another kind of player than the one that was made. Plain JVM.
 */
public class ContentTypesTest {

    @Test
    public void theTypeOfTheDescriptionWins() {
        assertEquals("audio/flac", ContentTypes.pick("audio/flac", "audio/mpeg"));
        assertEquals("video/mp4", ContentTypes.pick("video/mp4", null));
    }

    @Test
    public void withoutADescriptionTheFileExtensionDecides() {
        assertEquals("audio/mpeg", ContentTypes.pick(null, "audio/mpeg"));
        assertEquals("audio/mpeg", ContentTypes.pick("", "audio/mpeg"));
        assertEquals("audio/mpeg", ContentTypes.pick("   ", "audio/mpeg"));
    }

    @Test
    public void whenNothingKnowsTheTypeItIsNullNeverAnEmptyText() {
        assertNull(ContentTypes.pick(null, null));
        assertNull(ContentTypes.pick("", ""));
        assertNull(ContentTypes.pick("", null));
        assertNull(ContentTypes.pick(null, "  "));
    }
}
