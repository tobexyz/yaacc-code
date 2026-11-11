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
package de.yaacc.multiroom.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.control.SnapcastOperations;

import org.junit.Test;

/** The stream handling follows whichever control connection the receivers hold, and says "no" while there is none. */
public class LinkBoundStreamsTest {

    private final SimulatedSnapcastServer server = new SimulatedSnapcastServer();
    private SnapcastOperations operations;
    private final SnapcastStreams.MarkerStore store = new SnapcastStreams.MarkerStore() {
        private String value;

        @Override
        public String load() {
            return value;
        }

        @Override
        public void save(String marker) {
            value = marker;
        }
    };

    private LinkBoundStreams streams() {
        return new LinkBoundStreams(() -> operations, "Phone", store);
    }

    @Test
    public void withoutAConnectionEveryCallSaysNoAndNothingIsTouched() {
        LinkBoundStreams streams = streams();

        SnapcastStreams.EnsureResult ensured = streams.ensure();
        assertEquals(SnapcastStreams.Status.FAILED, ensured.getStatus());
        assertEquals(SnapcastStreams.UseResult.FAILED, streams.useFor("g1"));
        assertEquals(SnapcastStreams.ReleaseResult.FAILED, streams.release("g1"));
        assertFalse(streams.removeIfIdle());
        assertFalse(streams.shutdown());
        streams.noteAudio();

        assertEquals(0, server.streamCount());
        assertEquals(0, server.calls().size());
    }

    @Test
    public void withAConnectionItDoesWhatSnapcastStreamsDoes() {
        server.bindRealListeners();
        server.addPipeStream("Default");
        server.addGroup("g1", "Default", "c1");
        operations = new SnapcastOperations(server);
        LinkBoundStreams streams = streams();

        SnapcastStreams.EnsureResult ensured = streams.ensure();
        assertTrue(ensured.isReady());
        assertEquals(SnapcastStreams.UseResult.OK, streams.useFor("g1"));
        assertEquals(ensured.getStreamId(), server.groupStream("g1"));
        assertEquals(SnapcastStreams.ReleaseResult.RESTORED, streams.release("g1"));
        assertEquals("Default", server.groupStream("g1"));
        assertTrue(streams.shutdown());
        assertFalse(server.hasStream(ensured.getStreamId()));
    }

    @Test
    public void aReplacedConnectionGetsANewInstanceThatAdoptsTheSameStreamBecauseTheMarkerIsKept() {
        server.bindRealListeners();
        server.addGroup("g1", "", "c1");
        operations = new SnapcastOperations(server);
        LinkBoundStreams streams = streams();
        SnapcastStreams.EnsureResult first = streams.ensure();
        assertTrue(first.isReady());
        assertFalse(first.isAdopted());

        operations = new SnapcastOperations(server); // another connection object to the same server
        SnapcastStreams.EnsureResult second = streams.ensure();

        assertTrue(second.isReady());
        assertTrue("the stream with the same marker is taken over, not made twice", second.isAdopted());
        assertEquals(first.getStreamId(), second.getStreamId());
        assertEquals(1, server.streamCount());
    }

    @Test
    public void theSameConnectionKeepsTheSameInstanceAndItsRecords() {
        server.bindRealListeners();
        server.addPipeStream("Default");
        server.addGroup("g1", "Default", "c1");
        operations = new SnapcastOperations(server);
        LinkBoundStreams streams = streams();
        streams.ensure();
        streams.useFor("g1");

        assertEquals("the record of the first call is still there", SnapcastStreams.ReleaseResult.RESTORED,
                streams.release("g1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMissingSourceIsRefused() {
        new LinkBoundStreams(null, "Phone", store);
    }

    @Test
    public void noteAudioNeverWaitsForTheMonitorThatMakesTheStreams() throws Exception {
        server.bindRealListeners();
        operations = new SnapcastOperations(server);
        final LinkBoundStreams streams = new LinkBoundStreams(() -> operations, "Phone", new SnapcastStreams.MarkerStore() {
            @Override
            public String load() {
                try {
                    Thread.sleep(1_500); // a slow SharedPreferences read inside the monitor
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }

            @Override
            public void save(String marker) {
                // not kept
            }
        });
        Thread maker = new Thread(streams::ensure, "maker");
        maker.setDaemon(true);
        maker.start();
        Thread.sleep(200); // the maker holds the monitor and reads the marker

        long start = System.nanoTime();
        streams.noteAudio();

        assertTrue("noteAudio took " + (System.nanoTime() - start) / 1_000_000 + " ms",
                System.nanoTime() - start < 500_000_000L);
        maker.join(5_000);
    }
}
