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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * The players that the YAACC renderer made and the output each was made for (task 9.1): a change of the output stops exactly
 * the renderer's players of another output, never a player the user made in the app. Plain JVM.
 */
public class RendererPlayersTest {

    private final RendererPlayers tagged = new RendererPlayers();

    @Test
    public void nothingIsToBeStoppedWhenNothingWasTagged() {
        assertTrue(tagged.toStopFor("group:x").isEmpty());
    }

    @Test
    public void aPlayerOfAnotherOutputIsToBeStoppedAndOneOfTheSameOutputIsNot() {
        Player local = mock(Player.class);
        Player group = mock(Player.class);
        tagged.tag(local, "device");
        tagged.tag(group, "group:snapcast:10.0.0.1:1705:g1");

        assertEquals(Collections.singletonList(local), tagged.toStopFor("group:snapcast:10.0.0.1:1705:g1"));
        assertEquals(Collections.singletonList(group), tagged.toStopFor("device"));
        assertEquals("another group of the same server is another output", 2, tagged.toStopFor("group:snapcast:10.0.0.1:1705:g2").size());
    }

    @Test
    public void aPlayerThatWasNeverTaggedIsNeverToBeStopped() {
        Player appsOwn = mock(Player.class);
        Player renderers = mock(Player.class);
        tagged.tag(renderers, "device");

        List<Player> toStop = tagged.toStopFor("group:x");

        assertEquals(Collections.singletonList(renderers), toStop);
        assertFalse(toStop.contains(appsOwn));
        assertFalse(tagged.isTagged(appsOwn));
        assertTrue(tagged.isTagged(renderers));
    }

    @Test
    public void aForgottenPlayerIsNotStoppedLater() {
        Player player = mock(Player.class);
        tagged.tag(player, "device");

        tagged.forget(player);

        assertTrue(tagged.toStopFor("group:x").isEmpty());
        tagged.forget(player); // twice is fine
        tagged.forget(null);
    }

    @Test
    public void taggingAgainReplacesTheOutput() {
        Player player = mock(Player.class);
        tagged.tag(player, "device");
        tagged.tag(player, "group:x");

        assertTrue(tagged.toStopFor("group:x").isEmpty());
        assertEquals(1, tagged.toStopFor("device").size());
    }

    @Test
    public void theAnswerIsACopyAndTheMapKeepsNoPlayerTwice() {
        Player a = mock(Player.class);
        Player b = mock(Player.class);
        tagged.tag(a, "device");
        tagged.tag(b, "device");

        List<Player> toStop = tagged.toStopFor("group:x");
        tagged.forget(a); // while the caller goes through its copy

        assertEquals(new HashSet<>(Arrays.asList(a, b)), new HashSet<>(toStop));
        assertEquals(1, tagged.toStopFor("group:x").size());
    }

    @Test
    public void nullsAreIgnored() {
        tagged.tag(null, "device");
        tagged.tag(mock(Player.class), null);

        assertTrue(tagged.toStopFor("group:x").isEmpty());
        assertFalse(tagged.isTagged(null));
    }
}
