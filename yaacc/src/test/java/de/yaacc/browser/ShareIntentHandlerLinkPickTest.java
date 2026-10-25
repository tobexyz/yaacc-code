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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.player.PlayableItem;
import de.yaacc.player.Player;
import de.yaacc.share.LaunchNonces;
import de.yaacc.share.PendingShare;
import de.yaacc.upnp.UpnpClient;

/**
 * Shared links in the NEW layout ({@link BrowserHost#providesReceiverBar()} is true): like a shared
 * file they are never played by themselves and never fall back to this device, they become the one
 * pending share and the host opens the receiver picker. Only the confirm (with the chosen
 * receivers) sets the receivers, builds the item and plays. The classic host keeps the old
 * behavior (no picker). Android classes are mocks; the main thread is the calling thread.
 */
public class ShareIntentHandlerLinkPickTest {

    private static final String SEND = "android.intent.action.SEND";
    private static final String LINK = "https://media.example.org/stream/42.mp3";
    private static final String OTHER_LINK = "https://media.example.org/stream/43.mp3";
    private static final String FALLBACK_NAME = "Shared link";

    private Context appContext;
    private UpnpClient upnpClient;
    private PendingShare pendingShare;
    private ShareIntentHandler handler;
    private long now;

    /** Collects what the picker would be told; {@code newLayout} decides whether the host owns the picker. */
    private static final class RecordingHost implements BrowserHost {
        final boolean newLayout;
        final List<String> tokens = new CopyOnWriteArrayList<>();
        final List<String> names = new CopyOnWriteArrayList<>();
        volatile CountDownLatch latch = new CountDownLatch(1);

        RecordingHost(boolean newLayout) {
            this.newLayout = newLayout;
        }

        @Override
        public void showServers() {
        }

        @Override
        public void showContent() {
        }

        @Override
        public void showReceivers() {
        }

        @Override
        public void showPlayers() {
        }

        @Override
        public boolean providesReceiverBar() {
            return newLayout;
        }

        @Override
        public void onPendingShare(String token, String displayName) {
            tokens.add(token);
            names.add(displayName);
            latch.countDown();
        }
    }

    @Before
    public void setUp() {
        now = 0L;
        appContext = mock(Context.class);
        when(appContext.getApplicationContext()).thenReturn(appContext);
        when(appContext.getPackageName()).thenReturn("de.yaacc");
        when(appContext.getString(anyInt())).thenReturn(FALLBACK_NAME);
        upnpClient = mock(UpnpClient.class);
        when(upnpClient.isPlayerServiceInitialized()).thenReturn(true);
        // nothing selected and nothing ready: the old link path would pick this device and wait 3 s
        when(upnpClient.getReceiverDevicesReadyCount()).thenReturn(0);
        pendingShare = new PendingShare(() -> now, new java.util.Random(1)::nextBytes, 120_000L);
        handler = newHandler();
    }

    private ShareIntentHandler newHandler() {
        // launch tokens of their own: the real ones live in the Application, which a mock context lacks
        LaunchNonces nonces = new LaunchNonces(() -> now, new java.util.Random(2)::nextBytes, 30_000L, 8);
        return new ShareIntentHandler(appContext, upnpClient, Runnable::run, (data, ids) -> null, pendingShare,
                nonces, null, 2_000L, 20L, 1_000L);
    }

    @After
    public void tearDown() {
        handler.shutdown();
    }

    private static Intent linkIntent(String text) {
        Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn(SEND);
        when(intent.getType()).thenReturn("text/plain");
        when(intent.getStringExtra("android.intent.extra.TEXT")).thenReturn(text);
        return intent;
    }

    // ---- intake in the new layout

    @Test
    public void aLinkInTheNewLayoutBecomesTheOnePendingShareAndTheHostIsTold() {
        RecordingHost host = new RecordingHost(true);

        handler.handle(linkIntent(LINK), null, host);

        assertEquals(1, host.tokens.size());
        PendingShare.Data data = pendingShare.peek(host.tokens.get(0));
        assertNotNull(data);
        assertEquals(LINK, data.getUri());
    }

    @Test
    public void theLinkIsNeverShownAsTheNameOfTheShare() {
        RecordingHost host = new RecordingHost(true);

        handler.handle(linkIntent(LINK), null, host);

        assertEquals(FALLBACK_NAME, host.names.get(0));
        assertFalse(host.names.get(0).contains("example.org"));
    }

