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
package de.yaacc.upnp.server.openhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Task 12.13: the holder through which the receiver cards reach the standby state of the app's own OpenHome renderer. Plain JVM.
 */
public class OwnOpenHomeStandbyTest {

    private static final class FakeRenderer implements OhStandby {
        boolean standby;
        final List<Runnable> listeners = new ArrayList<>();

        @Override
        public boolean isStandby() {
            return standby;
        }

        @Override
        public void setStandby(boolean value) {
            if (standby != value) {
                standby = value;
                for (Runnable listener : new ArrayList<>(listeners)) {
                    listener.run();
                }
            }
        }

        @Override
        public void addStandbyListener(Runnable listener) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }

        @Override
        public void removeStandbyListener(Runnable listener) {
            listeners.remove(listener);
        }
    }

    private static final class Counter implements OwnOpenHomeStandby.Listener {
        int told;

        @Override
        public void onOwnStandbyChanged() {
            told++;
        }
    }

    @Test
    public void withoutARendererThereIsNoStateAndNothingToChange() {
        OwnOpenHomeStandby holder = new OwnOpenHomeStandby();

        assertFalse(holder.isAttached());
        assertNull(holder.standby());
        assertFalse(holder.setStandby(true));
    }

    @Test
    public void theHolderReadsAndWritesTheStateOfTheRenderer() {
        OwnOpenHomeStandby holder = new OwnOpenHomeStandby();
        FakeRenderer renderer = new FakeRenderer();
        holder.attach(renderer);

        assertTrue(holder.isAttached());
        assertEquals(Boolean.FALSE, holder.standby());
        assertTrue(holder.setStandby(true));
        assertTrue("the renderer is in standby, as the Standby action would say", renderer.standby);
        assertEquals(Boolean.TRUE, holder.standby());
        renderer.setStandby(false); // the settings switch, through the action
        assertEquals("what the settings switch writes is what the card reads", Boolean.FALSE, holder.standby());
    }

    @Test
    public void listenersAreToldOfEveryChangeAndOfTheRendererComingAndGoing() {
        OwnOpenHomeStandby holder = new OwnOpenHomeStandby();
        FakeRenderer renderer = new FakeRenderer();
        Counter counter = new Counter();
        holder.addListener(counter);

        holder.attach(renderer);
        assertEquals("the renderer came", 1, counter.told);
        renderer.setStandby(true);
        assertEquals("the settings switch changed it", 2, counter.told);
        holder.setStandby(true);
        assertEquals("no change, nobody is told", 2, counter.told);
        holder.setStandby(false);
        assertEquals("the card changed it", 3, counter.told);
        holder.detach(renderer);
        assertEquals("the renderer went", 4, counter.told);
        assertFalse(holder.isAttached());
        assertTrue("nothing keeps listening to a renderer that went", renderer.listeners.isEmpty());
    }

    @Test
    public void aListenerThatWasRemovedIsNotToldAndTheSameListenerTwiceIsToldOnce() {
        OwnOpenHomeStandby holder = new OwnOpenHomeStandby();
        FakeRenderer renderer = new FakeRenderer();
        Counter once = new Counter();
        Counter removed = new Counter();
        holder.addListener(once);
        holder.addListener(once);
        holder.addListener(removed);
        holder.removeListener(removed);
        holder.attach(renderer);

        renderer.setStandby(true);

        assertEquals(2, once.told);
        assertEquals(0, removed.told);
    }

    @Test
    public void aNewRendererReplacesTheOldOneAndTheOldOneIsNotHeardAnyMore() {
        OwnOpenHomeStandby holder = new OwnOpenHomeStandby();
        FakeRenderer first = new FakeRenderer();
        FakeRenderer second = new FakeRenderer();
        Counter counter = new Counter();
        holder.addListener(counter);
        holder.attach(first);
        holder.attach(second);
        int before = counter.told;

        first.setStandby(true);

        assertEquals(before, counter.told);
        assertTrue(first.listeners.isEmpty());
        assertEquals(Boolean.FALSE, holder.standby());
        holder.detach(first);
        assertTrue("detaching a renderer that is not the current one changes nothing", holder.isAttached());
    }

    @Test
    public void aListenerThatThrowsDoesNotKeepTheOthersFromBeingTold() {
        OwnOpenHomeStandby holder = new OwnOpenHomeStandby();
        FakeRenderer renderer = new FakeRenderer();
        Counter counter = new Counter();
        holder.addListener(() -> {
            throw new IllegalStateException("broken screen");
        });
        holder.addListener(counter);
        holder.attach(renderer);

        renderer.setStandby(true);

        assertEquals(2, counter.told);
    }
}
