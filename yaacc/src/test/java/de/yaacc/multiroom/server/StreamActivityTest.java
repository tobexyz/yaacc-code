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
package de.yaacc.multiroom.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** When a stream is idle or playing, as snapserver shows it (docs/tech.md: a chunk of zeros is silent; 100 ms of silence is idle). */
public class StreamActivityTest {

    @Test
    public void aStreamWithoutAProducerIsIdle() {
        assertEquals(StreamActivity.Status.IDLE, new StreamActivity(100, 20).status());
    }

    @Test
    public void theFirstChunkWithSoundMakesItPlaying() {
        StreamActivity a = new StreamActivity(100, 20);
        a.chunk(false);
        assertEquals(StreamActivity.Status.PLAYING, a.status());
    }

    @Test
    public void silenceOfTheThresholdMakesItIdleAndSoundPlaysAgain() {
        StreamActivity a = new StreamActivity(100, 20);
        a.chunk(false);
        for (int i = 0; i < 4; i++) {
            a.chunk(true);
            assertEquals("80 ms of silence is not yet idle", StreamActivity.Status.PLAYING, a.status());
        }
        a.chunk(true);
        assertEquals("100 ms of silence is", StreamActivity.Status.IDLE, a.status());
        a.chunk(false);
        assertEquals(StreamActivity.Status.PLAYING, a.status());
    }

    @Test
    public void aSilentChunkBetweenSoundDoesNotCount() {
        StreamActivity a = new StreamActivity(100, 20);
        a.chunk(false);
        a.chunk(true);
        a.chunk(true);
        a.chunk(false);
        a.chunk(true);
        assertEquals(StreamActivity.Status.PLAYING, a.status());
    }

    @Test
    public void whenTheProducerGoesTheStreamIsIdle() {
        StreamActivity a = new StreamActivity(100, 20);
        a.chunk(false);
        a.producerGone();
        assertEquals(StreamActivity.Status.IDLE, a.status());
    }
}
