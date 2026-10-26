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
package de.yaacc.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.widget.Toast;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import de.yaacc.util.InterfaceResolutionHelper;

/**
 * Characterization of {@link ShareService#prepare}, the glue between the provider, the
 * registry and the player item, which had no unit test of its own: every failure closes the
 * source and leaves nothing registered, a success hands the source to the registry and counts
 * the key as unclaimed, the item plays the proxy URL.
 * <p>
 * Written against the code as it was before Phase 4 moved it; the same cases must pass after the
 * class is renamed and its helpers are merged. Android types are mocks:
 * <ul>
 * <li>{@code Uri.parse} and {@code Toast.makeText} (static) and {@code Handler} (constructor) are
 * mocked on the test thread, so the toast the service would show is visible as a resource id.</li>
 * <li>The provider call runs on the service's own worker thread, where static mocks of the test
 * thread do not apply, so the {@code HeldFileSource.open} stand-in is installed from the answer
 * of the mocked {@code ContentResolver}, which runs on that worker thread, and removed again as
 * soon as it was used.</li>
 * </ul>
 */
public class ShareServicePrepareTest {

    private static final String URI = "content://com.example.provider/files/song.mp3";
    private static final String IP = "192.168.1.5";
    private static final long SOURCE_SIZE = 4096L;
    private static final long FAR_FUTURE = Long.MAX_VALUE / 2;

    private final SecureRandom random = new SecureRandom();
    private volatile long now = 1_000_000L;

    private ContentResolver resolver;
    private Yaacc yaacc;
    private UpnpClient upnpClient;
    private ShareRegistry registry;
    private UnclaimedShares unclaimed;
    private HeldFileSource source;
    private ShareService service;

    private MockedStatic<Uri> uriStatic;
    private MockedStatic<Toast> toastStatic;
    private MockedStatic<InterfaceResolutionHelper> network;
    private MockedConstruction<Handler> handlers;
    private final List<Runnable> posted = new CopyOnWriteArrayList<>();
    private final List<Integer> toasts = new ArrayList<>();

    private String ip = IP;

