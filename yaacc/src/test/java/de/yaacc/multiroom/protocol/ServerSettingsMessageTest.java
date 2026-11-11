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
package de.yaacc.multiroom.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The clamps of the server's settings (design: bufferMs 0..10 000, latency -10 000..10 000, volume 0..100). */
public class ServerSettingsMessageTest {

    private static ServerSettingsMessage decode(String json) throws Exception {
        return (ServerSettingsMessage) SnapcastMessageCodec.decode(Bytes.frame(3, Bytes.jsonBody(json))).getMessage();
    }

    @Test
    public void valuesInsideTheRangeAreKept() throws Exception {
        ServerSettingsMessage settings = decode("{\"bufferMs\":10000,\"latency\":-10000,\"muted\":true,\"volume\":0}");

        assertEquals(10000, settings.getBufferMs());
        assertEquals(-10000, settings.getLatencyMs());
        assertTrue(settings.isMuted());
        assertEquals(0, settings.getVolume());
    }

    @Test
    public void valuesOutsideTheRangeAreClamped() throws Exception {
        ServerSettingsMessage high = decode("{\"bufferMs\":99999,\"latency\":123456,\"volume\":101}");
        ServerSettingsMessage low = decode("{\"bufferMs\":-1,\"latency\":-123456,\"volume\":-5}");
        ServerSettingsMessage huge = decode("{\"bufferMs\":1e300,\"latency\":-1e300,\"volume\":99999999999999999999}");

        assertEquals(10000, high.getBufferMs());
        assertEquals(10000, high.getLatencyMs());
        assertEquals(100, high.getVolume());
        assertEquals(0, low.getBufferMs());
        assertEquals(-10000, low.getLatencyMs());
        assertEquals(0, low.getVolume());
        assertEquals(10000, huge.getBufferMs());
        assertEquals(-10000, huge.getLatencyMs());
        assertEquals(100, huge.getVolume());
    }

    @Test
    public void missingOrWrongTypedFieldsGetTheDefaults() throws Exception {
        ServerSettingsMessage empty = decode("{}");
        ServerSettingsMessage wrong = decode("{\"bufferMs\":\"soon\",\"latency\":null,\"muted\":\"yes\",\"volume\":[1]}");

        assertEquals(1000, empty.getBufferMs());
        assertEquals(0, empty.getLatencyMs());
        assertFalse(empty.isMuted());
        assertEquals(100, empty.getVolume());
        assertEquals(empty, wrong);
    }

    @Test
    public void theConstructorClampsToo() {
        ServerSettingsMessage settings = new ServerSettingsMessage(Integer.MAX_VALUE, Integer.MIN_VALUE, false, 1000);

        assertEquals(10000, settings.getBufferMs());
        assertEquals(-10000, settings.getLatencyMs());
        assertEquals(100, settings.getVolume());
        assertEquals(100, new ClientInfoMessage(5000, false).getVolume());
        assertEquals(0, new ClientInfoMessage(-5000, false).getVolume());
    }

    @Test
    public void unknownFieldsAreIgnored() throws Exception {
        ServerSettingsMessage settings = decode("{\"bufferMs\":800,\"host\":\"203.0.113.9\",\"port\":1,\"extra\":{\"a\":[1,2]}}");

        assertEquals(800, settings.getBufferMs());
    }
}
