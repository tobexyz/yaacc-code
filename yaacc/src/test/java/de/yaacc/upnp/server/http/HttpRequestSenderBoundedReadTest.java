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
package de.yaacc.upnp.server.http;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

/** The body of an answer of another device is read up to a limit and a deadline. */
public class HttpRequestSenderBoundedReadTest {

    @Test
    public void aBodyWithinTheLimitIsReadAndNoBodyIsNull() throws IOException {
        byte[] body = "<a/>".getBytes();

        assertArrayEquals(body, HttpRequestSender.readBounded(new ByteArrayEntity(body, ContentType.TEXT_XML), 100, 1000));
        assertNull(HttpRequestSender.readBounded(null, 100, 1000));
    }

    @Test
    public void aBodyThatDeclaresMoreThanTheLimitIsRefusedBeforeReading() {
        try {
            HttpRequestSender.readBounded(new ByteArrayEntity(new byte[101], ContentType.TEXT_XML), 100, 1000);
            fail();
        } catch (IOException expected) {
            // too long
        }
    }

    @Test
    public void aBodyThatNeverEndsIsStoppedAtTheLimit() {
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return len;
            }
        };
        try {
            HttpRequestSender.readBounded(new InputStreamEntity(endless, -1, ContentType.TEXT_XML), 100_000, 60_000);
            fail();
        } catch (IOException expected) {
            // too long
        }
    }

    @Test
    public void aBodyThatDripsInSlowlyIsStoppedAtTheDeadline() {
        InputStream slow = new InputStream() {
            @Override
            public int read() throws IOException {
                return read(new byte[1], 0, 1);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                b[off] = 1;
                return 1;
            }
        };
        long start = System.currentTimeMillis();
        try {
            HttpRequestSender.readBounded(new InputStreamEntity(slow, -1, ContentType.TEXT_XML), 1_000_000, 100);
            fail();
        } catch (IOException expected) {
            // too slow
        }
        org.junit.Assert.assertTrue(System.currentTimeMillis() - start < 5000);
    }
}
