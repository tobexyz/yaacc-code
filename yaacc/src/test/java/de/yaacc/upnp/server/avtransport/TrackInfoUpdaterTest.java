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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.StorageMedium;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.player.Player;
import de.yaacc.upnp.server.events.ObservableLastChange;

/**
 * The position info of the transport is kept up to date from the players while the transport plays, and not a
 * moment longer: nothing runs, nothing is logged and nothing is evented when no player is active, the state was
 * left or nothing changed. Before, the update chain was never stopped when the state was left by a new
 * SetAVTransportURI or the player disappeared, and logged several times a second with no player at all.
 * Plain JVM; the scheduler and the player thread are replaced, so the test decides when a tick happens.
 */
public class TrackInfoUpdaterTest {

    private static final UnsignedIntegerFourBytes ID = new UnsignedIntegerFourBytes(0);

    private final List<String> log = new ArrayList<>();
    private final AtomicInteger events = new AtomicInteger();
    private final List<Player> active = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduled;
    private AvTransport transport;
    private TrackInfoUpdater updater;
    private Player player;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        ObservableLastChange lastChange = new ObservableLastChange(new AVTransportLastChangeParser());
        lastChange.setChangeListener(events::incrementAndGet);
        transport = new AvTransport(ID, lastChange, StorageMedium.NETWORK);
        transport.setPositionInfo(new PositionInfo(1, "", "http://192.0.2.1/a.mp3"));
        scheduler = mock(ScheduledExecutorService.class);
        scheduled = mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> scheduled);
        player = mock(Player.class);
        when(player.getDuration()).thenReturn("00:03:00");
        when(player.getElapsedTime()).thenReturn("00:00:05");
        active.add(player);
        updater = newUpdater();
    }

    private TrackInfoUpdater newUpdater() {
        return new TrackInfoUpdater(transport, () -> (Collection<Player>) new ArrayList<>(active), scheduler, Runnable::run, log::add);
    }

    private List<String> positionLines() {
        List<String> lines = new ArrayList<>();
        for (String line : log) {
            if (line.startsWith("track info: ")) {
                lines.add(line);
            }
        }
        return lines;
    }

    @Test
    public void startingSchedulesOneTaskEverySecondAndTheFirstUpdateComesAtOnce() {
        updater.start(Collections.singletonList(player));

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(TrackInfoUpdater.PERIOD_MILLIS), eq(TimeUnit.MILLISECONDS));
        assertTrue(updater.isRunning());
    }

    @Test
    public void anUpdateCopiesDurationAndElapsedTimeIntoThePositionInfo() {
        updater.start(Collections.singletonList(player));

        updater.update();

        assertEquals("00:03:00", transport.getPositionInfo().getTrackDuration());
        assertEquals("00:00:05", transport.getPositionInfo().getRelTime());
        assertEquals(1, positionLines().size());
    }

    @Test
    public void startingAgainReplacesTheTaskInsteadOfAddingOne() {
        ScheduledFuture<?> first = scheduled;
        updater.start(Collections.singletonList(player));
        scheduled = mock(ScheduledFuture.class);

        updater.start(Collections.singletonList(player));

        verify(first).cancel(false);
        verify(scheduled, never()).cancel(false);
    }

    @Test
    public void stoppingCancelsTheTaskAndAnUpdateOnItsWayDoesNothing() {
        updater.start(Collections.singletonList(player));
        log.clear();

        updater.stop();
        updater.update();

        verify(scheduled).cancel(false);
        assertFalse(updater.isRunning());
        verify(player, never()).getDuration();
        assertEquals("00:00:00", transport.getPositionInfo().getRelTime());
        assertEquals(Arrays.asList("track info updates stopped"), log);
    }

    @Test
    public void anUpdaterThatWasNeverStartedDoesNothingAndLogsNothing() {
        updater.update();
        updater.update();

        verifyNoInteractions(player);
        assertTrue(log.toString(), log.isEmpty());
        assertEquals(0, events.get());
    }

    @Test
    public void whenNoPlayerIsActiveAnyMoreItStopsItselfAndSaysNothingAboutTracks() {
        updater.start(Collections.singletonList(player));
        log.clear();
        active.clear(); // the player was closed in the app

        updater.update();
        updater.update();
        updater.update();

        assertFalse(updater.isRunning());
        verify(scheduled, times(1)).cancel(false);
        verify(player, never()).getDuration();
        verify(player, never()).getElapsedTime();
        assertTrue(positionLines().toString(), positionLines().isEmpty());
        assertEquals("the position info is untouched", "00:00:00", transport.getPositionInfo().getRelTime());
        assertEquals(Arrays.asList("track info updates stopped"), log);
    }

    @Test
    public void aPlayerThatIsNotActiveIsNotAskedEvenWhenAnotherOneIs() {
        Player gone = mock(Player.class);
        updater.start(Arrays.asList(gone, player));

        updater.update();

        verify(gone, never()).getDuration();
        assertEquals("00:03:00", transport.getPositionInfo().getTrackDuration());
    }

    @Test
    public void unchangedTrackInfoIsNeitherWrittenNorLoggedAgain() {
        updater.start(Collections.singletonList(player));
        updater.update();
        log.clear();

        updater.update();
        updater.update();

        assertTrue(log.toString(), log.isEmpty());
    }

    @Test
    public void aChangedElapsedTimeIsWrittenAndLoggedOnce() {
        updater.start(Collections.singletonList(player));
        updater.update();
        log.clear();
        when(player.getElapsedTime()).thenReturn("00:00:06");

        updater.update();
        updater.update();

        assertEquals("00:00:06", transport.getPositionInfo().getRelTime());
        assertEquals(positionLines().toString(), 1, positionLines().size());
    }

    @Test
    public void aPlayerWithoutDurationYetChangesNothing() {
        when(player.getDuration()).thenReturn("");
        updater.start(Collections.singletonList(player));
        log.clear();

        updater.update();

        assertTrue(log.toString(), log.isEmpty());
        assertEquals("00:00:00", transport.getPositionInfo().getTrackDuration());
    }

    @Test
    public void thePositionNeverGoesIntoTheLastChangeSoItNeverCausesAnEvent() {
        updater.start(Collections.singletonList(player));

        for (int second = 5; second < 15; second++) {
            when(player.getElapsedTime()).thenReturn("00:00:" + (second < 10 ? "0" : "") + second);
            updater.update();
        }

        assertEquals(0, events.get());
        assertEquals("", transport.getLastChange().toString());
    }
}
