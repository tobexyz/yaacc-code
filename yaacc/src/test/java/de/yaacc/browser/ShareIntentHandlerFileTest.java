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
import org.junit.Test;

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
import de.yaacc.share.PendingShare;
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

    private Context appContext;
    private PackageManager packageManager;
    private ContentResolver resolver;
    private UpnpClient upnpClient;
    private PendingShare pendingShare;
    private ShareIntentHandler handler;
    private final List<SharedFileService.Prepared> prepared = new CopyOnWriteArrayList<>();
    private final List<Set<String>> preparedFor = new CopyOnWriteArrayList<>();
    private final AtomicReference<SharedFileService.Prepared> toReturn = new AtomicReference<>();
    private String providerType;
    private String providerName;
    private long now;

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
        handler = new ShareIntentHandler(appContext, upnpClient, Runnable::run,
                (data, ids) -> {
                    preparedFor.add(ids);
                    SharedFileService.Prepared result = toReturn.get();
                    if (result != null) {
                        prepared.add(result);
                    }
                    return result;
                }, pendingShare);
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

    private static Intent streamIntent(String action, String type, int items, String uri, boolean data, int flags) {
        // build the nested mocks first: stubbing inside another stubbing call is not allowed
        Uri dataUri = data ? mock(Uri.class) : null;
        ClipData clip = items == 0 ? null : clipOf(items, uri);
        Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn(action);
        when(intent.getType()).thenReturn(type);
        when(intent.getData()).thenReturn(dataUri);
        when(intent.getFlags()).thenReturn(flags);
        when(intent.getClipData()).thenReturn(clip);
        return intent;
    }

    private static Intent goodIntent() {
        return streamIntent(SEND, "audio/mpeg", 1, URI, false, 1);
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
    public void aProviderThatDoesNotAnswerDropsTheShare() throws Exception {
        when(resolver.getType(nullable(Uri.class))).thenAnswer(invocation -> {
            Thread.sleep(4_000);
            return "audio/mpeg";
        });
        RecordingHost host = new RecordingHost();
        long begin = System.nanoTime();

        handler.handle(goodIntent(), null, host);

        assertTrue(host.staysSilent());
        assertFalse(pendingShare.isPending());
        assertTrue("bounded by the provider timeout", (System.nanoTime() - begin) / 1_000_000L < 3_900L);
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

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