    @Test
    public void aLinkInTheNewLayoutIsNeverPlayedByItselfAndNeverFallsBackToThisDevice() throws Exception {
        RecordingHost host = new RecordingHost(true);

        handler.handle(linkIntent(LINK), null, host);

        // give a wrongly started old path time to run (it waits for receivers and then picks this device)
        Thread.sleep(400);
        verify(upnpClient, never()).setReceiverDeviceIds(any());
        verify(upnpClient, never()).createPlayableItem(nullable(Uri.class));
        verify(upnpClient, never()).initializePlayersWithPlayableItems(anyList());
    }

    @Test
    public void aSecondLinkWhileOneIsPendingIsDroppedAndNeverReplacesIt() {
        RecordingHost host = new RecordingHost(true);
        handler.handle(linkIntent(LINK), null, host);

        handler.handle(linkIntent(OTHER_LINK), null, host);

        assertEquals(1, host.tokens.size());
        assertEquals(LINK, pendingShare.peek(host.tokens.get(0)).getUri());
    }

    @Test
    public void aLinkThatIsNotHttpIsStillIgnored() {
        RecordingHost host = new RecordingHost(true);

        handler.handle(linkIntent("file:///sdcard/secret.mp3"), null, host);
        handler.handle(linkIntent("ftp://example.org/a.mp3"), null, host);

        assertTrue(host.tokens.isEmpty());
        assertFalse(pendingShare.isPending());
    }

    @Test
    public void theLinkIsConsumedFromTheIntent() {
        RecordingHost host = new RecordingHost(true);
        Intent intent = linkIntent(LINK);

        handler.handle(intent, null, host);

        verify(intent).removeExtra(Intent.EXTRA_TEXT);
    }

    // ---- confirm and cancel

    @Test
    public void confirmSetsTheChosenReceiversBeforeTheItemIsBuiltAndPlaysIt() throws Exception {
        PlayableItem item = mock(PlayableItem.class);
        Player player = mock(Player.class);
        when(upnpClient.createPlayableItem(nullable(Uri.class))).thenReturn(item);
        when(upnpClient.initializePlayersWithPlayableItems(anyList())).thenReturn(Collections.singletonList(player));
        RecordingHost host = new RecordingHost(true);
        handler.handle(linkIntent(LINK), null, host);

        handler.confirm(host.tokens.get(0), Set.of("receiver-1", "receiver-2"));

        verify(player, timeout(3_000)).play();
        InOrder order = inOrder(upnpClient);
        order.verify(upnpClient).setReceiverDeviceIds(Set.of("receiver-1", "receiver-2"));
        order.verify(upnpClient).createPlayableItem(nullable(Uri.class));
        order.verify(upnpClient).initializePlayersWithPlayableItems(anyList());
    }