    @Before
    public void setUp() throws Exception {
        uriStatic = mockStatic(Uri.class);
        stubUriParse(uriStatic);
        toastStatic = mockStatic(Toast.class);
        toastStatic.when(() -> Toast.makeText(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            toasts.add(invocation.getArgument(1));
            return mock(Toast.class);
        });
        handlers = mockConstruction(Handler.class, (handler, context) ->
                when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
                    posted.add(invocation.getArgument(0));
                    return true;
                }));
        network = mockStatic(InterfaceResolutionHelper.class);
        network.when(() -> InterfaceResolutionHelper.getIpAddress(any())).thenAnswer(invocation -> ip);
        network.when(() -> InterfaceResolutionHelper.getBindAddresses(any()))
                .thenAnswer(invocation -> Collections.<InetAddress>emptyList().iterator());

        resolver = mock(ContentResolver.class);
        yaacc = mock(Yaacc.class);
        when(yaacc.getApplicationContext()).thenReturn(yaacc);
        when(yaacc.getContentResolver()).thenReturn(resolver);
        when(yaacc.getPackageManager()).thenReturn(mock(PackageManager.class));
        registry = new ShareRegistry(() -> now, random::nextBytes, 2);
        unclaimed = new UnclaimedShares();
        when(yaacc.getShareRegistry()).thenReturn(registry);
        when(yaacc.getUnclaimedShares()).thenReturn(unclaimed);
        upnpClient = mock(UpnpClient.class);
        when(upnpClient.isHttpServerRunning()).thenReturn(true);

        source = mock(HeldFileSource.class);
        when(source.size()).thenReturn(SOURCE_SIZE);
        providerHandsOutAFile();

        service = new ShareService(yaacc, upnpClient);
    }

    @After
    public void tearDown() {
        network.close();
        handlers.close();
        toastStatic.close();
        uriStatic.close();
    }

    // ---- fixtures

    /** {@code Uri.parse} returns a mock whose text is the parsed text (the stub returns null). */
    private static void stubUriParse(MockedStatic<Uri> uris) {
        uris.when(() -> Uri.parse(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            Uri uri = mock(Uri.class);
            when(uri.toString()).thenReturn(text);
            return uri;
        });
    }

    /** The provider opens the file; the held source is the mock above. */
    private void providerHandsOutAFile() throws Exception {
        when(resolver.openAssetFileDescriptor(nullable(Uri.class), eq("r"))).thenAnswer(invocation -> {
            // this runs on the provider worker thread: static mocks are bound to the thread
            MockedStatic<HeldFileSource> held = mockStatic(HeldFileSource.class);
            held.when(() -> HeldFileSource.open(any())).thenAnswer(open -> {
                held.close();
                return source;
            });
            return mock(AssetFileDescriptor.class);
        });
    }

    private PendingShare.Data data(String mimeType) {
        PendingShare pending = new PendingShare(() -> now, random::nextBytes, 120_000L);
        String token = pending.offer(URI, "song.mp3", mimeType);
        return pending.take(token);
    }

    private ShareService.Prepared prepare(String mimeType, Set<String> receivers) {
        return service.prepare(data(mimeType), receivers);
    }

    private ShareService.Prepared prepare() {
        return prepare("audio/mpeg", Collections.<String>emptySet());
    }

    /** The resource ids of the toasts the service posted, in order. */
    private List<Integer> shownToasts() {
        for (Runnable runnable : new ArrayList<>(posted)) {
            runnable.run();
        }
        posted.clear();
        return new ArrayList<>(toasts);
    }

    private static final class Leftover implements AutoCloseable {
        int closes;

        @Override
        public void close() {
            closes++;
        }
    }

    private ShareRegistry.Entry entryOf(Object target) {
        return new ShareRegistry.Entry(ShareRegistry.Kind.LOCAL_FILE, target, "audio/mpeg",
                Collections.<String>emptySet(), 10_000L, 100_000L);
    }

    // ---- failures: the source is closed, nothing stays registered

    @Test
    public void aServerThatIsNotReadyDropsTheShareAndClosesTheSource() {
        when(upnpClient.isHttpServerRunning()).thenReturn(false);

        ShareService.Prepared result = prepare();

        assertNull(result);
        verify(source, times(1)).close();
        assertEquals(0, registry.size());
        assertEquals(0, unclaimed.size());
        assertEquals(Collections.singletonList(R.string.share_open_failed), shownToasts());
    }

    @Test
    public void withoutANetworkAddressTheShareIsDroppedBeforeAnythingIsRegistered() {
        ip = null;

        assertNull(prepare());

        verify(source, times(1)).close();
        assertEquals(0, registry.size());
        assertEquals(0, unclaimed.size());
        assertEquals(Collections.singletonList(R.string.share_open_failed), shownToasts());
    }

    @Test
    public void aBlankNetworkAddressIsNoAddress() {
        ip = "   ";

        assertNull(prepare());

        verify(source, times(1)).close();
        assertEquals(0, registry.size());
    }

    @Test
    public void aFullRegistryDropsTheShareClosesTheSourceAndKeepsTheRunningOnes() {
        Leftover running = new Leftover();
        Leftover second = new Leftover();
        String firstKey = registry.register(entryOf(running));
        registry.register(entryOf(second));
        // both are running: nothing can be evicted

        ShareService.Prepared result = prepare();

        assertNull(result);
        verify(source, times(1)).close();
        assertEquals(2, registry.size());
        assertNotNull(registry.lookup(firstKey));
        assertEquals(0, running.closes);
        assertEquals(0, second.closes);
        assertEquals(0, unclaimed.size());
        assertEquals(Collections.singletonList(R.string.share_registry_full), shownToasts());
    }

    @Test
    public void aFullRegistryEvictsAReleasedLeftoverAndRegistersTheShare() {
        Leftover leftover = new Leftover();
        String leftoverKey = registry.register(entryOf(leftover));
        registry.register(entryOf(new Leftover()));
        registry.release(leftoverKey);

        ShareService.Prepared result = prepare();

        assertNotNull(result);
        assertEquals(1, leftover.closes);
        assertNull(registry.lookup(leftoverKey));
        assertNotNull(registry.lookup(result.getKey()));
        verify(source, never()).close();
    }

    @Test
    public void anItemThatCannotBeBuiltRemovesTheKeyAndClosesTheSource() {
        ShareService.Prepared result = prepare("application/pdf", Collections.<String>emptySet());

        assertNull(result);
        assertEquals("the registry removed the key", 0, registry.size());
        verify(source, times(1)).close();
        assertEquals(Collections.singletonList(R.string.share_open_failed), shownToasts());
    }

    @Test
    public void anExceptionBeforeTheRegistrationClosesTheSource() {
        when(upnpClient.getDevice("tv")).thenThrow(new IllegalStateException("boom"));

        ShareService.Prepared result = prepare("audio/mpeg", Collections.singleton("tv"));

        assertNull(result);
        verify(source, times(1)).close();
        assertEquals(0, registry.size());
        assertEquals(Collections.singletonList(R.string.share_open_failed), shownToasts());
    }

    @Test
    public void anExceptionAfterTheRegistrationRemovesTheKeyAndClosesTheSourceOnce() {
        UnclaimedShares failing = mock(UnclaimedShares.class);
        doThrow(new IllegalStateException("boom")).when(failing).registered(anyString(), anyLong());
        when(yaacc.getUnclaimedShares()).thenReturn(failing);

        ShareService.Prepared result = prepare();

        assertNull(result);
        assertEquals(0, registry.size());
        verify(source, times(1)).close();
        assertEquals(Collections.singletonList(R.string.share_open_failed), shownToasts());
    }

    @Test
    public void aProviderThatCannotOpenTheFileEndsTheShareBeforeTheServerIsAsked() throws Exception {
        when(resolver.openAssetFileDescriptor(nullable(Uri.class), eq("r"))).thenReturn(null);

        assertNull(prepare());

        verify(upnpClient, never()).isHttpServerRunning();
        assertEquals(0, registry.size());
        assertEquals(Collections.singletonList(R.string.share_open_failed), shownToasts());
    }

    @Test
    public void aProviderCallRefusedAsBusyShowsTheBusyTextAndRegistersNothing() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(resolver.openAssetFileDescriptor(nullable(Uri.class), eq("r"))).thenAnswer(invocation -> {
            inside.countDown();
            release.await(30, TimeUnit.SECONDS);
            throw new SecurityException("grant gone");
        });
        AtomicReference<ShareService.Prepared> first = new AtomicReference<>();
        Thread hanging = new Thread(() -> {
            // static mocks are bound to the thread that makes them
            try (MockedStatic<Uri> uris = mockStatic(Uri.class)) {
                stubUriParse(uris);
                first.set(prepare());
            }
        });
        hanging.start();
        try {
            assertTrue(inside.await(5, TimeUnit.SECONDS));

            // the same provider again while its first call is still in flight
            ShareService.Prepared second = prepare();

            assertNull(second);
            verify(resolver, times(1)).openAssetFileDescriptor(nullable(Uri.class), eq("r"));
            assertEquals(0, registry.size());
            assertEquals(Collections.singletonList(R.string.share_busy), shownToasts());
        } finally {
            release.countDown();
            hanging.join(10_000L);
        }
        assertNull(first.get());
        assertFalse(hanging.isAlive());
    }

    // ---- success

    @Test
    public void aSuccessRegistersTheFileCountsItAsUnclaimedAndPlaysTheProxyUrl() {
        ShareService.Prepared result = prepare();

        assertNotNull(result);
        String key = result.getKey();
        assertNotNull(key);
        assertTrue(ShareRegistry.isWellFormedKey(key));
        ShareRegistry.Entry entry = registry.lookup(key);
        assertNotNull(entry);
        assertEquals(ShareRegistry.Kind.LOCAL_FILE, entry.getKind());
        assertEquals("the registry holds the source", source, entry.getTarget());
        assertEquals("audio/mpeg", entry.getMimeType());
        assertEquals("http://" + IP + ":" + YaaccUpnpServerService.PORT + "/proxy/" + key,
                result.getItem().getUri().toString());
        assertEquals("audio/mpeg", result.getItem().getMimeType());
        assertEquals("song.mp3", result.getItem().getTitle());
        assertEquals(1, unclaimed.size());
        assertEquals(Collections.singletonList(key), unclaimed.dueForRemoval(FAR_FUTURE));
        assertTrue(shownToasts().isEmpty());
    }

    @Test
    public void afterASuccessTheRegistryNotTheCallerOwnsTheSource() {
        ShareService.Prepared result = prepare();
        assertNotNull(result);
        verify(source, never()).close();

        assertTrue(registry.remove(result.getKey()));

        verify(source, times(1)).close();
    }

    @Test
    public void theAllowedAddressesComeFromTheReceiversTheUserPickedNotFromTheStoredSelection() {
        Set<String> picked = Collections.singleton(UpnpClient.LOCAL_UID);

        ShareService.Prepared result = prepare("audio/mpeg", picked);

        assertNotNull(result);
        Set<String> allowed = registry.lookup(result.getKey()).getAllowedAddresses();
        assertTrue(allowed.contains(IP));
        assertTrue(allowed.contains("127.0.0.1"));
        verify(upnpClient, never()).getReceiverDeviceIds();
    }

    @Test
    public void withoutAReceiverNobodyMayFetch() {
        ShareService.Prepared result = prepare();

        assertNotNull(result);
        assertTrue(registry.lookup(result.getKey()).getAllowedAddresses().isEmpty());
    }

    @Test
    public void theOfflineAddressIsStillAnAddress() {
        // InterfaceResolutionHelper returns "0.0.0.0", not null, when the phone is offline
        ip = "0.0.0.0";

        ShareService.Prepared result = prepare();

        assertNotNull(result);
        assertEquals("http://0.0.0.0:" + YaaccUpnpServerService.PORT + "/proxy/" + result.getKey(),
                result.getItem().getUri().toString());
    }

    @Test
    public void anIpv6AddressIsBracketedInTheItemUrl() {
        ip = "fd00::1";

        ShareService.Prepared result = prepare();

        assertNotNull(result);
        assertEquals("http://[fd00::1]:" + YaaccUpnpServerService.PORT + "/proxy/" + result.getKey(),
                result.getItem().getUri().toString());
    }

    @Test
    public void aVideoAndAnImageGetTheirOwnItemClass() {
        ShareService.Prepared video = prepare("video/mp4", Collections.<String>emptySet());
        ShareService.Prepared image = prepare("image/png", Collections.<String>emptySet());

        assertNotNull(video);
        assertNotNull(image);
        assertEquals("video/mp4", video.getItem().getMimeType());
        assertEquals("image/png", image.getItem().getMimeType());
    }
}
