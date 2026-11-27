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
package de.yaacc.sendspin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A peer that stops reading fills the socket, and a write then waits for ever (a blocking write has no timeout). The connection breaks
 * such a write by closing its output after a limit, so that the thread that writes (often the one command thread of the app) is not held.
 */
public class WsWriteStallTest {

    /** An output whose write blocks until the output is closed, as a socket with a full buffer does. */
    private static final class BlockingOut extends OutputStream {
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicBoolean blocking = new AtomicBoolean(true);

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (blocking.get()) {
                try {
                    closed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("closed while writing");
            }
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    @Test(timeout = 15_000)
    public void aWriteThatDoesNotEndIsBrokenAfterTheLimit() throws Exception {
        BlockingOut out = new BlockingOut();
        WsConnection ws = new WsConnection(new ByteArrayInputStream(new byte[0]), out, false, 1 << 20, new SecureRandom());
        ws.setWriteStallMillis(300);
        long start = System.nanoTime();
        try {
            ws.sendText("hello");
            fail("the write has to fail");
        } catch (IOException expected) {
            // fine
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("it took " + millis + " ms", millis >= 250 && millis < 5_000);
        assertEquals("the output was closed", 0L, out.closed.getCount());
    }

    @Test(timeout = 15_000)
    public void aWriteThatEndsInTimeIsLeftAlone() throws Exception {
        BlockingOut out = new BlockingOut();
        out.blocking.set(false);
        WsConnection ws = new WsConnection(new ByteArrayInputStream(new byte[0]), out, false, 1 << 20, new SecureRandom());
        ws.setWriteStallMillis(300);
        for (int i = 0; i < 20; i++) {
            ws.sendText("hello");
        }
        Thread.sleep(800); // the watchdog looks at nothing that is not writing
        assertEquals("not closed", 1L, out.closed.getCount());
        assertTrue(out.closed.await(0, TimeUnit.MILLISECONDS) == false);
    }
}
