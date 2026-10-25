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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import de.yaacc.player.PlayableItem;
import de.yaacc.player.Player;
import de.yaacc.share.BoundedCall;
import de.yaacc.share.DeleteOnCloseSource;
import de.yaacc.share.LaunchNonces;
import de.yaacc.share.PendingShare;
import de.yaacc.share.PositionalSource;
import de.yaacc.share.ProviderCallGate;
import de.yaacc.share.ShareLimits;
import de.yaacc.share.ShareRegistry;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.share.SharedFileService;

/**
 * The shared-file path of {@link ShareIntentHandler}: the intake checks it repeats itself, the
 * pending share (one, token bound, nothing opened), consuming the intent, and what confirm and
 * cancel do. Android classes are mocks; the main thread is the calling thread and the file
 * preparation is a fake, so no provider, registry or player is involved.
 */
public class ShareIntentHandlerFileTest {

    private static final String SEND = "android.intent.action.SEND";
    private static final String URI = "content://media/external/audio/media/7";
    private static final String OTHER_URI = "content://com.example.other/files/8";

    private Context appContext;
    private PackageManager packageManager;
    private ContentResolver resolver;
    private UpnpClient upnpClient;
    private PendingShare pendingShare;
    private LaunchNonces nonces;
    private BoundedCall probeCalls;
    private ShareIntentHandler handler;
    private final List<SharedFileService.Prepared> prepared = new CopyOnWriteArrayList<>();
    private final List<Set<String>> preparedFor = new CopyOnWriteArrayList<>();
    private final AtomicReference<SharedFileService.Prepared> toReturn = new AtomicReference<>();
    private String providerType;
    private String providerName;
    private long now;
    private final AtomicInteger mainThreadRuns = new AtomicInteger();
    private ShareIntentHandler.FilePreparer preparer;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** Collects what the picker would be told. */
    private static final class RecordingHost implements BrowserHost {
        final List<String> tokens = new CopyOnWriteArrayList<>();
        final List<String> names = new CopyOnWriteArrayList<>();
        volatile CountDownLatch latch = new CountDownLatch(1);

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
        public void onPendingShare(String token, String displayName) {
            tokens.add(token);
            names.add(displayName);
            latch.countDown();
        }

        boolean awaitAnnouncement() throws InterruptedException {
            return latch.await(3, TimeUnit.SECONDS);
        }

        /** True if nothing is announced for a short while. */
        boolean staysSilent() throws InterruptedException {
            return !latch.await(400, TimeUnit.MILLISECONDS);
        }
    }

    @Before
    public void setUp() {
        now = 0L;
        providerType = "audio/mpeg";
        providerName = "Song.mp3";
        appContext = mock(Context.class);
        when(appContext.getApplicationContext()).thenReturn(appContext);
        when(appContext.getPackageName()).thenReturn("de.yaacc");
        when(appContext.getString(anyInt())).thenReturn("Shared file");
        packageManager = mock(PackageManager.class);
        when(appContext.getPackageManager()).thenReturn(packageManager);
        resolver = mock(ContentResolver.class);
        when(appContext.getContentResolver()).thenReturn(resolver);
        when(resolver.getType(nullable(Uri.class))).thenAnswer(invocation -> providerType);
        Cursor cursor = mock(Cursor.class);
        when(cursor.moveToFirst()).thenReturn(true);
        when(cursor.getColumnIndex(anyString())).thenReturn(0);
        when(cursor.getType(0)).thenReturn(Cursor.FIELD_TYPE_STRING);
        when(cursor.getString(0)).thenAnswer(invocation -> providerName);
        when(resolver.query(nullable(Uri.class), any(), nullable(String.class), any(), nullable(String.class)))
                .thenReturn(cursor);
        upnpClient = mock(UpnpClient.class);
        when(upnpClient.isPlayerServiceInitialized()).thenReturn(true);
        pendingShare = new PendingShare(() -> now, new java.util.Random(1)::nextBytes, 120_000L);
        nonces = new LaunchNonces(() -> now, new java.util.Random(2)::nextBytes, 30_000L, 8);
        // a pool and gate of their own: the process-wide ones would carry a stuck marker from test to test
        probeCalls = new BoundedCall(ShareLimits.PROVIDER_POOL_THREADS, "test-probe",
                new ProviderCallGate(ShareLimits.PROVIDER_MAX_TRACKED));
        preparer = (data, ids) -> {
            preparedFor.add(ids);
            SharedFileService.Prepared result = toReturn.get();
            if (result != null) {
                prepared.add(result);
            }
            return result;
        };
        handler = newHandler(10_000L, 100L, 3_000L);
    }

