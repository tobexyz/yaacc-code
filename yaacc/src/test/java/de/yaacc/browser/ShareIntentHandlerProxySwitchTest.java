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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.yaacc.R;
import de.yaacc.share.LaunchNonces;
import de.yaacc.share.PendingShare;
import de.yaacc.share.ProxyAvailability;
import de.yaacc.upnp.UpnpClient;

/**
 * Task 12.21: a shared link with the media proxy ticked but the master switch "UPnP Server" off is refused, and the user is
 * told why (a message that names the switch), instead of the generic "could not play". The refusal comes before any network
 * work (no item is built). Android classes are mocks; the main thread is the calling thread.
 */
public class ShareIntentHandlerProxySwitchTest {

    private static final String SEND = "android.intent.action.SEND";
    private static final String LINK = "https://media.example.org/stream/42.mp3";

    private Context appContext;
    private UpnpClient upnpClient;
    private PendingShare pendingShare;
    private ShareIntentHandler handler;
    private MockedStatic<Toast> toastStatic;
    private final List<Integer> toasts = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    /** The "main thread": the static Toast mock lives on the test thread only, so the test thread runs what is posted. */
    private final BlockingQueue<Runnable> mainThread = new LinkedBlockingQueue<>();

    private void runMainThreadUntil(BooleanSupplier done) throws InterruptedException {
        long end = System.currentTimeMillis() + 3_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < end) {
            Runnable next = mainThread.poll(20, TimeUnit.MILLISECONDS);
            if (next != null) {
                next.run();
            }
        }
    }

    @Before
    public void setUp() {
        toastStatic = mockStatic(Toast.class);
        toastStatic.when(() -> Toast.makeText(any(Context.class), anyInt(), anyInt())).thenAnswer(invocation -> {
            toasts.add(invocation.getArgument(1));
            return mock(Toast.class);
        });
        appContext = mock(Context.class);
        when(appContext.getApplicationContext()).thenReturn(appContext);
        when(appContext.getPackageName()).thenReturn("de.yaacc");
        when(appContext.getString(anyInt())).thenReturn("text");
        when(appContext.getString(eq(R.string.share_link_host), any())).thenReturn("Link: host");
        upnpClient = mock(UpnpClient.class);
        when(upnpClient.isPlayerServiceInitialized()).thenReturn(true);
        when(upnpClient.getReceiverDevicesReadyCount()).thenReturn(1);
        pendingShare = new PendingShare(() -> 0L, new java.util.Random(1)::nextBytes, 120_000L);
        LaunchNonces nonces = new LaunchNonces(() -> 0L, new java.util.Random(2)::nextBytes, 30_000L, 8);
        handler = new ShareIntentHandler(appContext, upnpClient, mainThread::add, (data, ids) -> null, pendingShare,
                nonces, null, 2_000L, 20L, 1_000L);
    }

    @After
    public void tearDown() {
        handler.shutdown();
        toastStatic.close();
    }

    private static Intent linkIntent() {
        Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn(SEND);
        when(intent.getType()).thenReturn("text/plain");
        when(intent.getStringExtra("android.intent.extra.TEXT")).thenReturn(LINK);
        return intent;
    }

    private BrowserHost newLayoutHost() {
        return new BrowserHost() {
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
                return true;
            }

            @Override
            public void onPendingShare(String token, String displayName) {
                tokens.add(token);
            }
        };
    }

    @Test
    public void theRefusalTextNamesTheSwitchOnlyForTheMissingServer() {
        assertEquals(R.string.share_link_needs_server,
                ShareIntentHandler.linkRefusalText(ProxyAvailability.Decision.REFUSE_NO_SERVER));
        assertEquals(R.string.share_link_failed, ShareIntentHandler.linkRefusalText(ProxyAvailability.Decision.USE_PROXY));
        assertEquals(R.string.share_link_failed, ShareIntentHandler.linkRefusalText(ProxyAvailability.Decision.DIRECT));
        assertEquals(R.string.share_link_failed, ShareIntentHandler.linkRefusalText(null));
    }

    @Test
    public void aConfirmedLinkWithTheProxyButWithoutTheServerIsRefusedWithTheClearMessageAndBuildsNothing() throws Exception {
        when(upnpClient.linkProxyDecision()).thenReturn(ProxyAvailability.Decision.REFUSE_NO_SERVER);
        handler.handle(linkIntent(), null, newLayoutHost());
        CountDownLatch finished = new CountDownLatch(1);

        handler.confirm(tokens.get(0), Set.of("receiver-1"), () -> {
        }, finished::countDown);

        runMainThreadUntil(() -> finished.getCount() == 0);
        assertEquals(0, finished.getCount());
        verify(upnpClient, never()).createPlayableItem(nullable(Uri.class), anySet());
        assertEquals(List.of(R.string.share_link_needs_server), toasts);
    }

    @Test
    public void aRefusedLinkOfAnotherReasonKeepsTheGenericMessage() throws Exception {
        when(upnpClient.linkProxyDecision()).thenReturn(ProxyAvailability.Decision.USE_PROXY);
        when(upnpClient.createPlayableItem(nullable(Uri.class), anySet())).thenReturn(null);
        handler.handle(linkIntent(), null, newLayoutHost());
        CountDownLatch finished = new CountDownLatch(1);

        handler.confirm(tokens.get(0), Set.of("receiver-1"), () -> {
        }, finished::countDown);

        runMainThreadUntil(() -> finished.getCount() == 0);
        assertEquals(0, finished.getCount());
        assertEquals(List.of(R.string.share_link_failed), toasts);
    }

    @Test
    public void theClassicPathAlsoTellsTheUserAndBuildsNothing() throws Exception {
        when(upnpClient.linkProxyDecision()).thenReturn(ProxyAvailability.Decision.REFUSE_NO_SERVER);

        handler.handle(linkIntent(), null);

        runMainThreadUntil(() -> !toasts.isEmpty());
        verify(upnpClient, never()).createPlayableItem(nullable(Uri.class));
        assertEquals(List.of(R.string.share_link_needs_server), toasts);
    }
}
