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
package de.yaacc.fcast.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.yaacc.multiroom.control.MonotonicClock;

/**
 * YAACC's session against the test rig of {@code tools/fcast-rig} (a separate process, written in Python from the protocol documents):
 * the check that the hand-written Java layer and an independent implementation understand each other over real TLS 1.3. Skipped unless
 * the environment names a running rig: {@code python fcast_rig.py --speaks 4 --port 46899 --no-advertise}, then
 * {@code FCAST_RIG_PORT=46899 FCAST_RIG_FP=<the fingerprint it prints> ./gradlew :yaacc:testDebugUnitTest --tests '*FCastRigV4ManualTest'}.
 */
public class FCastRigV4ManualTest {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "rig-manual-test");
        thread.setDaemon(true);
        return thread;
    });
    private FCastSession session;

    @After
    public void tearDown() {
        if (session != null) {
            session.close();
        }
        executor.shutdownNow();
    }

    private interface Check {
        boolean test(FCastReceiverState state);
    }

    private boolean await(Check check) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            if (check.test(session.getState())) {
                return true;
            }
            Thread.sleep(20);
        }
        return check.test(session.getState());
    }

    private FCastSession open(String fingerprint, int port) {
        FCastConnection.Config config = new FCastConnection.Config();
        config.connectTimeoutMillis = 3_000;
        config.pin = FCastPin.parse(fingerprint, FCastPin.Source.LINK);
        config.platformSdk = 34;
        return new FCastSession(FCastSession.connectorFor(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), config,
                MonotonicClock.SYSTEM, "YAACC test", "YAACC", "test"), new FCastSession.Listener() {
            @Override
            public void onStateChanged(FCastReceiverState state) {
            }
        }, new FCastSession.Config(), MonotonicClock.SYSTEM, executor);
    }

    @Test
    public void theSessionPlaysSeeksAndSelectsTracksOnTheRig() throws Exception {
        String port = System.getenv("FCAST_RIG_PORT");
        String fingerprint = System.getenv("FCAST_RIG_FP");
        assumeTrue("no rig named (FCAST_RIG_PORT, FCAST_RIG_FP)", port != null && fingerprint != null);
        session = open(fingerprint, Integer.parseInt(port));
        session.acquire();

        session.connect();
        assertTrue(await(s -> s.getLink() == FCastReceiverState.Link.CONNECTED && s.getProtocolVersion() == 4));
        assertEquals(FCastPin.Source.LINK, session.getState().getPinSource());
        assertTrue(await(s -> "yaacc-test-rig".equals(s.getReceiverApp())));
        assertNotNull(session.getState().getCapabilities());

        session.play(new FCastMessages.PlayRequest("audio/mpeg", "http://192.168.0.5:8080/My Song.mp3").title("Song"));
        assertTrue(await(s -> s.getPlayback() == FCastReceiverState.Playback.PLAYING && s.getPosition() != null));
        assertEquals("http://192.168.0.5:8080/My Song.mp3", session.getState().getItemUrl());
        assertTrue(await(s -> s.getTracks().size() == 4 && Long.valueOf(1).equals(s.getSelectedAudio())));

        session.pause();
        assertTrue(await(s -> s.getPlayback() == FCastReceiverState.Playback.PAUSED));
        session.resume();
        assertTrue(await(s -> s.getPlayback() == FCastReceiverState.Playback.PLAYING));
        session.seek(60);
        assertTrue(await(s -> s.getPosition() != null && s.getPosition() >= 59));
        session.setVolume(0.25);
        assertTrue(await(s -> s.getVolume() != null && Math.abs(s.getVolume() - 0.25) < 1e-6));
        session.changeTrack(FCastTrack.Type.AUDIO, 2L);
        assertTrue(await(s -> Long.valueOf(2).equals(s.getSelectedAudio())));
        session.addSubtitle("http://192.168.0.5:8080/a.srt", true, "English (file)");
        assertTrue(await(s -> s.getTracks().size() == 5 && Long.valueOf(5).equals(s.getSelectedSubtitle())));
        session.stop();
        assertTrue(await(s -> s.getPlayback() == FCastReceiverState.Playback.IDLE));
    }

    @Test
    public void aWrongKeyIsRefusedByTheSessionAndNoFallbackHappens() throws Exception {
        String port = System.getenv("FCAST_RIG_PORT");
        assumeTrue("no rig named (FCAST_RIG_PORT)", port != null);
        session = open(java.util.Base64.getEncoder().encodeToString(new byte[32]), Integer.parseInt(port));
        session.acquire();

        session.connect();

        assertTrue(await(s -> s.getLink() == FCastReceiverState.Link.FAILED && s.getLastError() != null));
        assertEquals(0, session.getState().getProtocolVersion());
    }
}