    /** The main thread is the calling thread; every run on it is counted. */
    private ShareIntentHandler newHandler(long playerWaitMillis, long pollMillis, long providerTimeoutMillis) {
        return new ShareIntentHandler(appContext, upnpClient, runnable -> {
            mainThreadRuns.incrementAndGet();
            runnable.run();
        }, preparer, pendingShare, nonces, probeCalls, playerWaitMillis, pollMillis, providerTimeoutMillis);
    }

    private ShareIntentHandler replaceHandler(ShareIntentHandler replacement) {
        handler.shutdown();
        handler = replacement;
        return replacement;
    }

    @After
    public void tearDown() {
        handler.shutdown();
    }

    private static ClipData clipOf(int itemCount, String uri) {
        ClipData clip = mock(ClipData.class);
        when(clip.getItemCount()).thenReturn(itemCount);
        if (itemCount > 0) {
            ClipData.Item item = mock(ClipData.Item.class);
            Uri parsed = mock(Uri.class);
            when(parsed.toString()).thenReturn(uri);
            Uri itemUri = uri == null ? null : parsed;
            when(item.getUri()).thenReturn(itemUri);
            when(clip.getItemAt(0)).thenReturn(item);
        }
        return clip;
    }

    /** A file intent as the launcher forwards it: it carries a valid single-use launch token. */
    private Intent streamIntent(String action, String type, int items, String uri, boolean data, int flags) {
        return streamIntentWithNonce(action, type, items, uri, data, flags, nonces.mint());
    }

    private static Intent streamIntentWithNonce(String action, String type, int items, String uri, boolean data,
                                                int flags, String nonce) {
        // build the nested mocks first: stubbing inside another stubbing call is not allowed
        Uri dataUri = data ? mock(Uri.class) : null;
        ClipData clip = items == 0 ? null : clipOf(items, uri);
        Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn(action);
        when(intent.getType()).thenReturn(type);
        when(intent.getData()).thenReturn(dataUri);
        when(intent.getFlags()).thenReturn(flags);
        when(intent.getClipData()).thenReturn(clip);
        when(intent.getStringExtra(LaunchNonces.EXTRA_NAME)).thenReturn(nonce);
        return intent;
    }

    private Intent goodIntent() {
        return streamIntent(SEND, "audio/mpeg", 1, URI, false, 1);
    }

    private static Intent goodIntentWithNonce(String nonce) {
        return streamIntentWithNonce(SEND, "audio/mpeg", 1, URI, false, 1, nonce);
    }

    // ---- intake

    @Test
    public void aWellFormedFileBecomesTheOnePendingShareAndTheHostIsTold() throws Exception {
        RecordingHost host = new RecordingHost();
        Intent intent = goodIntent();

        handler.handle(intent, null, host);

        assertTrue(host.awaitAnnouncement());
        assertEquals(1, host.tokens.size());
        assertEquals("Song.mp3", host.names.get(0));
        PendingShare.Data data = pendingShare.peek(host.tokens.get(0));
        assertNotNull(data);
        assertEquals(URI, data.getUri());
        assertEquals("audio/mpeg", data.getMimeType());
        assertEquals("Song.mp3", data.getDisplayName());
    }

    @Test
    public void theIntentIsConsumedAtOnce() throws Exception {
        RecordingHost host = new RecordingHost();
        Intent intent = goodIntent();

        handler.handle(intent, null, host);

        verify(intent).setClipData(null);
        verify(intent).removeExtra(Intent.EXTRA_STREAM);
    }

    @Test
    public void aRejectedIntentIsConsumedToo() throws Exception {
        RecordingHost host = new RecordingHost();
        Intent intent = streamIntent(SEND, "audio/mpeg", 1, URI, false, 0);

        handler.handle(intent, null, host);

        verify(intent).setClipData(null);
    }

    @Test
    public void theNameComesFromTheProviderCleaned() throws Exception {
        providerName = "evil\n\u202Eexe.mp3";
        RecordingHost host = new RecordingHost();

        handler.handle(goodIntent(), null, host);

        assertTrue(host.awaitAnnouncement());
        assertEquals("evil exe.mp3", host.names.get(0));
    }

