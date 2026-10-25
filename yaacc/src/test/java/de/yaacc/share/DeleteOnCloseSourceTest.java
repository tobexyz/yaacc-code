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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** {@link DeleteOnCloseSource}: the copy of a registered file never outlives its share. */
public class DeleteOnCloseSourceTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** Records the order of close and delete. */
    private static final class Probe extends PositionalSource {
        final File file;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean fileExistedAtClose = new AtomicBoolean();
        CountDownLatch insideRead;
        CountDownLatch releaseRead;

        Probe(File file, long size) {
            super(size);
            this.file = file;
        }

        @Override
        protected int readAtInternal(long position, byte[] buf, int off, int len) throws IOException {
            if (insideRead != null) {
                insideRead.countDown();
                try {
                    releaseRead.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            buf[off] = 42;
            return 1;
        }

        @Override
        protected void closeInternal() {
            fileExistedAtClose.set(file.exists());
            closed.set(true);
        }
    }

    @Test
    public void readsGoToTheWrappedSource() throws IOException {
        File file = temp.newFile();
        DeleteOnCloseSource source = new DeleteOnCloseSource(new Probe(file, 10), file);
        byte[] buf = new byte[4];

        assertEquals(1, source.readAt(0, buf, 0, 4));
        assertEquals(42, buf[0]);
        assertEquals(10L, source.size());
    }

    @Test
    public void closeClosesTheWrappedSourceFirstAndThenDeletesTheFile() throws IOException {
        File file = temp.newFile();
        Probe probe = new Probe(file, 10);
        DeleteOnCloseSource source = new DeleteOnCloseSource(probe, file);

        source.close();

        assertTrue(probe.closed.get());
        assertTrue("the wrapped source is closed while the file is still there", probe.fileExistedAtClose.get());
        assertFalse(file.exists());
    }

    @Test
    public void closeIsIdempotent() throws IOException {
        File file = temp.newFile();
        DeleteOnCloseSource source = new DeleteOnCloseSource(new Probe(file, 10), file);

        source.close();
        source.close();

        assertFalse(file.exists());
    }

    @Test
    public void readsAfterCloseFail() throws IOException {
        File file = temp.newFile();
        DeleteOnCloseSource source = new DeleteOnCloseSource(new Probe(file, 10), file);
        source.close();

        try {
            source.readAt(0, new byte[1], 0, 1);
            fail("a closed source must not read");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void theFileStaysWhileAReadIsRunningAndGoesWhenItEnds() throws Exception {
        File file = temp.newFile();
        Probe probe = new Probe(file, 10);
        probe.insideRead = new CountDownLatch(1);
        probe.releaseRead = new CountDownLatch(1);
        DeleteOnCloseSource source = new DeleteOnCloseSource(probe, file);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                source.readAt(0, new byte[1], 0, 1);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        reader.start();
        assertTrue(probe.insideRead.await(5, TimeUnit.SECONDS));

        source.close();

        assertTrue("a running read keeps the file", file.exists());
        probe.releaseRead.countDown();
        reader.join(5_000);
        assertEquals(null, failure.get());
        assertFalse("the last reader finished the close", file.exists());
        assertTrue(probe.closed.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMissingSourceIsRejected() {
        new DeleteOnCloseSource(null, new File("x"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMissingFileIsRejected() {
        new DeleteOnCloseSource(new Probe(new File("x"), 1), null);
    }
}
