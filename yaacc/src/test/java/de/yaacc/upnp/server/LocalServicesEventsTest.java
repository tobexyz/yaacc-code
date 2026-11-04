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
package de.yaacc.upnp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.fourthline.cling.model.DefaultServiceManager;
import org.fourthline.cling.model.ServiceManager;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.ProtocolInfos;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import de.yaacc.upnp.server.connectionmanager.ConnectionManagerService;
import de.yaacc.upnp.server.contentdirectory.SafChangeMonitor;
import de.yaacc.upnp.server.contentdirectory.SafDirectory;
import de.yaacc.upnp.server.contentdirectory.SafDirectoryEntry;
import de.yaacc.upnp.server.contentdirectory.YaaccContentDirectory;

/**
 * The events of the services of the local device that report plain state variables (no LastChange): ConnectionManager,
 * ContentDirectory and the Microsoft media receiver registrar. A control point that subscribes gets a valid,
 * complete initial event, and a change of a variable reaches it; nothing throws on subscribe or on change.
 * Run through the real receiving path ({@link ServiceHarness}). Plain JVM.
 */
public class LocalServicesEventsTest {

    private static final int LOCK_TIMEOUT_MILLIS = 1000;

    private final List<ServiceHarness<?>> harnesses = new ArrayList<>();

    @After
    public void tearDown() {
        for (ServiceHarness<?> harness : harnesses) {
            harness.close();
        }
    }

