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
package de.yaacc.upnp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.widget.Toast;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.share.HttpLinkTarget;
import de.yaacc.share.ShareRegistry;
import de.yaacc.share.UnclaimedShares;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import de.yaacc.util.InterfaceResolutionHelper;

/**
 * Characterization of the registration part of {@code UpnpClient#createHttpLinkShare} (private, so
 * called by reflection), which had no unit test: the link is registered for the CHOSEN receivers,
 * counted as unclaimed and handed out as a proxy URL; with no address, no server or no room the
 * answer is null (the caller then plays the link directly) and nothing stays registered.
 * <p>
 * Written against the code as it was before Phase 4 replaced its helpers; the same cases must pass
 * afterwards. Android types are mocks, the registry and the claim bookkeeping are real.
 */
public class UpnpClientLinkShareTest {

    private static final String LINK = "http://media.example.com/stream/song.mp3";
    private static final String IP = "192.168.1.5";

    private final SecureRandom random = new SecureRandom();
    private final List<Runnable> posted = new CopyOnWriteArrayList<>();
    private final List<Integer> toasts = new ArrayList<>();

    private UpnpClient client;
    private ShareRegistry registry;
    private UnclaimedShares unclaimed;
    private String ip = IP;
    private boolean serverRunning = true;

    private MockedStatic<InterfaceResolutionHelper> network;
    private MockedStatic<Toast> toastStatic;
    private MockedConstruction<Handler> handlers;

    @Before
    public void setUp() {
        network = mockStatic(InterfaceResolutionHelper.class);
        network.when(() -> InterfaceResolutionHelper.getIpAddress(any())).thenAnswer(invocation -> ip);
        network.when(() -> InterfaceResolutionHelper.getBindAddresses(any()))
                .thenAnswer(invocation -> Collections.<InetAddress>emptyList().iterator());
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

        Yaacc yaacc = mock(Yaacc.class);
        when(yaacc.getApplicationContext()).thenReturn(yaacc);
        registry = new ShareRegistry(Yaacc::shareClockMillis, random::nextBytes, 2);
        unclaimed = new UnclaimedShares();
        when(yaacc.getShareRegistry()).thenReturn(registry);
        when(yaacc.getUnclaimedShares()).thenReturn(unclaimed);
        client = spy(new UpnpClient());
        doReturn(yaacc).when(client).getContext();
        doReturn(true).when(client).isHttpServerRunning();
    }

    @After
    public void tearDown() {
        handlers.close();
        toastStatic.close();
        network.close();
    }