    @Test
    public void aMissingNameFallsBackToTheFixedText() throws Exception {
        providerName = "   ";
        RecordingHost host = new RecordingHost();

        handler.handle(goodIntent(), null, host);

        assertTrue(host.awaitAnnouncement());
        assertEquals("Shared file", host.names.get(0));
    }

    @Test
    public void theProvidersTypeWinsOverTheIntentsWildcard() throws Exception {
        providerType = "audio/flac";
        RecordingHost host = new RecordingHost();

        handler.handle(streamIntent(SEND, "audio/*", 1, URI, false, 1), null, host);

        assertTrue(host.awaitAnnouncement());
        assertEquals("audio/flac", pendingShare.peek(host.tokens.get(0)).getMimeType());
    }

    @Test
    public void aTypeThatIsNotMediaIsIgnoredEvenIfTheIntentSaidAudio() throws Exception {
        providerType = "text/html";
        RecordingHost host = new RecordingHost();

        handler.handle(streamIntent(SEND, "audio/*", 1, URI, false, 1), null, host);

        assertTrue(host.staysSilent());
        assertFalse(pendingShare.isPending());
    }

    @Test
    public void theConcreteIntentTypeIsTheFallbackWhenTheProviderKnowsNone() throws Exception {
        providerType = null;
        RecordingHost host = new RecordingHost();

        handler.handle(streamIntent(SEND, "video/mp4", 1, URI, false, 1), null, host);

        assertTrue(host.awaitAnnouncement());
        assertEquals("video/mp4", pendingShare.peek(host.tokens.get(0)).getMimeType());
    }

    @Test
    public void withoutTheReadFlagNothingIsPending() throws Exception {
        RecordingHost host = new RecordingHost();

        handler.handle(streamIntent(SEND, "audio/mpeg", 1, URI, false, 0), null, host);

        assertTrue(host.staysSilent());
        assertFalse(pendingShare.isPending());
    }

    @Test
    public void moreThanOneClipItemIsIgnored() throws Exception {
        RecordingHost host = new RecordingHost();

        handler.handle(streamIntent(SEND, "audio/mpeg", 2, URI, false, 1), null, host);

        assertTrue(host.staysSilent());
        assertFalse(pendingShare.isPending());
    }

    @Test
    public void noClipDataIsIgnored() throws Exception {
        RecordingHost host = new RecordingHost();

        handler.handle(streamIntent(SEND, "audio/mpeg", 0, null, false, 1), null, host);

        assertTrue(host.staysSilent());
    }

    @Test
    public void dataOnTheIntentIsIgnored() throws Exception {
        RecordingHost host = new RecordingHost();

        handler.handle(streamIntent(SEND, "audio/mpeg", 1, URI, true, 1), null, host);

        assertTrue(host.staysSilent());
    }

    @Test
    public void aFileUriIsIgnored() throws Exception {
        RecordingHost host = new RecordingHost();

        handler.handle(streamIntent(SEND, "audio/mpeg", 1, "file:///data/data/de.yaacc/x.mp3", false, 1), null, host);

        assertTrue(host.staysSilent());
    }

    @Test
    public void aUriOfTheFileProviderIsIgnored() throws Exception {
        RecordingHost host = new RecordingHost();

        handler.handle(streamIntent(SEND, "audio/mpeg", 1, "content://de.yaacc.fileprovider/shared/x.mp3", false, 1),
                null, host);

        assertTrue(host.staysSilent());
    }

    @Test
    public void aUriOfAnyOtherProviderOfThisAppIsIgnored() throws Exception {
        ProviderInfo ours = new ProviderInfo();
        ours.packageName = "de.yaacc";
        when(packageManager.resolveContentProvider("de.yaacc.androidx-startup", 0)).thenReturn(ours);
        RecordingHost host = new RecordingHost();

        handler.handle(streamIntent(SEND, "audio/mpeg", 1, "content://de.yaacc.androidx-startup/x", false, 1),
                null, host);

        assertTrue(host.staysSilent());
    }

    @Test
    public void anotherActionIsIgnored() throws Exception {
        RecordingHost host = new RecordingHost();

        handler.handle(streamIntent("android.intent.action.VIEW", "audio/mpeg", 1, URI, false, 1), null, host);

        assertTrue(host.staysSilent());
    }

