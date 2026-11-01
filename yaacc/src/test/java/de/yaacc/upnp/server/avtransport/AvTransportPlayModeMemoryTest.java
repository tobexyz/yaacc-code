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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import de.yaacc.player.PlayMode;
import de.yaacc.player.Player;

/**
 * The play mode a renderer transport remembers: a mode chosen by Home Assistant or by the buttons of a player
 * stays while a player of the transport exists (also across the replacement of the player by the next item), and
 * is forgotten when all the players are gone, so the next playback starts with the settings again. Run through
 * the real receiving path of the app ({@link RendererHarness}). Plain JVM.
 */
public class AvTransportPlayModeMemoryTest {

    private static final String URI_A = "http://192.0.2.1/a.mp3";
    private static final String URI_B = "http://192.0.2.1/b.mp3";

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

    private static String reported(RendererHarness h) {
        String body = h.soap("GetTransportSettings", "InstanceID", "0").getBodyString();
        int from = body.indexOf("<PlayMode>") + "<PlayMode>".length();
        return body.substring(from, body.indexOf("</PlayMode>"));
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("not within 5 s: " + what);
            }
            Thread.sleep(10);
        }
    }

    private static boolean anyEventCarries(RendererHarness h, String mode) {
        for (String event : h.sentEvents) {
            if (event.replace("&quot;", "\"").contains("CurrentPlayMode val=\"" + mode + "\"")) {
                return true;
            }
        }
        return false;
    }

    /** HA: Stop, SetAVTransportURI, Play - what {@code media_player.play_media} sends. */
    private static void homeAssistantPlays(RendererHarness h, String uri) {
        assertTrue(RendererHarness.succeeded(h.stop()));
        assertTrue(RendererHarness.succeeded(h.setAvTransportUri(uri)));
        assertTrue(RendererHarness.succeeded(h.play()));
    }

    // --- the mode stays while a player exists

    @Test
    public void aModeSetByHomeAssistantIsGivenToThePlayerOfTheNextItem() throws Exception {
        RendererHarness h = renderer();
        homeAssistantPlays(h, URI_A);
        h.setPlayMode("SHUFFLE");
        Player first = h.players.get(0);
        assertEquals(PlayMode.fromUpnpPlayMode("SHUFFLE"), first.getPlayMode());

        // each item of Home Assistant is a new player: the one that plays is shut down, the new one created
        homeAssistantPlays(h, URI_B);

        assertEquals(1, h.players.size());
        assertFalse(first == h.players.get(0));
        assertEquals(PlayMode.fromUpnpPlayMode("SHUFFLE"), h.players.get(0).getPlayMode());
        assertEquals("SHUFFLE", reported(h));
    }

    @Test
    public void theNoticeThatTheOldPlayerWasRemovedMayComeAfterTheNewOneIsAttached() throws Exception {
        RendererHarness h = renderer();
        h.removalNoticeDuringStart = false;
        homeAssistantPlays(h, URI_A);
        h.setPlayMode("REPEAT_ALL");
        Player old = h.players.get(0);

        homeAssistantPlays(h, URI_B);
        h.closePlayer(old); // the main thread delivers the notice late (the old one is not in the list any more)

        assertEquals(PlayMode.fromUpnpPlayMode("REPEAT_ALL"), h.players.get(0).getPlayMode());
        assertEquals("REPEAT_ALL", reported(h));
    }

    @Test
    public void aModeChosenWithTheButtonsOfThePlayerStaysForTheNextPlayer() throws Exception {
        RendererHarness h = renderer();
        homeAssistantPlays(h, URI_A);
        h.players.get(0).setPlayMode(PlayMode.of(PlayMode.RepeatMode.ONE, false));
        assertEquals("REPEAT_ONE", reported(h));

        homeAssistantPlays(h, URI_B);

        assertEquals(PlayMode.of(PlayMode.RepeatMode.ONE, false), h.players.get(0).getPlayMode());
    }

    @Test
    public void aModeSetBeforeAnyPlayerExistsStaysForTheFirstPlayer() throws Exception {
        RendererHarness h = renderer();
        h.setPlayMode("RANDOM");
        h.closePlayer(RendererHarness.fakePlayer(PlayMode.NORMAL)); // some other player of the app goes away

        homeAssistantPlays(h, URI_A);

        assertEquals(PlayMode.fromUpnpPlayMode("RANDOM"), h.players.get(0).getPlayMode());
    }

    @Test
    public void theModeStaysWhileAnotherPlayerOfTheTransportIsLeft() throws Exception {
        RendererHarness h = renderer();
        homeAssistantPlays(h, URI_A);
        Player second = h.addPlayer(PlayMode.NORMAL);
        h.transport().attachPlayers(List.of(second));
        h.setPlayMode("SHUFFLE");

        h.closePlayer(h.players.get(0));

        assertEquals("SHUFFLE", reported(h));
    }

    @Test
    public void thePlayerOfAnotherTransportEndingChangesNothing() throws Exception {
        RendererHarness h = renderer();
        homeAssistantPlays(h, URI_A);
        h.setPlayMode("SHUFFLE");

        h.closePlayer(RendererHarness.fakePlayer(PlayMode.NORMAL));

        assertEquals("SHUFFLE", reported(h));
    }

    // --- the mode is forgotten when all players are gone

    @Test
    public void whenAllPlayersAreGoneTheNextPlaybackStartsWithTheSettings() throws Exception {
        RendererHarness h = renderer();
        h.settings(false, false);
        homeAssistantPlays(h, URI_A);
        h.setPlayMode("SHUFFLE");
        assertEquals("SHUFFLE", reported(h));

        h.closePlayer(h.players.get(0)); // closed in the app

        assertEquals("the settings give NORMAL", "NORMAL", reported(h));
        homeAssistantPlays(h, URI_B);
        assertEquals(PlayMode.NORMAL, h.players.get(0).getPlayMode());
        assertEquals("NORMAL", reported(h));
    }

    @Test
    public void theModeOfTheSettingsIsTheOneThatComesBack() throws Exception {
        RendererHarness h = renderer();
        h.settings(true, true); // repeat playlist and shuffle music
        homeAssistantPlays(h, URI_A);
        h.setPlayMode("NORMAL");
        assertEquals("NORMAL", reported(h));

        h.closePlayer(h.players.get(0));

        assertEquals("RANDOM", reported(h));
        homeAssistantPlays(h, URI_B);
        assertEquals(PlayMode.of(PlayMode.RepeatMode.ALL, true), h.players.get(0).getPlayMode());
    }

    @Test
    public void whenThePlayerIsGoneAHomeAssistantThatSubscribedIsToldTheModeOfTheSettings() throws Exception {
        RendererHarness h = renderer();
        h.settings(false, false);
        homeAssistantPlays(h, URI_A);
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> !h.sentEvents.isEmpty());
        h.setPlayMode("SHUFFLE");
        await("SHUFFLE", () -> anyEventCarries(h, "SHUFFLE"));
        int before = h.sentEvents.size();

        h.closePlayer(h.players.get(0));

        await("NORMAL", () -> h.sentEvents.size() > before && anyEventCarries(h, "NORMAL"));
    }

    @Test
    public void nothingIsSentWhenTheControlPointAlreadySawTheModeOfTheSettings() throws Exception {
        RendererHarness h = renderer();
        h.settings(false, false);
        homeAssistantPlays(h, URI_A);
        assertEquals("NORMAL", reported(h));
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> !h.sentEvents.isEmpty());
        Thread.sleep(400);
        int before = h.sentEvents.size();

        h.closePlayer(h.players.get(0));

        Thread.sleep(700);
        assertEquals(before, h.sentEvents.size());
    }

    @Test
    public void aSecondRoundAlsoForgets() throws Exception {
        RendererHarness h = renderer();
        h.settings(false, false);
        for (int round = 0; round < 3; round++) {
            homeAssistantPlays(h, URI_A);
            h.setPlayMode("REPEAT_ONE");
            assertEquals("REPEAT_ONE", reported(h));

            h.closePlayer(h.players.get(0));

            assertEquals("round " + round, "NORMAL", reported(h));
        }
    }

    @Test
    public void whenTheNewPlayerCannotBeCreatedTheModeIsForgotten() throws Exception {
        RendererHarness h = renderer();
        h.settings(false, false);
        homeAssistantPlays(h, URI_A);
        h.setPlayMode("SHUFFLE");
        h.newPlayerCannotBeCreated = true;

        homeAssistantPlays(h, URI_B); // the old player is shut down, there is no new one

        assertTrue(h.players.isEmpty());
        assertEquals("NORMAL", reported(h));
    }

    @Test
    public void aModeSetAfterThePlayersWereGoneIsRemembered() throws Exception {
        RendererHarness h = renderer();
        h.settings(false, false);
        homeAssistantPlays(h, URI_A);
        h.setPlayMode("SHUFFLE");
        h.closePlayer(h.players.get(0));
        assertEquals("NORMAL", reported(h));

        h.setPlayMode("REPEAT_ALL"); // set while nothing plays: kept for the next player

        homeAssistantPlays(h, URI_B);
        assertEquals(PlayMode.fromUpnpPlayMode("REPEAT_ALL"), h.players.get(0).getPlayMode());
    }

    @Test
    public void theTransportStopsListeningWhenItHasNoPlayerAndListensAgainForTheNext() throws Exception {
        RendererHarness h = renderer();
        homeAssistantPlays(h, URI_A);
        assertEquals(1, h.playerListListeners.size());

        h.closePlayer(h.players.get(0));
        assertEquals(0, h.playerListListeners.size());

        homeAssistantPlays(h, URI_B);
        assertEquals(1, h.playerListListeners.size());
    }
}