    private <T> ServiceHarness<T> harness(Class<T> type, String name, java.util.concurrent.Callable<T> instance) {
        ServiceHarness<T> harness = new ServiceHarness<>(type, "urn:schemas-upnp-org:service:" + name + ":1", name,
                service -> (ServiceManager<T>) new DefaultServiceManager<T>(service, null) {
                    @Override
                    protected int getLockTimeoutMillis() {
                        return LOCK_TIMEOUT_MILLIS;
                    }

                    @Override
                    protected T createServiceInstance() throws Exception {
                        return instance.call();
                    }
                });
        harnesses.add(harness);
        return harness;
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

    private static boolean anyContains(ServiceHarness<?> h, String text) {
        for (String event : h.sentEvents) {
            if (event.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private YaaccContentDirectory contentDirectory() {
        Context context = mock(Context.class);
        when(context.getSharedPreferences(any(), anyInt())).thenReturn(mock(SharedPreferences.class));
        return new YaaccContentDirectory(context, 0);
    }

    // --- ConnectionManager

    @Test
    public void connectionManagerSendsItsThreeVariablesAsInitialEvent() throws Exception {
        ServiceHarness<ConnectionManagerService> h = harness(ConnectionManagerService.class, "ConnectionManager",
                () -> new ConnectionManagerService(new ProtocolInfos(new ProtocolInfo("http-get:*:audio/mpeg:*")), new ProtocolInfos()));

        assertEquals(200, h.subscribeExchange());

        await("the initial event", () -> h.sentEvents.size() == 1);
        String initial = h.sentEvents.get(0);
        assertTrue(initial, initial.contains("<SourceProtocolInfo>http-get:*:audio/mpeg:*</SourceProtocolInfo>"));
        assertTrue(initial, initial.contains("<SinkProtocolInfo></SinkProtocolInfo>"));
        assertTrue(initial, initial.contains("<CurrentConnectionIDs>0</CurrentConnectionIDs>"));
    }

    @Test
    public void connectionManagerWithoutASinkListIsStillAValidEvent() throws Exception {
        // the sink list used to be null
        ServiceHarness<ConnectionManagerService> h = harness(ConnectionManagerService.class, "ConnectionManager",
                () -> new ConnectionManagerService(new ProtocolInfos(new ProtocolInfo("http-get:*:audio/mpeg:*")), null));

        assertEquals(200, h.subscribeExchange());

        await("the initial event", () -> h.sentEvents.size() == 1);
        assertTrue(h.sentEvents.get(0), h.sentEvents.get(0).contains("<SinkProtocolInfo></SinkProtocolInfo>"));
    }

    // --- ContentDirectory

    @Test
    public void contentDirectorySendsItsSystemUpdateIdAsInitialEvent() throws Exception {
        ServiceHarness<YaaccContentDirectory> h = harness(YaaccContentDirectory.class, "ContentDirectory", this::contentDirectory);

        assertEquals(200, h.subscribeExchange());

        await("the initial event", () -> h.sentEvents.size() == 1);
        assertTrue(h.sentEvents.get(0), h.sentEvents.get(0).contains("<SystemUpdateID>0</SystemUpdateID>"));
        assertFalse("only the evented variable: " + h.sentEvents.get(0), h.sentEvents.get(0).contains("SortCapabilities"));
    }

    @Test
    public void aContentChangeSendsTheNewSystemUpdateId() throws Exception {
        ServiceHarness<YaaccContentDirectory> h = harness(YaaccContentDirectory.class, "ContentDirectory", this::contentDirectory);
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> h.sentEvents.size() == 1);

        h.service().changeSystemUpdateID();

        await("the new id", () -> anyContains(h, "<SystemUpdateID>1</SystemUpdateID>"));
        assertEquals("1", String.valueOf(h.service().getSystemUpdateID().getValue()));
    }

    @Test
    public void aSafChangeFoundByTheCheckReachesASubscriberAsASystemUpdateIdEvent() throws Exception {
        ServiceHarness<YaaccContentDirectory> h = harness(YaaccContentDirectory.class, "ContentDirectory", this::contentDirectory);
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> h.sentEvents.size() == 1);
        AtomicReference<SafDirectory> folder = new AtomicReference<>(safListing("a.mp4"));
        SafChangeMonitor monitor = new SafChangeMonitor(key -> folder.get(), () -> {
            h.service().changeSystemUpdateID();
            return true;
        }, delayMillis -> { }, System::currentTimeMillis);
        List<String> roots = Collections.singletonList("content://p/tree/lib/document/lib");

        monitor.runPass(roots); // the baseline
        monitor.runPass(roots); // nothing new
        assertEquals("0", String.valueOf(h.service().getSystemUpdateID().getValue()));
        folder.set(safListing("a.mp4", "b.mp4")); // e.g. a torrent finished in the shared folder
        monitor.runPass(roots);

        await("the event of the change", () -> anyContains(h, "<SystemUpdateID>1</SystemUpdateID>"));
        monitor.runPass(roots); // the same listing again: no second event
        assertEquals("1", String.valueOf(h.service().getSystemUpdateID().getValue()));
    }

    private static SafDirectory safListing(String... names) {
        List<SafDirectoryEntry> entries = new ArrayList<>();
        for (String name : names) {
            entries.add(new SafDirectoryEntry(name, "doc:" + name, "video/mp4", 1, 10, false));
        }
        return SafDirectory.listed("content://p/tree/lib/document/lib", true, entries);
    }

    @Test
    public void changesInQuickSuccessionAreNotDroppedTheLastIdArrives() throws Exception {
        ServiceHarness<YaaccContentDirectory> h = harness(YaaccContentDirectory.class, "ContentDirectory", this::contentDirectory);
        assertEquals(200, h.subscribeExchange());
        await("the initial event", () -> h.sentEvents.size() == 1);

        h.service().changeSystemUpdateID();
        h.service().changeSystemUpdateID();
        h.service().changeSystemUpdateID();

        await("the last id", () -> anyContains(h, "<SystemUpdateID>3</SystemUpdateID>"));
    }

    @Test
    public void systemUpdateIdIsAnEventedVariableWithoutARateLimitThatCouldDropIt() {
        ServiceHarness<YaaccContentDirectory> h = harness(YaaccContentDirectory.class, "ContentDirectory", this::contentDirectory);

        assertTrue(h.localService.getStateVariable("SystemUpdateID").getEventDetails().isSendEvents());
        assertEquals(0, h.localService.getStateVariable("SystemUpdateID").getEventDetails().getEventMaximumRateMilliseconds());
    }

    @Test
    public void aSubscriberThatThrowsDoesNotMakeAContentChangeFail() throws Exception {
        ServiceHarness<YaaccContentDirectory> h = harness(YaaccContentDirectory.class, "ContentDirectory", this::contentDirectory);
        h.localService.getManager().getPropertyChangeSupport().addPropertyChangeListener(event -> {
            if (ServiceManager.EVENTED_STATE_VARIABLES.equals(event.getPropertyName())) {
                throw new IllegalStateException("subscriber is gone");
            }
        });
        assertEquals(200, h.subscribeExchange());

        h.service().changeSystemUpdateID(); // must not throw

        assertEquals(1L, (long) h.service().getSystemUpdateID().getValue());
    }

    @Test
    public void aContentChangeWithoutSubscribersIsHarmless() {
        ServiceHarness<YaaccContentDirectory> h = harness(YaaccContentDirectory.class, "ContentDirectory", this::contentDirectory);

        h.service().changeSystemUpdateID();

        assertEquals(1L, (long) h.service().getSystemUpdateID().getValue());
        assertTrue(h.sentEvents.isEmpty());
    }

    // --- Microsoft media receiver registrar

    @Test
    public void theMediaReceiverRegistrarSendsItsFourUpdateIdsAsInitialEvent() throws Exception {
        ServiceHarness<YaaccMediaReceiverRegistrarService> h = new ServiceHarness<>(YaaccMediaReceiverRegistrarService.class,
                "urn:microsoft.com:service:X_MS_MediaReceiverRegistrar:1", "X_MS_MediaReceiverRegistrar",
                service -> new DefaultServiceManager<YaaccMediaReceiverRegistrarService>(service, null) {
                    @Override
                    protected int getLockTimeoutMillis() {
                        return LOCK_TIMEOUT_MILLIS;
                    }

                    @Override
                    protected YaaccMediaReceiverRegistrarService createServiceInstance() {
                        return new YaaccMediaReceiverRegistrarService();
                    }
                });
        harnesses.add(h);

        assertEquals(200, h.subscribeExchange());

        await("the initial event", () -> h.sentEvents.size() == 1);
        for (String variable : new String[]{"AuthorizationGrantedUpdateID", "AuthorizationDeniedUpdateID",
                "ValidationSucceededUpdateID", "ValidationRevokedUpdateID"}) {
            assertTrue(variable + " in " + h.sentEvents.get(0), h.sentEvents.get(0).contains("<" + variable + ">0</" + variable + ">"));
        }
    }
}
