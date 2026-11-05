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
package de.yaacc.upnp.server.avtransport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.player.Player;

/**
 * Issue 296: Home Assistant has one play / pause button; clicked fast, a second Pause meets the paused renderer. A command that
 * asks for the state the renderer is in already is answered successfully and changes nothing (before: UPnP error 701). Run
 * through the real receiving path ({@link RendererHarness}). Plain JVM.
 */
public class AvTransportFastClicksTest {

    private static final String URI = "http://192.0.2.1/music.mp3";

    private final List<RendererHarness> harnesses = new ArrayList<>();

    @After
    public void tearDown() {
        for (RendererHarness harness : harnesses) {
            harness.close();
        }
    }

    private RendererHarness renderer() {
        RendererHarness harness = new RendererHarness(service -> new AvTransportServiceManager(service, 2000));
        harnesses.add(harness);
        return harness;
    }

    private static String transportState(RendererHarness h) {
        String body = h.soap("GetTransportInfo", "InstanceID", "0").getBodyString();
        int from = body.indexOf("<CurrentTransportState>") + "<CurrentTransportState>".length();
        return body.substring(from, body.indexOf("</CurrentTransportState>"));
    }

    private static void ok(String what, RendererHarness h, org.fourthline.cling.model.message.StreamResponseMessage answer) {
        assertTrue(what + ": " + RendererHarness.describe(answer), RendererHarness.succeeded(answer));
    }

    private RendererHarness playing() {
        RendererHarness h = renderer();
        ok("set uri", h, h.setAvTransportUri(URI));
        ok("play", h, h.play());
        assertEquals("PLAYING", transportState(h));
        return h;
    }

    @Test
    public void aSecondPauseWhilePausedIsAnsweredAndPausesThePlayerOnlyOnce() {
        RendererHarness h = playing();
        Player player = h.players.get(0);

        ok("first pause", h, h.pause());
        ok("second pause", h, h.pause());
        ok("third pause", h, h.pause());

        assertEquals("PAUSED_PLAYBACK", transportState(h));
        verify(player, times(1)).pause();
    }

    @Test
    public void fastAlternatingPlayAndPauseNeverFails() {
        RendererHarness h = playing();
        String[] clicks = {"pause", "pause", "play", "pause", "pause", "pause", "play", "play", "pause", "play"};
        for (String click : clicks) {
            ok(click, h, click.equals("pause") ? h.pause() : h.play());
        }

        assertEquals("PLAYING", transportState(h));
    }

    @Test
    public void seekWhilePausedMovesThePlayersAndKeepsThePause() {
        RendererHarness h = playing();
        Player player = h.players.get(0);
        ok("pause", h, h.pause());

        ok("seek", h, h.soap("Seek", "InstanceID", "0", "Unit", "REL_TIME", "Target", "00:00:20"));

        assertEquals("PAUSED_PLAYBACK", transportState(h));
        verify(player).seekTo(20_000L);
        verify(player, times(1)).play(); // the start only, the seek does not resume
    }

    @Test
    public void seekWithATargetThatCannotBeReadWhilePausedChangesNothing() {
        RendererHarness h = playing();
        Player player = h.players.get(0);
        ok("pause", h, h.pause());

        ok("seek", h, h.soap("Seek", "InstanceID", "0", "Unit", "REL_TIME", "Target", "soon"));

        assertEquals("PAUSED_PLAYBACK", transportState(h));
        verify(player, never()).seekTo(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    public void nextAndPreviousWhilePausedAreAnsweredAndDoNothing() {
        RendererHarness h = playing();
        ok("pause", h, h.pause());

        ok("next", h, h.soap("Next", "InstanceID", "0"));
        ok("previous", h, h.soap("Previous", "InstanceID", "0"));

        assertEquals("PAUSED_PLAYBACK", transportState(h));
    }

    @Test
    public void pauseWhileStoppedOrIdleIsAnsweredAndChangesNothing() {
        RendererHarness idle = renderer();
        ok("pause when idle", idle, idle.pause());
        assertEquals("NO_MEDIA_PRESENT", transportState(idle));

        RendererHarness stopped = renderer();
        ok("set uri", stopped, stopped.setAvTransportUri(URI));
        ok("stop", stopped, stopped.stop());
        ok("pause when stopped", stopped, stopped.pause());
        assertEquals("STOPPED", transportState(stopped));
    }

    @Test
    public void thePossibleActionsOfThePausedStateAreAllAnswered() {
        RendererHarness h = playing();
        ok("pause", h, h.pause());

        ok("pause", h, h.pause());
        ok("play", h, h.play());
        ok("pause again", h, h.pause());
        ok("stop", h, h.stop());
        assertEquals("STOPPED", transportState(h));
    }
}
