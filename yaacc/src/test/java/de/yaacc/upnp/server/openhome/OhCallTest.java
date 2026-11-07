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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** The wait for the player's thread: result, failure, and a call that is too late must not run afterwards. */
public class OhCallTest {

    @Test
    public void theResultComesBack() {
        assertEquals("done", OhCall.runAndWait(Runnable::run, () -> "done", 1));
    }

    @Test
    public void aFailureOfTheCallIsReported() {
        try {
            OhCall.runAndWait(Runnable::run, () -> {
                throw new IllegalArgumentException("boom");
            }, 1);
            fail();
        } catch (IllegalStateException e) {
            assertEquals("boom", e.getCause().getMessage());
        }
    }

    @Test
    public void aCallThatDoesNotRunInTimeIsCancelledAndNeverRunsLater() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        Executor neverRuns = queued::add; // the player's thread is busy
        AtomicBoolean ran = new AtomicBoolean();

        try {
            OhCall.runAndWait(neverRuns, () -> {
                ran.set(true);
                return null;
            }, 1);
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("timed out"));
        }

        queued.get(0).run(); // the thread gets free now
        assertEquals(false, ran.get());
    }
}
