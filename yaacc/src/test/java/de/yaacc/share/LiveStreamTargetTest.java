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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/** The live stream share: the capture is given back once, when the entry of the registry goes. */
public class LiveStreamTargetTest {

    private static final class FakeCapture implements LiveStreamTarget.Capture {
        final AtomicInteger released = new AtomicInteger();
        final InputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});

        @Override
        public InputStream open() {
            return stream;
        }

        @Override
        public void release() {
            released.incrementAndGet();
        }
    }

    @Test
    public void aShareOpensTheStreamOfTheCaptureAndKnowsItsKind() throws Exception {
        FakeCapture capture = new FakeCapture();
        LiveStreamTarget target = new LiveStreamTarget(true, capture);
        assertTrue(target.isAudio());
        assertSame(capture.stream, target.open());
    }

    @Test
    public void closingGivesTheCaptureBackOnceAndOpensNothingAfterwards() throws Exception {
        FakeCapture capture = new FakeCapture();
        LiveStreamTarget target = new LiveStreamTarget(false, capture);
        target.close();
        target.close();
        assertEquals(1, capture.released.get());
        assertNull(target.open());
    }

    @Test
    public void theRegistryClosesTheTargetWhenTheEntryGoes() {
        FakeCapture capture = new FakeCapture();
        ShareRegistry registry = ShareRegistry.createDefault();
        String key = registry.registerOrEvict(new ShareRegistry.Entry(ShareRegistry.Kind.LIVE_STREAM,
                new LiveStreamTarget(true, capture), "audio/wav", Collections.singleton("192.168.0.9"),
                ShareLimits.IDLE_GRACE_MILLIS, ShareLimits.ABSOLUTE_MAX_MILLIS));
        assertTrue(registry.lookup(key) != null);
        registry.remove(key);
        assertEquals("the capture goes back with the entry", 1, capture.released.get());
    }
}
