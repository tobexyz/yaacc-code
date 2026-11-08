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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import androidx.fragment.app.FragmentActivity;

import org.fourthline.cling.support.model.DIDLObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.player.Player;
import de.yaacc.share.PickedAction;
import de.yaacc.upnp.UpnpClient;

/**
 * The application points of the picked selection in {@link ContentListFragment}: the new layout
 * clears the receiver selection once the players have been started (play, play-all) or the item was
 * added to the playlists, also when that fails; classic never clears; the picked action stores the
 * chosen receivers only after the confirm, and ends its single flight even when it cannot run.
 * The fragment is a spy without a view; the client and the host are mocks.
 */
public class ContentListFragmentPickedSelectionTest {

    private UpnpClient client;
    private ContentListFragment fragment;
    private DIDLObject object;

    @Before
    public void setUp() {
        client = mock(UpnpClient.class);
        fragment = spy(new ContentListFragment());
        object = mock(DIDLObject.class);
    }

    // ---- play: clear after the start (new layout only)

    @Test
    public void thePlayersAreStartedAndTheSelectionIsClearedInTheNewLayout() {
        Player first = mock(Player.class);
        Player second = mock(Player.class);
        when(client.initializePlayers(any(DIDLObject.class))).thenReturn(Arrays.asList(first, null, second));
        fragment.attachForTest(client, true);

        fragment.playItem(object);

        InOrder order = inOrder(first, second, client);
        order.verify(first).play();
        order.verify(second).play();
        order.verify(client).clearReceiverDevices();
        verify(client, times(1)).clearReceiverDevices();
    }

    @Test
    public void theSelectionIsClearedEvenWhenNoPlayerCouldBeCreated() {
        when(client.initializePlayers(any(DIDLObject.class))).thenReturn(Collections.<Player>emptyList());
        fragment.attachForTest(client, true);

        fragment.playItem(object);

        verify(client).clearReceiverDevices();
    }

    @Test
    public void theSelectionIsClearedEvenWhenAPlayerFailsToPlay() {
        Player broken = mock(Player.class);
        doThrow(new IllegalStateException("boom")).when(broken).play();
        when(client.initializePlayers(any(DIDLObject.class))).thenReturn(Collections.singletonList(broken));
        fragment.attachForTest(client, true);

        try {
            fragment.playItem(object);
            fail("the failure is not swallowed");
        } catch (IllegalStateException expected) {
            // ok
        }

        verify(client).clearReceiverDevices();
    }

    @Test
    public void classicPlaysWithoutTouchingTheSelection() {
        Player player = mock(Player.class);
        when(client.initializePlayers(any(DIDLObject.class))).thenReturn(Collections.singletonList(player));
        fragment.attachForTest(client, false);

        fragment.playItem(object);

        verify(player).play();
        verify(client, never()).clearReceiverDevices();
        verify(client, never()).setReceiverDeviceIds(any());
    }

    // ---- add to playlist

    @Test
    public void addToPlaylistClearsTheSelectionAfterwardsInTheNewLayout() {
        fragment.attachForTest(client, true);

        fragment.addToPlayList(object);

        InOrder order = inOrder(client);
        order.verify(client).addToPlaylist(object);
        order.verify(client).clearReceiverDevices();
    }

    @Test
    public void addToPlaylistClearsTheSelectionEvenWhenItFails() {
        doThrow(new IllegalStateException("boom")).when(client).addToPlaylist(object);
        fragment.attachForTest(client, true);

        try {
            fragment.addToPlayList(object);
            fail("the failure is not swallowed");
        } catch (IllegalStateException expected) {
            // ok
        }

        verify(client).clearReceiverDevices();
    }

    @Test
    public void addToPlaylistInClassicKeepsTheSelection() {
        fragment.attachForTest(client, false);

        fragment.addToPlayList(object);

        verify(client).addToPlaylist(object);
        verify(client, never()).clearReceiverDevices();
    }

    // ---- startPlayAction

    /** A host activity: a FragmentActivity that also is a BrowserHost, as the browser activities are. */
    private FragmentActivity hostActivity(boolean providesReceiverBar, boolean pickerOpens) {
        FragmentActivity activity = mock(FragmentActivity.class, withSettings().extraInterfaces(BrowserHost.class));
        BrowserHost host = (BrowserHost) activity;
        when(host.providesReceiverBar()).thenReturn(providesReceiverBar);
        when(host.pickReceivers(any(), any(), any())).thenReturn(pickerOpens);
        doReturn(activity).when(fragment).getActivity();
        doReturn(true).when(fragment).isAdded();
        return activity;
    }

