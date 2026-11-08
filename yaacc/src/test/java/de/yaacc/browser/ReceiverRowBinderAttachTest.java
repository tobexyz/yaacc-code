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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.player.Player;

/** A tap on the row of an OpenHome device that plays already: the player is made in the background, then opened. */
public class ReceiverRowBinderAttachTest {

    @Test
    public void thePlayerIsMadeOnTheBackgroundThreadAndThenHandedOver() {
        List<String> steps = new ArrayList<>();
        Player player = mock(Player.class);
        java.util.concurrent.Executor background = task -> {
            steps.add("queued");
            task.run();
        };

        ReceiverRowBinder.attachInBackground(background, () -> {
            steps.add("attach");
            return player;
        }, attached -> {
            assertEquals(player, attached);
            steps.add("open");
        });

        assertEquals(java.util.Arrays.asList("queued", "attach", "open"), steps);
    }

    @Test
    public void withoutAPlayerNothingIsOpened() {
        List<Player> opened = new ArrayList<>();

        ReceiverRowBinder.attachInBackground(Runnable::run, () -> null, opened::add);

        assertTrue(opened.isEmpty());
    }

    @Test
    public void aFailureDoesNotEndTheBackgroundThread() {
        List<Player> opened = new ArrayList<>();

        ReceiverRowBinder.attachInBackground(Runnable::run, () -> {
            throw new IllegalStateException("no service");
        }, opened::add);

        assertTrue(opened.isEmpty());
    }
}
