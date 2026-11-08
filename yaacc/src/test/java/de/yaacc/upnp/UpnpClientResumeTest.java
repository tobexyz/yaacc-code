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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.fourthline.cling.model.meta.Device;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.upnp.server.NetworkDeviceListener;
import de.yaacc.upnp.server.ResumeRefreshPolicy;
import de.yaacc.upnp.server.YaaccUpnpServerService;

/**
 * What the client does when a screen is resumed (listener check, search) and when a play action finds
 * a selected receiver missing in the registry. Plain JVM: the registry is a set of ids, the waits are
 * recorded instead of slept.
 */
public class UpnpClientResumeTest {

    private static final String KEY = "selected_receivers";

    private UpnpClient client;
    private SharedPreferences.Editor editor;
    private final Set<String> registry = new HashSet<>();
    private final List<Long> sleeps = new ArrayList<>();
    private final AtomicInteger searches = new AtomicInteger();
    private Set<String> cameBackWithTheSearch = new HashSet<>();

    @Before
    public void setUp() {
        Context context = mock(Context.class);
        when(context.getString(anyInt())).thenReturn(KEY);
        SharedPreferences preferences = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putStringSet(nullable(String.class), org.mockito.ArgumentMatchers.any())).thenReturn(editor);
        client = spy(new UpnpClient());
        client.preferences = preferences;
        client.receiverAvailabilityPolicy = new ReceiverAvailabilityPolicy(sleeps::add);
        doReturn(context).when(client).getContext();
        registry.add(UpnpClient.LOCAL_UID);
        doAnswer(invocation -> registry.contains((String) invocation.getArgument(0)) ? mock(Device.class) : null)
                .when(client).getDevice(anyString());
        doAnswer(invocation -> {
            searches.incrementAndGet();
            registry.addAll(cameBackWithTheSearch);
            return null;
        }).when(client).searchDevices();
    }

    private void selected(String... ids) {
        when(client.preferences.getStringSet(org.mockito.ArgumentMatchers.eq(KEY), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new HashSet<>(Arrays.asList(ids)));
    }

    private void bindListener(NetworkDeviceListener listener) throws ReflectiveOperationException {
        YaaccUpnpServerService serverService = mock(YaaccUpnpServerService.class);
        when(serverService.getNetworkDeviceListener()).thenReturn(listener);
        Field field = UpnpClient.class.getDeclaredField("yaaccUpnpServerService");
        field.setAccessible(true);
        field.set(client, serverService);
    }

    // ---- receivers missing from the registry ---------------------------------------------------

    @Test
    public void receiversThatAreThereAreReturnedWithoutWaiting() {
        selected("a", "b");
        registry.addAll(Arrays.asList("a", "b"));

        assertEquals(2, client.awaitReceiverDevices().size());

        assertTrue(sleeps.isEmpty());
        assertEquals(0, searches.get());
    }

    @Test
    public void theLocalDeviceIsNeverWaitedFor() {
        selected(UpnpClient.LOCAL_UID);

        assertEquals(1, client.awaitReceiverDevices().size());

        assertTrue(sleeps.isEmpty());
        assertEquals(0, searches.get());
    }

    @Test
    public void aReceiverThatComesBackWithTheSearchIsPlayedAndStaysSelected() {
        selected("a", "b");
        registry.add("a");
        cameBackWithTheSearch.add("b");

        assertEquals("both receivers", 2, client.awaitReceiverDevices().size());

        assertEquals(1, searches.get());
        assertEquals(Arrays.asList(2500L), sleeps);
        verify(editor, never()).putStringSet(nullable(String.class), org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void aReceiverThatStaysAwayIsDroppedAsBeforeAfterTheLastLook() {
        selected("a", "b");
        registry.add("a");

        assertEquals("only the receiver that is there", 1, client.awaitReceiverDevices().size());

        assertEquals(2, searches.get());
        assertEquals(Arrays.asList(2500L, 2500L), sleeps);
        verify(editor).putStringSet(nullable(String.class), org.mockito.ArgumentMatchers.eq(new HashSet<>(Arrays.asList("a"))));
    }

    @Test
    public void theMainThreadDoesNotWait() {
        selected("a");
        doReturn(true).when(client).isOnMainThread();

        assertEquals(0, client.awaitReceiverDevices().size());

        assertTrue(sleeps.isEmpty());
        assertEquals(0, searches.get());
    }

    // ---- resume -------------------------------------------------------------------------------

    @Test
    public void healthyListenerAndARecentSearchDoNothing() throws ReflectiveOperationException {
        NetworkDeviceListener listener = mock(NetworkDeviceListener.class);
        when(listener.reenableIfStale()).thenReturn(ResumeRefreshPolicy.ListenerCheck.HEALTHY);
        bindListener(listener);
        doReturn(100_000L).when(client).nowMillis();
        client.lastSearchAtMillis = 100_000L - 10_000L;

        client.refreshAfterResumeNow();

        assertEquals(0, searches.get());
    }

    @Test
    public void healthyListenerAndAnOldSearchSearchesAgain() throws ReflectiveOperationException {
        NetworkDeviceListener listener = mock(NetworkDeviceListener.class);
        when(listener.reenableIfStale()).thenReturn(ResumeRefreshPolicy.ListenerCheck.HEALTHY);
        bindListener(listener);
        doReturn(100_000L).when(client).nowMillis();
        client.lastSearchAtMillis = 100_000L - 31_000L;

        client.refreshAfterResumeNow();

        assertEquals(1, searches.get());
    }

    @Test
    public void noSearchYetSearches() throws ReflectiveOperationException {
        NetworkDeviceListener listener = mock(NetworkDeviceListener.class);
        when(listener.reenableIfStale()).thenReturn(ResumeRefreshPolicy.ListenerCheck.HEALTHY);
        bindListener(listener);
        doReturn(100_000L).when(client).nowMillis();

        client.refreshAfterResumeNow();

        assertEquals(1, searches.get());
    }

    @Test
    public void aListenerThatWasEnabledAgainSearchesEvenAfterARecentSearch() throws ReflectiveOperationException {
        NetworkDeviceListener listener = mock(NetworkDeviceListener.class);
        when(listener.reenableIfStale()).thenReturn(ResumeRefreshPolicy.ListenerCheck.NETWORK_CHANGED);
        bindListener(listener);
        doReturn(100_000L).when(client).nowMillis();
        client.lastSearchAtMillis = 100_000L - 1_000L;

        client.refreshAfterResumeNow();

        verify(listener, times(1)).reenableIfStale();
        assertEquals(1, searches.get());
    }

    @Test
    public void withoutANetworkListenerNothingHappens() {
        client.refreshAfterResumeNow();

        assertEquals(0, searches.get());
    }

    @Test
    public void aFailingCheckDoesNotEscape() throws ReflectiveOperationException {
        NetworkDeviceListener listener = mock(NetworkDeviceListener.class);
        when(listener.reenableIfStale()).thenThrow(new IllegalStateException("no interface"));
        bindListener(listener);

        client.refreshAfterResumeNow();

        assertEquals(0, searches.get());
    }
}