    private static PickedAction capturePick(FragmentActivity activity) {
        ArgumentCaptor<PickedAction> captor = ArgumentCaptor.forClass(PickedAction.class);
        verify((BrowserHost) activity).pickReceivers(captor.capture(), eq(null), eq(null));
        return captor.getValue();
    }

    @Test
    public void classicStartsAtOnceWithoutAskingForReceivers() {
        FragmentActivity activity = hostActivity(false, true);
        fragment.attachForTest(client, false);
        AtomicInteger started = new AtomicInteger();

        fragment.startPlayAction(object, ContentItemPlayTask.PLAY_ALL, started::incrementAndGet, null);

        assertEquals(1, started.get());
        verify((BrowserHost) activity, never()).pickReceivers(any(), any(), any());
        verify(client, never()).setReceiverDeviceIds(any());
    }

    @Test
    public void theNewLayoutAsksFirstAndStartsNothingBeforeTheConfirm() {
        FragmentActivity activity = hostActivity(true, true);
        fragment.attachForTest(client, true);
        AtomicInteger started = new AtomicInteger();

        fragment.startPlayAction(object, ContentItemPlayTask.PLAY_ALL, started::incrementAndGet, null);

        assertNotNull(capturePick(activity));
        assertEquals(0, started.get());
        verify(client, never()).setReceiverDeviceIds(any());
    }

    @Test
    public void aConfirmStoresTheChosenReceiversThenStarts() {
        FragmentActivity activity = hostActivity(true, true);
        fragment.attachForTest(client, true);
        List<String> order = new ArrayList<>();
        doAnswer(invocation -> {
            order.add("stored");
            return null;
        }).when(client).setReceiverDeviceIds(any());

        fragment.startPlayAction(object, ContentItemPlayTask.PLAY_ALL, () -> order.add("started"), null);
        AtomicInteger done = new AtomicInteger();
        capturePick(activity).onConfirmed(Set.of("tv"), done::incrementAndGet);

        assertEquals(Arrays.asList("stored", "started"), order);
        verify(client).setReceiverDeviceIds(Set.of("tv"));
        // the flight ends with the task, which cannot run on the JVM: not yet
        assertEquals(0, done.get());
    }

    @Test
    public void aCancelChangesNothing() {
        FragmentActivity activity = hostActivity(true, true);
        fragment.attachForTest(client, true);
        AtomicInteger started = new AtomicInteger();

        fragment.startPlayAction(object, ContentItemPlayTask.PLAY_ALL, started::incrementAndGet, null);
        capturePick(activity).onCancelled();

        assertEquals(0, started.get());
        verify(client, never()).setReceiverDeviceIds(any());
        verify(client, never()).clearReceiverDevices();
    }

    @Test
    public void aConfirmAfterTheScreenIsGoneStoresNothingAndEndsTheFlight() {
        FragmentActivity activity = hostActivity(true, true);
        fragment.attachForTest(client, true);
        AtomicInteger started = new AtomicInteger();
        fragment.startPlayAction(object, ContentItemPlayTask.PLAY_ALL, started::incrementAndGet, null);
        PickedAction action = capturePick(activity);
        doReturn(false).when(fragment).isAdded();
        AtomicInteger done = new AtomicInteger();

        action.onConfirmed(Set.of("tv"), done::incrementAndGet);

        assertEquals(1, done.get());
        assertEquals(0, started.get());
        verify(client, never()).setReceiverDeviceIds(any());
    }

    @Test
    public void aFailingStartEndsTheFlightAndReportsTheFailure() {
        FragmentActivity activity = hostActivity(true, true);
        fragment.attachForTest(client, true);
        fragment.startPlayAction(object, ContentItemPlayTask.PLAY_ALL, () -> {
            throw new IllegalStateException("boom");
        }, null);
        AtomicInteger done = new AtomicInteger();

        try {
            capturePick(activity).onConfirmed(Set.of("tv"), done::incrementAndGet);
            fail("expected the failure");
        } catch (IllegalStateException expected) {
            // ok
        }

        assertEquals(1, done.get());
    }

    @Test
    public void whenThePickerCannotOpenNothingIsStartedAndNothingStored() {
        hostActivity(true, false);
        fragment.attachForTest(client, true);
        AtomicInteger started = new AtomicInteger();

        fragment.startPlayAction(object, ContentItemPlayTask.PLAY_ALL, started::incrementAndGet, null);

        assertEquals(0, started.get());
        verify(client, never()).setReceiverDeviceIds(any());
    }

    @Test
    public void aNullObjectIsIgnored() {
        FragmentActivity activity = hostActivity(true, true);
        fragment.attachForTest(client, true);

        fragment.startPlayAction(null, ContentItemPlayTask.PLAY_ALL, null, null);

        verify((BrowserHost) activity, never()).pickReceivers(any(), any(), any());
    }
}