    @Test
    public void aProviderThatDoesNotAnswerDropsTheShareAndFreesTheGateForTheNext() throws Exception {
        replaceHandler(newHandler(10_000L, 100L, 200L));
        CountDownLatch hang = new CountDownLatch(1);
        AtomicInteger asked = new AtomicInteger();
        when(resolver.getType(nullable(Uri.class))).thenAnswer(invocation -> {
            if (asked.incrementAndGet() == 1) {
                // a provider that never answers (until the test ends)
                hang.await(30, TimeUnit.SECONDS);
            }
            return "audio/mpeg";
        });
        RecordingHost host = new RecordingHost();
        try {
            handler.handle(goodIntent(), null, host);

            assertTrue("the hung share is dropped, not announced", host.staysSilent());
            assertFalse(pendingShare.isPending());

            // the timeout released the single-flight gate: a share from a responsive provider is
            // accepted (another one: the hung provider is stuck, see the per-provider tests below)
            host.latch = new CountDownLatch(1);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (host.tokens.isEmpty() && System.nanoTime() < deadline) {
                handler.handle(streamIntent(SEND, "audio/mpeg", 1, OTHER_URI, false, 1), null, host);
                host.latch.await(300, TimeUnit.MILLISECONDS);
            }
            assertEquals(1, host.tokens.size());
        } finally {
            hang.countDown();
        }
    }

    @Test
    public void aSecondIntakeWhileOneIsStillRunningIsDroppedWithoutAnotherProviderCall() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger asked = new AtomicInteger();
        when(resolver.getType(nullable(Uri.class))).thenAnswer(invocation -> {
            asked.incrementAndGet();
            gate.await(30, TimeUnit.SECONDS);
            return "audio/mpeg";
        });
        RecordingHost host = new RecordingHost();
        try {
            handler.handle(goodIntent(), null, host);
            waitUntil(() -> asked.get() == 1);
            assertEquals("the first intake is inside the provider call", 1, asked.get());

            Intent second = goodIntent();
            handler.handle(second, null, host);
            handler.handle(goodIntent(), null, host);
            Thread.sleep(400);

            assertEquals("no second provider call was started", 1, asked.get());
            verify(second).setClipData(null);
            assertTrue(host.tokens.isEmpty());
            assertFalse(pendingShare.isPending());
        } finally {
            gate.countDown();
        }

        // the first one finishes normally and leaves exactly one pending share
        assertTrue(host.awaitAnnouncement());
        assertEquals(1, host.tokens.size());
        String first = host.tokens.get(0);
        assertNotNull(pendingShare.peek(first));
        // the fileExecutor is single-threaded: without the gate the dropped intents would only be
        // queued behind the blocked call and be probed now, so give them the chance to run
        Thread.sleep(300);
        assertEquals("the dropped intents were never probed", 1, asked.get());