    @Test
    public void confirmRunsTheHookOnceAfterThePlaybackStarted() throws Exception {
        PlayableItem item = mock(PlayableItem.class);
        Player player = mock(Player.class);
        when(upnpClient.createPlayableItem(nullable(Uri.class))).thenReturn(item);
        when(upnpClient.initializePlayersWithPlayableItems(anyList())).thenReturn(Collections.singletonList(player));
        RecordingHost host = new RecordingHost(true);
        handler.handle(linkIntent(LINK), null, host);
        CountDownLatch hook = new CountDownLatch(1);
        AtomicInteger hookRuns = new AtomicInteger();

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"), () -> {
            hookRuns.incrementAndGet();
            hook.countDown();
        });

        assertTrue(hook.await(3, TimeUnit.SECONDS));
        verify(player).play();
        assertEquals(1, hookRuns.get());
    }

    @Test
    public void aRefusedLinkStillRunsTheHookBecauseTheSelectionWasChanged() throws Exception {
        when(upnpClient.createPlayableItem(nullable(Uri.class))).thenReturn(null);
        RecordingHost host = new RecordingHost(true);
        handler.handle(linkIntent(LINK), null, host);
        CountDownLatch hook = new CountDownLatch(1);

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"), hook::countDown);

        assertTrue(hook.await(3, TimeUnit.SECONDS));
        verify(upnpClient, never()).initializePlayersWithPlayableItems(anyList());
    }

    @Test
    public void whenNoPlayerTakesTheLinkItsShareIsGivenBackAndTheHookStillRuns() throws Exception {
        PlayableItem item = mock(PlayableItem.class);
        when(upnpClient.createPlayableItem(nullable(Uri.class))).thenReturn(item);
        when(upnpClient.initializePlayersWithPlayableItems(anyList())).thenReturn(new ArrayList<Player>());
        RecordingHost host = new RecordingHost(true);
        handler.handle(linkIntent(LINK), null, host);
        CountDownLatch hook = new CountDownLatch(1);

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"), hook::countDown);

        assertTrue(hook.await(3, TimeUnit.SECONDS));
        verify(upnpClient).discardShareOf(item);
    }

    @Test
    public void confirmTakesTheLinkOnce() throws Exception {
        PlayableItem item = mock(PlayableItem.class);
        Player player = mock(Player.class);
        when(upnpClient.createPlayableItem(nullable(Uri.class))).thenReturn(item);
        when(upnpClient.initializePlayersWithPlayableItems(anyList())).thenReturn(Collections.singletonList(player));
        RecordingHost host = new RecordingHost(true);
        handler.handle(linkIntent(LINK), null, host);
        String token = host.tokens.get(0);

        handler.confirm(token, Set.of("receiver-1"));
        handler.confirm(token, Set.of("receiver-2"));

        verify(player, timeout(3_000)).play();
        Thread.sleep(300);
        verify(upnpClient).initializePlayersWithPlayableItems(anyList());
        verify(upnpClient, never()).setReceiverDeviceIds(Set.of("receiver-2"));
    }

    @Test
    public void confirmWithNoReceiverReleasesTheLinkAndPlaysNothing() throws Exception {
        RecordingHost host = new RecordingHost(true);
        handler.handle(linkIntent(LINK), null, host);

        handler.confirm(host.tokens.get(0), Collections.<String>emptySet());

        Thread.sleep(300);
        assertFalse(pendingShare.isPending());
        verify(upnpClient, never()).createPlayableItem(nullable(Uri.class));
        verify(upnpClient, never()).setReceiverDeviceIds(any());
    }

    @Test
    public void cancelReleasesTheLinkAndAConfirmWithItsTokenThenDoesNothing() throws Exception {
        RecordingHost host = new RecordingHost(true);
        handler.handle(linkIntent(LINK), null, host);
        String token = host.tokens.get(0);

        handler.cancel(token);
        handler.confirm(token, Set.of("receiver-1"));

        Thread.sleep(300);
        assertFalse(pendingShare.isPending());
        verify(upnpClient, never()).createPlayableItem(nullable(Uri.class));
    }

    @Test
    public void afterACancelANewLinkMayBeShared() {
        RecordingHost host = new RecordingHost(true);
        handler.handle(linkIntent(LINK), null, host);
        handler.cancel(host.tokens.get(0));

        handler.handle(linkIntent(OTHER_LINK), null, host);

        assertEquals(2, host.tokens.size());
        assertEquals(OTHER_LINK, pendingShare.peek(host.tokens.get(1)).getUri());
    }

    @Test
    public void aTimedOutLinkCannotBeConfirmed() throws Exception {
        RecordingHost host = new RecordingHost(true);
        handler.handle(linkIntent(LINK), null, host);
        now = 120_000L;

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"));

        Thread.sleep(300);
        verify(upnpClient, never()).createPlayableItem(nullable(Uri.class));
    }

    // ---- classic keeps the old behavior

    @Test
    public void aLinkInClassicDoesNotOpenThePicker() throws Exception {
        when(upnpClient.getReceiverDevicesReadyCount()).thenReturn(1);
        RecordingHost host = new RecordingHost(false);

        handler.handle(linkIntent(LINK), null, host);

        // the old path builds the item on its worker without asking anybody
        verify(upnpClient, timeout(3_000)).createPlayableItem(nullable(Uri.class));
        assertTrue(host.tokens.isEmpty());
        assertFalse(pendingShare.isPending());
    }

    @Test
    public void aLinkWithoutAHostKeepsTheOldBehaviorToo() throws Exception {
        when(upnpClient.getReceiverDevicesReadyCount()).thenReturn(1);

        handler.handle(linkIntent(LINK), null);

        verify(upnpClient, timeout(3_000)).createPlayableItem(nullable(Uri.class));
        assertFalse(pendingShare.isPending());
    }
}