    private String share(String link, String mimeType, Set<String> receivers) throws Exception {
        doReturn(serverRunning).when(client).isHttpServerRunning();
        Method method = UpnpClient.class.getDeclaredMethod("createHttpLinkShare", String.class, String.class,
                Set.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(client, link, mimeType, receivers);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private String share() throws Exception {
        return share(LINK, "audio/mpeg", Collections.<String>emptySet());
    }

    private List<Integer> shownToasts() {
        for (Runnable runnable : new ArrayList<>(posted)) {
            runnable.run();
        }
        posted.clear();
        return new ArrayList<>(toasts);
    }

    private static String keyOf(String proxyUrl) {
        return proxyUrl.substring(proxyUrl.lastIndexOf('/') + 1);
    }

    private ShareRegistry.Entry runningEntry() {
        return new ShareRegistry.Entry(ShareRegistry.Kind.LOCAL_FILE, (AutoCloseable) () -> { },
                "audio/mpeg", Collections.<String>emptySet(), 10_000L, 100_000L);
    }

    @Test
    public void aLinkIsRegisteredCountedAsUnclaimedAndHandedOutAsTheProxyUrl() throws Exception {
        String proxyUrl = share();

        assertNotNull(proxyUrl);
        String key = keyOf(proxyUrl);
        assertEquals("http://" + IP + ":" + YaaccUpnpServerService.PORT + "/proxy/" + key, proxyUrl);
        assertTrue(ShareRegistry.isWellFormedKey(key));
        ShareRegistry.Entry entry = registry.lookup(key);
        assertNotNull(entry);
        assertEquals(ShareRegistry.Kind.HTTP_LINK, entry.getKind());
        assertEquals(HttpLinkTarget.of(LINK), entry.getTarget());
        assertEquals("audio/mpeg", entry.getMimeType());
        assertEquals(Collections.singletonList(key), unclaimed.dueForRemoval(Long.MAX_VALUE / 2));
    }

    @Test
    public void withoutANetworkAddressNothingIsRegisteredAndTheLinkIsUsedDirectly() throws Exception {
        ip = null;

        assertNull(share());

        assertEquals(0, registry.size());
        assertEquals(0, unclaimed.size());
    }

    @Test
    public void aBlankNetworkAddressIsNoAddress() throws Exception {
        ip = "  ";

        assertNull(share());

        assertEquals(0, registry.size());
    }

    @Test
    public void aServerThatIsNotReadyMeansTheLinkIsUsedDirectly() throws Exception {
        serverRunning = false;

        assertNull(share());

        assertEquals(0, registry.size());
        assertEquals(0, unclaimed.size());
    }

    @Test
    public void aFullRegistryMeansTheLinkIsUsedDirectlyAndTheUserIsTold() throws Exception {
        String first = registry.register(runningEntry());
        registry.register(runningEntry());

        assertNull(share());

        assertEquals(2, registry.size());
        assertNotNull(registry.lookup(first));
        assertEquals(0, unclaimed.size());
        assertEquals(Collections.singletonList(R.string.share_registry_full), shownToasts());
    }

    @Test
    public void aFullRegistryEvictsAReleasedLeftoverAndRegistersTheLink() throws Exception {
        String leftoverKey = registry.register(runningEntry());
        registry.register(runningEntry());
        registry.release(leftoverKey);

        String proxyUrl = share();

        assertNotNull(proxyUrl);
        assertNull(registry.lookup(leftoverKey));
        assertNotNull(registry.lookup(keyOf(proxyUrl)));
        assertTrue(shownToasts().isEmpty());
    }

    @Test
    public void somethingThatIsNotAnHttpLinkIsNotRegistered() throws Exception {
        assertNull(share("ftp://media.example.com/song.mp3", "audio/mpeg", Collections.<String>emptySet()));
        assertNull(share("not a link", "audio/mpeg", Collections.<String>emptySet()));

        assertEquals(0, registry.size());
    }

    @Test
    public void theAllowedAddressesComeFromTheReceiversGivenNotFromTheStoredSelection() throws Exception {
        String proxyUrl = share(LINK, "audio/mpeg", Collections.singleton(UpnpClient.LOCAL_UID));

        Set<String> allowed = registry.lookup(keyOf(proxyUrl)).getAllowedAddresses();
        assertTrue(allowed.contains(IP));
        assertTrue(allowed.contains("127.0.0.1"));
    }

    @Test
    public void withoutAReceiverNobodyMayFetch() throws Exception {
        String proxyUrl = share();

        assertTrue(registry.lookup(keyOf(proxyUrl)).getAllowedAddresses().isEmpty());
    }

    @Test
    public void theOfflineAddressIsStillAnAddress() throws Exception {
        // InterfaceResolutionHelper returns "0.0.0.0", not null, when the phone is offline
        ip = "0.0.0.0";

        String proxyUrl = share();

        assertEquals("http://0.0.0.0:" + YaaccUpnpServerService.PORT + "/proxy/" + keyOf(proxyUrl), proxyUrl);
    }

    @Test
    public void anIpv6AddressIsBracketedInTheProxyUrl() throws Exception {
        ip = "fd00::1";

        String proxyUrl = share();

        assertEquals("http://[fd00::1]:" + YaaccUpnpServerService.PORT + "/proxy/" + keyOf(proxyUrl), proxyUrl);
    }
}