        // once it is released and finished, a new intake is accepted again
        handler.cancel(first);
        assertFalse(pendingShare.isPending());
        host.latch = new CountDownLatch(1);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (host.tokens.size() < 2 && System.nanoTime() < deadline) {
            handler.handle(goodIntent(), null, host);
            host.latch.await(300, TimeUnit.MILLISECONDS);
        }
        assertEquals(2, host.tokens.size());
        assertNull("the first token is dead", pendingShare.peek(first));
        assertNotNull(pendingShare.peek(host.tokens.get(1)));
    }

    @Test
    public void aSecondShareWhileOneIsPendingIsDroppedAndNeverReplacesIt() throws Exception {
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());
        String token = host.tokens.get(0);
        host.latch = new CountDownLatch(1);
        providerName = "Other.mp3";

        handler.handle(goodIntent(), null, host);

        assertTrue(host.staysSilent());
        assertEquals(1, host.tokens.size());
        assertEquals("Song.mp3", pendingShare.peek(token).getDisplayName());
    }

    @Test
    public void withoutAHostTheShareIsDroppedAtOnceBecauseNobodyCouldConfirmIt() throws Exception {
        handler.handle(goodIntent(), null, null);

        Thread.sleep(500);
        assertFalse(pendingShare.isPending());
    }

    @Test
    public void aPickerThatFailsReleasesTheShare() throws Exception {
        BrowserHost failing = new BrowserHost() {
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
            public void onPendingShare(String token, String displayName) {
                throw new IllegalStateException("fragment manager is gone");
            }
        };

        handler.handle(goodIntent(), null, failing);

        Thread.sleep(500);
        assertFalse(pendingShare.isPending());
    }

    @Test
    public void aTextIntentNeverTakesTheFilePath() throws Exception {
        RecordingHost host = new RecordingHost();
        Intent text = streamIntent(SEND, "text/plain", 1, URI, false, 1);

        handler.handle(text, null, host);

        assertTrue(host.staysSilent());
        assertFalse(pendingShare.isPending());
    }

    // ---- confirm and cancel

    @Test
    public void confirmTakesTheShareOnceAndPreparesItForTheChosenReceivers() throws Exception {
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());
        String token = host.tokens.get(0);

        handler.confirm(token, Set.of("receiver-1", "receiver-2"));
        handler.confirm(token, Set.of("receiver-3"));

        verify(upnpClient, timeout(3_000).atLeastOnce()).isPlayerServiceInitialized();
        waitUntil(() -> preparedFor.size() >= 1);
        Thread.sleep(200);
        assertEquals(1, preparedFor.size());
        assertEquals(Set.of("receiver-1", "receiver-2"), preparedFor.get(0));
        assertFalse(pendingShare.isPending());
    }

    @Test
    public void aWrongTokenDoesNothing() throws Exception {
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());

        handler.confirm("0123456789abcdef0123456789abcdef", Set.of("receiver-1"));
        handler.confirm(null, Set.of("receiver-1"));

        Thread.sleep(300);
        assertEquals(0, preparedFor.size());
        assertTrue("the pending share is untouched", pendingShare.isPending());
    }

    @Test
    public void confirmWithNoReceiverReleasesTheShareAndPlaysNothing() throws Exception {
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());

        handler.confirm(host.tokens.get(0), Collections.<String>emptySet());

        Thread.sleep(300);
        assertEquals(0, preparedFor.size());
        assertFalse(pendingShare.isPending());
        verify(upnpClient, never()).setReceiverDeviceIds(anySet());
    }

    @Test
    public void cancelReleasesTheShareAndOnlyForItsToken() throws Exception {
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());

        handler.cancel("0123456789abcdef0123456789abcdef");
        assertTrue(pendingShare.isPending());
        handler.cancel(host.tokens.get(0));

        assertFalse(pendingShare.isPending());
    }

    @Test
    public void aTimedOutShareCannotBeConfirmed() throws Exception {
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());
        now = 120_000L;

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"));

        Thread.sleep(300);
        assertEquals(0, preparedFor.size());
    }

    @Test
    public void aConfirmedFileIsPlayedOnTheChosenReceiversAndTheHookRuns() throws Exception {
        PlayableItem item = new PlayableItem();
        toReturn.set(new SharedFileService.Prepared("key", item));
        Player player = mock(Player.class);
        when(upnpClient.initializePlayersWithPlayableItems(anyList())).thenReturn(List.of(player));
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());
        CountDownLatch hook = new CountDownLatch(1);

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"), hook::countDown);

        assertTrue(hook.await(5, TimeUnit.SECONDS));
        verify(upnpClient).setReceiverDeviceIds(Set.of("receiver-1"));
        verify(player).play();
        verify(upnpClient, never()).discardShareOf(any());
    }

    @Test
    public void whenNoPlayerIsCreatedTheShareIsDiscardedAndTheHookStillRuns() throws Exception {
        PlayableItem item = new PlayableItem();
        toReturn.set(new SharedFileService.Prepared("key", item));
        when(upnpClient.initializePlayersWithPlayableItems(anyList())).thenReturn(new ArrayList<Player>());
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());
        CountDownLatch hook = new CountDownLatch(1);

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"), hook::countDown);

        assertTrue(hook.await(5, TimeUnit.SECONDS));
        verify(upnpClient).discardShareOf(item);
    }

    @Test
    public void whenStartingThrowsTheShareIsDiscardedAndTheHookStillRuns() throws Exception {
        PlayableItem item = new PlayableItem();
        toReturn.set(new SharedFileService.Prepared("key", item));
        when(upnpClient.initializePlayersWithPlayableItems(anyList())).thenThrow(new IllegalStateException("boom"));
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());
        CountDownLatch hook = new CountDownLatch(1);

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"), hook::countDown);

        assertTrue(hook.await(5, TimeUnit.SECONDS));
        verify(upnpClient).discardShareOf(item);
    }

    @Test
    public void whenTheFileCannotBePreparedThereIsNothingToClearAndNothingIsPlayed() throws Exception {
        toReturn.set(null);
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());
        AtomicInteger hook = new AtomicInteger();

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"), hook::incrementAndGet);

        waitUntil(() -> preparedFor.size() == 1);
        Thread.sleep(300);
        assertEquals(0, hook.get());
        verify(upnpClient, never()).setReceiverDeviceIds(anySet());
        verify(upnpClient, never()).initializePlayersWithPlayableItems(anyList());
    }

    @Test
    public void thePlayerServiceIsStartedWhenItIsNotUpYet() throws Exception {
        when(upnpClient.isPlayerServiceInitialized()).thenReturn(false);
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"));

        verify(upnpClient).startService();
    }

    // ---- the player service never comes up

    /** A source that counts its closes. */
    private static final class CountingSource extends PositionalSource {
        final AtomicInteger closes = new AtomicInteger();

        CountingSource() {
            super(10L);
        }

        @Override
        protected int readAtInternal(long position, byte[] buf, int off, int len) {
            return -1;
        }

        @Override
        protected void closeInternal() {
            closes.incrementAndGet();
        }
    }

    private ShareRegistry registryWhoseRemovalIsTheDiscard(String[] keyOut, PlayableItem item, PositionalSource source) {
        ShareRegistry registry = new ShareRegistry(() -> now, new java.util.Random(2)::nextBytes, 4);
        String key = registry.register(new ShareRegistry.Entry(ShareRegistry.Kind.LOCAL_FILE, source, "audio/mpeg",
                Set.of("192.168.1.20"), 600_000L, 3_600_000L));
        assertNotNull(key);
        keyOut[0] = key;
        // the real UpnpClient.discardShareOf removes the key from the registry; the mock does the same
        doAnswer(invocation -> registry.remove(key)).when(upnpClient).discardShareOf(item);
        toReturn.set(new SharedFileService.Prepared(key, item));
        return registry;
    }

    private void confirmWithAPlayerServiceThatNeverComesUp(AtomicInteger hook) throws Exception {
        when(upnpClient.isPlayerServiceInitialized()).thenReturn(false);
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"), hook::incrementAndGet);

        waitUntil(() -> preparedFor.size() == 1);
        // the wait is 50 ms: give the worker time to give up and clean up
        Thread.sleep(500);
    }

    @Test
    public void ifThePlayerServiceNeverComesUpTheShareIsRemovedAndTheSourceClosed() throws Exception {
        replaceHandler(newHandler(50L, 10L, 3_000L));
        PlayableItem item = new PlayableItem();
        CountingSource source = new CountingSource();
        String[] key = new String[1];
        ShareRegistry registry = registryWhoseRemovalIsTheDiscard(key, item, source);
        AtomicInteger hook = new AtomicInteger();

        confirmWithAPlayerServiceThatNeverComesUp(hook);

        verify(upnpClient).discardShareOf(item);
        assertNull("the key is gone from the registry", registry.lookup(key[0]));
        assertEquals("the source was closed once", 1, source.closes.get());
        verify(upnpClient, never()).setReceiverDeviceIds(anySet());
        verify(upnpClient, never()).initializePlayersWithPlayableItems(anyList());
        assertEquals("nothing changed, so the selection hook does not run", 0, hook.get());
    }

    @Test
    public void ifThePlayerServiceNeverComesUpACopyIsDeletedToo() throws Exception {
        replaceHandler(newHandler(50L, 10L, 3_000L));
        PlayableItem item = new PlayableItem();
        File copy = temp.newFile("share-0123456789abcdef0123456789abcdef.bin");
        CountingSource inner = new CountingSource();
        String[] key = new String[1];
        ShareRegistry registry = registryWhoseRemovalIsTheDiscard(key, item, new DeleteOnCloseSource(inner, copy));
        AtomicInteger hook = new AtomicInteger();

        confirmWithAPlayerServiceThatNeverComesUp(hook);

        assertNull(registry.lookup(key[0]));
        assertEquals(1, inner.closes.get());
        assertFalse("the copy file is deleted", copy.exists());
        assertEquals(0, hook.get());
    }

    @Test
    public void theGiveUpIsReportedOnceThroughTheMainThreadAndNeverStartsPlayback() throws Exception {
        replaceHandler(newHandler(50L, 10L, 3_000L));
        PlayableItem item = new PlayableItem();
        toReturn.set(new SharedFileService.Prepared("key", item));
        when(upnpClient.isPlayerServiceInitialized()).thenReturn(false);
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());
        int runsBefore = mainThreadRuns.get();

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"));
        waitUntil(() -> mainThreadRuns.get() > runsBefore);
        Thread.sleep(300);

        // exactly one run on the main thread: the message to the user, not playFile
        assertEquals(runsBefore + 1, mainThreadRuns.get());
        verify(upnpClient, never()).initializePlayersWithPlayableItems(anyList());
        verify(upnpClient).discardShareOf(item);
    }

    @Test
    public void aFailingMessageDoesNotDiscardTheShareASecondTime() throws Exception {
        // Toast.makeText is a stub that returns null here, so showing the message throws; the clean-up already happened
        replaceHandler(newHandler(50L, 10L, 3_000L));
        PlayableItem item = new PlayableItem();
        toReturn.set(new SharedFileService.Prepared("key", item));
        when(upnpClient.isPlayerServiceInitialized()).thenReturn(false);
        RecordingHost host = new RecordingHost();
        handler.handle(goodIntent(), null, host);
        assertTrue(host.awaitAnnouncement());

        handler.confirm(host.tokens.get(0), Set.of("receiver-1"));
        waitUntil(() -> preparedFor.size() == 1);
        Thread.sleep(500);

        verify(upnpClient, org.mockito.Mockito.times(1)).discardShareOf(item);
    }

    // ---- launch token: a file is accepted only if it came through the launcher

    @Test
    public void aFileWithoutALaunchTokenIsDroppedSilentlyAndNeverReachesTheProvider() throws Exception {
        RecordingHost host = new RecordingHost();
        Intent intent = goodIntentWithNonce(null);

        handler.handle(intent, null, host);

        assertTrue(host.staysSilent());
        assertFalse(pendingShare.isPending());
        verify(resolver, never()).getType(nullable(Uri.class));
        verify(intent).setClipData(null);
        verify(intent).removeExtra(LaunchNonces.EXTRA_NAME);
    }

    @Test
    public void aForgedLaunchTokenIsDropped() throws Exception {
        RecordingHost host = new RecordingHost();
        nonces.mint();

        handler.handle(goodIntentWithNonce("00000000000000000000000000000000"), null, host);
        handler.handle(goodIntentWithNonce("not-a-token"), null, host);

        assertTrue(host.staysSilent());
        assertFalse(pendingShare.isPending());
        verify(resolver, never()).getType(nullable(Uri.class));
    }

    @Test
    public void aWrongTokenDoesNotSpendTheRealOne() throws Exception {
        RecordingHost host = new RecordingHost();
        String real = nonces.mint();

        handler.handle(goodIntentWithNonce("ffffffffffffffffffffffffffffffff"), null, host);
        assertTrue(host.staysSilent());

        handler.handle(goodIntentWithNonce(real), null, host);
        assertTrue(host.awaitAnnouncement());
    }

    @Test
    public void anExpiredLaunchTokenIsDropped() throws Exception {
        RecordingHost host = new RecordingHost();
        String nonce = nonces.mint();
        now += 30_000L;

        handler.handle(goodIntentWithNonce(nonce), null, host);

        assertTrue(host.staysSilent());
        assertFalse(pendingShare.isPending());
        verify(resolver, never()).getType(nullable(Uri.class));
    }

    @Test
    public void aLaunchTokenIsAcceptedOnceAndAReplayIsDropped() throws Exception {
        RecordingHost host = new RecordingHost();
        String nonce = nonces.mint();

        handler.handle(goodIntentWithNonce(nonce), null, host);
        assertTrue(host.awaitAnnouncement());
        handler.cancel(host.tokens.get(0));
        assertFalse(pendingShare.isPending());
        host.latch = new CountDownLatch(1);

        // the same token again, for instance from a re-delivered intent
        handler.handle(goodIntentWithNonce(nonce), null, host);

        assertTrue(host.staysSilent());
        assertFalse(pendingShare.isPending());
        assertEquals(1, host.tokens.size());
    }

    @Test
    public void theTokenIsSpentEvenWhenALaterCheckRejectsTheShare() throws Exception {
        RecordingHost host = new RecordingHost();
        String nonce = nonces.mint();

        handler.handle(streamIntentWithNonce(SEND, "audio/mpeg", 1, URI, false, 0, nonce), null, host);
        handler.handle(goodIntentWithNonce(nonce), null, host);

        assertTrue(host.staysSilent());
    }

    @Test
    public void aLinkNeedsNoLaunchToken() throws Exception {
        // the text path is unchanged: a text intent carries no token and is not looked at here
        Intent text = streamIntentWithNonce(SEND, "text/plain", 1, URI, false, 1, null);

        handler.handle(text, null, new RecordingHost());

        verify(text).removeExtra(Intent.EXTRA_TEXT);
        verify(text, never()).getStringExtra(LaunchNonces.EXTRA_NAME);
        verify(resolver, never()).getType(nullable(Uri.class));
    }

    // ---- one provider cannot take the pool

    /**
     * The first provider call hangs until the test ends; later ones answer. Interrupts do not end
     * it, like a Binder call into another process.
     */
    private CountDownLatch hangFirstProviderCall(AtomicInteger asked) throws Exception {
        CountDownLatch hang = new CountDownLatch(1);
        when(resolver.getType(nullable(Uri.class))).thenAnswer(invocation -> {
            if (asked.incrementAndGet() == 1) {
                long end = System.nanoTime() + 30_000_000_000L;
                while (hang.getCount() > 0 && System.nanoTime() < end) {
                    try {
                        hang.await(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // keep waiting
                    }
                }
            }
            return "audio/mpeg";
        });
        return hang;
    }

    @Test
    public void aHungProviderDoesNotStopAShareFromAnotherProvider() throws Exception {
        replaceHandler(newHandler(10_000L, 100L, 200L));
        AtomicInteger asked = new AtomicInteger();
        CountDownLatch hang = hangFirstProviderCall(asked);
        RecordingHost host = new RecordingHost();
        try {
            handler.handle(goodIntent(), null, host);
            assertTrue("the hung share is dropped", host.staysSilent());
            host.latch = new CountDownLatch(1);

            handler.handle(streamIntent(SEND, "audio/mpeg", 1, OTHER_URI, false, 1), null, host);

            assertTrue("a share from another provider still goes through", host.awaitAnnouncement());
            assertEquals(2, asked.get());
        } finally {
            hang.countDown();
        }
    }

    @Test
    public void aSecondShareFromAStuckProviderIsDroppedWithoutAProviderCall() throws Exception {
        replaceHandler(newHandler(10_000L, 100L, 200L));
        AtomicInteger asked = new AtomicInteger();
        CountDownLatch hang = hangFirstProviderCall(asked);
        RecordingHost host = new RecordingHost();
        try {
            handler.handle(goodIntent(), null, host);
            assertTrue(host.staysSilent());
            assertEquals(1, asked.get());
            host.latch = new CountDownLatch(1);

            handler.handle(goodIntent(), null, host);
            handler.handle(goodIntent(), null, host);

            assertTrue(host.staysSilent());
            assertEquals("the stuck provider was not asked again", 1, asked.get());
            assertFalse(pendingShare.isPending());
        } finally {
            hang.countDown();
        }
    }

    @Test
    public void aStuckProviderStaysBlockedLongAfterAndIsFreedWhenItsCallReturns() throws Exception {
        replaceHandler(newHandler(10_000L, 100L, 200L));
        AtomicInteger asked = new AtomicInteger();
        CountDownLatch hang = hangFirstProviderCall(asked);
        RecordingHost host = new RecordingHost();
        try {
            handler.handle(goodIntent(), null, host);
            assertTrue(host.staysSilent());
            host.latch = new CountDownLatch(1);

            // no amount of waiting frees it: its thread is still pinned
            now += 24L * 60L * 60L * 1000L;
            handler.handle(goodIntent(), null, host);
            assertTrue(host.staysSilent());
            assertEquals("the pinned provider was not asked again", 1, asked.get());
        } finally {
            hang.countDown();
        }

        // the late return of the call frees the provider
        host.latch = new CountDownLatch(1);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (host.tokens.isEmpty() && System.nanoTime() < deadline) {
            handler.handle(goodIntent(), null, host);
            host.latch.await(300, TimeUnit.MILLISECONDS);
        }
        assertEquals(1, host.tokens.size());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
