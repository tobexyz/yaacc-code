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
import static org.junit.Assert.fail;

import org.junit.Test;

/** The tcp stream sources that the control port accepts (rule S4): only what the server can serve, nothing that starts a program or opens a file. */
public class StreamSpecTest {

    private static void refused(String uri) {
        try {
            StreamSpec.parse(uri);
            fail("expected a refusal: " + uri);
        } catch (ServerState.StateException e) {
            assertEquals(-32602, e.code());
        }
    }

    @Test
    public void aTcpSourceWithOnlyANameGetsTheDefaults() {
        StreamSpec s = StreamSpec.parse("tcp://0.0.0.0:4953?name=YAACC%20one");
        assertEquals("YAACC one", s.name());
        assertEquals(4953, s.port());
        assertEquals(48000, s.sampleRate());
        assertEquals(16, s.bitsPerSample());
        assertEquals(2, s.channels());
        assertEquals("pcm", s.codec());
    }

    @Test
    public void theSampleFormatAndThePortAreRead() {
        StreamSpec s = StreamSpec.parse("tcp://127.0.0.1:4955?name=a&sampleformat=44100:24:1");
        assertEquals(4955, s.port());
        assertEquals(44100, s.sampleRate());
        assertEquals(24, s.bitsPerSample());
        assertEquals(1, s.channels());
        assertEquals(4957, StreamSpec.parse("tcp://0.0.0.0?name=a&port=4957").port());
    }

    @Test
    public void everyOtherKindOfSourceIsRefused() {
        refused("pipe:///tmp/snapfifo?name=a");
        refused("file:///sdcard/a.wav?name=a");
        refused("process:///bin/sh?name=a");
        refused("alsa://?name=a");
        refused("librespot:///usr/bin/librespot?name=a");
        refused("http://example.org/?name=a");
        refused("not a uri");
        refused("");
    }

    @Test
    public void aStreamNeedsAName() {
        refused("tcp://0.0.0.0:4953");
        refused("tcp://0.0.0.0:4953?name=");
        refused("tcp://0.0.0.0:4953?name=" + "x".repeat(65));
    }

    @Test
    public void aPathOrACommandInAParameterIsRefused() {
        refused("tcp://0.0.0.0:4953/etc/passwd?name=a");
        refused("tcp://0.0.0.0:4953?name=a&controlscript=/system/bin/sh");
        refused("tcp://0.0.0.0:4953?name=a&params=--evil");
        refused("tcp://0.0.0.0:4953?name=a%00b");
    }

    @Test
    public void thePortIsOneOfTheFeederPorts() {
        refused("tcp://0.0.0.0:80?name=a");
        refused("tcp://0.0.0.0:4963?name=a");
        refused("tcp://0.0.0.0:0?name=a");
        refused("tcp://0.0.0.0:abc?name=a");
        StreamSpec.parse("tcp://0.0.0.0:4962?name=a");
    }

    @Test
    public void clientModeIsNotSupported() {
        refused("tcp://10.0.0.5:4953?name=a&mode=client");
        StreamSpec.parse("tcp://0.0.0.0:4953?name=a&mode=server");
    }

    @Test
    public void aSampleFormatThatTheServerCannotChunkIsRefused() {
        refused("tcp://0.0.0.0:4953?name=a&sampleformat=44100:12:2");
        refused("tcp://0.0.0.0:4953?name=a&sampleformat=100:16:2");
        refused("tcp://0.0.0.0:4953?name=a&sampleformat=48000:16:9");
        refused("tcp://0.0.0.0:4953?name=a&sampleformat=48000:16");
        refused("tcp://0.0.0.0:4953?name=a&sampleformat=a:b:c");
    }

    @Test
    public void onlyPcmAndFlacAreCodecs() {
        refused("tcp://0.0.0.0:4953?name=a&codec=opus");
        refused("tcp://0.0.0.0:4953?name=a&codec=ogg");
        assertEquals("flac", StreamSpec.parse("tcp://0.0.0.0:4953?name=a&codec=flac").codec());
    }
}
