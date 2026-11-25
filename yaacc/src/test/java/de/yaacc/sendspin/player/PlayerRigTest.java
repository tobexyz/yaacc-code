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
package de.yaacc.sendspin.player;

import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.multiroom.protocol.OffsetSource;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;
import de.yaacc.multiroom.protocol.WireChunkMessage;
import de.yaacc.sendspin.SendspinClientPairings;
import de.yaacc.sendspin.StringSetStore;
import de.yaacc.sendspin.hub.InboundServer;
import de.yaacc.sendspin.secure.Identity;

import org.junit.Assume;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The phone as a player against the reference server of {@code tools/sendspin-rig} (opt-in): {@code -Dsendspin.rig.player=PORT} makes the
 * test listen on that port and wait for the reference server to dial it
 * ({@code sendspin serve --demo --client ws://127.0.0.1:PORT/sendspin}); the test passes when a stream with audio arrived. Skipped without the
 * property.
 */
public class PlayerRigTest {

    @Test
    public void theReferenceServerDialsThePhoneAndStreamsDemoAudioToIt() throws Exception {
        String property = System.getProperty("sendspin.rig.player");
        Assume.assumeTrue("no -Dsendspin.rig.player=PORT", property != null && !property.isEmpty());
        final AtomicInteger headers = new AtomicInteger();
        final AtomicInteger chunks = new AtomicInteger();
        final AtomicInteger lastRate = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<String> codec = new java.util.concurrent.atomic.AtomicReference<>("");
        final Map<String, Set<String>> map = new HashMap<>();
        StringSetStore store = new StringSetStore() {
            @Override
            public Set<String> get(String key) {
                Set<String> f = map.get(key);
                return f == null ? new HashSet<String>() : new HashSet<>(f);
            }

            @Override
            public void put(String key, Set<String> values) {
                map.put(key, new HashSet<>(values));
            }
        };
        SendspinPlayerHost.Store settings = new SendspinPlayerHost.Store() {
            @Override
            public int volume() {
                return 60;
            }

            @Override
            public boolean muted() {
                return false;
            }

            @Override
            public int outputDelayMs() {
                return 0;
            }

            @Override
            public void save(int volume, boolean muted, int outputDelayMs) {
            }

            @Override
            public String lastPlaybackServer() {
                return null;
            }

            @Override
            public void setLastPlaybackServer(String serverId) {
            }
        };
        SendspinPlayerHost host = new SendspinPlayerHost(Identity.generate(new SecureRandom()), new SendspinClientPairings(store, new SecureRandom()),
                () -> true, () -> Boolean.getBoolean("sendspin.rig.flac"), "YAACC rig phone", (OffsetSource offsets, MicroClock clock) -> new SendspinPlayerHost.Engine() {
            @Override
            public SendspinAudioEngine.Target target() {
                return new SendspinAudioEngine.Target() {
                    @Override
                    public void onServerSettings(ServerSettingsMessage s) {
                    }

                    @Override
                    public void onCodecHeader(CodecHeaderMessage h) {
                        headers.incrementAndGet();
                        lastRate.set(h.getSampleRate());
                        codec.set(h.getCodec().toString());
                    }

                    @Override
                    public void onChunk(WireChunkMessage c) {
                        chunks.incrementAndGet();
                    }

                    @Override
                    public void flush() {
                    }

                    @Override
                    public void setManualLatencyMs(int ms) {
                    }
                };
            }

            @Override
            public void close() {
            }
        }, MicroClock.monotonic(), new SecureRandom(), settings, null);
        InboundServer server = new InboundServer(Integer.parseInt(property), "/sendspin", address -> true, (link, remote) -> host.accept(link), 8, 5000);
        server.start();
        try {
            System.out.println("rig player: listening on " + server.port() + ", waiting for the reference server");
            long deadline = System.nanoTime() + Long.getLong("sendspin.rig.wait", 90L) * 1_000_000_000L;
            while (chunks.get() < 200 && System.nanoTime() < deadline) {
                Thread.sleep(200);
            }
            System.out.println("rig player: headers " + headers.get() + ", chunks " + chunks.get() + ", codec " + codec.get() + ", rate " + lastRate.get() + ", server '"
                    + host.playingServerName() + "', sessions " + host.sessionCount() + " " + host.sessionPhases());
            assertTrue("a stream with audio arrived: " + chunks.get(), chunks.get() >= 200);
        } finally {
            server.close();
            host.close();
        }
    }
}
