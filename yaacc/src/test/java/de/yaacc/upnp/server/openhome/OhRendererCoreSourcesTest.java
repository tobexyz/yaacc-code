/*
 *
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
package de.yaacc.upnp.server.openhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.meta.LocalService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastRendererOutput;
import de.yaacc.multiroom.SnapcastRendererSources;
import de.yaacc.multiroom.SnapcastRendererSources.Entry;
import de.yaacc.multiroom.SnapcastRendererSources.GroupRef;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.upnp.server.ServiceHarness;

/**
 * The Product service of the renderer with the Snapcast groups as sources (task 12.5), as a control point sees it: the list in
 * {@code SourceXml}, the current one in {@code SourceIndex}, the three ways to choose, the events when the list or the current
 * source changes, and a refusal that leaves everything as it was. Plain JVM against the real receiving path.
 */
public class OhRendererCoreSourcesTest {

    private static final SnapcastRendererSources.Labels LABELS = new SnapcastRendererSources.Labels("Snapcast group", "Snapcast group (not available)");

    private static String id(String group) {
        return SnapcastGroupDevice.idOf(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), group);
    }

    /** A provider that the test moves by hand; choosing moves its current source like the real one writes the setting. */
    private static final class FakeProvider implements OhSourceProvider {
        final List<GroupRef> groups = new ArrayList<>();
        String chosenGroup; // null: this device
        boolean refuse;
        final List<Entry> chosen = new ArrayList<>();

        @Override
        public SnapcastRendererSources.Snapshot snapshot() {
            return SnapcastRendererSources.build(chosenGroup == null ? SnapcastRendererOutput.read(k -> null)
                    : SnapcastRendererOutput.read(k -> k.equals(SnapcastRendererOutput.KEY_OUTPUT) ? "group" : chosenGroup), groups, LABELS);
        }

        @Override
        public boolean select(Entry entry) {
            chosen.add(entry);
            if (refuse) {
                return false;
            }
            chosenGroup = entry.receiverId();
            return true;
        }
    }

    private final FakeProvider provider = new FakeProvider();
    private OhRendererCore core;
    private ServiceHarness<OhServiceState> product;
    private final List<ServiceHarness<OhServiceState>> all = new ArrayList<>();

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        core = new OhRendererCore(org.mockito.Mockito.mock(OhPlayback.class), (runnable, millis) -> runnable.run(),
                new OhRendererCore.Details("yaacc.de", "https://www.yaacc.de", "YAACC", "Room", "Test renderer"), "http-get:*:audio/mpeg:*");
        List<LocalService<?>> services = core.buildServices(2000);
        product = new ServiceHarness<>((LocalService<OhServiceState>) services.get(0), OhServiceDefs.product().serviceType(), "Product");
        all.add(product);
    }

    @After
    public void tearDown() {
        for (ServiceHarness<OhServiceState> harness : all) {
            harness.close();
        }
    }

    private static String value(StreamResponseMessage answer, String name) {
        assertTrue(ServiceHarness.describe(answer), ServiceHarness.succeeded(answer));
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(answer.getBodyString());
        assertTrue(name + " in " + answer.getBodyString(), m.find());
        return m.group(1);
    }

    private static void assertFault(int code, StreamResponseMessage answer) {
        assertEquals(500, answer.getOperation().getStatusCode());
        assertTrue(answer.getBodyString(), answer.getBodyString().contains("<errorCode>" + code + "</errorCode>"));
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("not within 5 s: " + what);
            }
            Thread.sleep(5);
        }
    }

    private int sourceCount() {
        return Integer.parseInt(value(product.soap("SourceCount"), "Value"));
    }

    private int sourceIndex() {
        return Integer.parseInt(value(product.soap("SourceIndex"), "Value"));
    }

    private void withGroups() {
        provider.groups.add(new GroupRef(id("g1"), "Kitchen"));
        provider.groups.add(new GroupRef(id("g2"), "Attic"));
        core.setSourceProvider(provider);
        core.refreshSources();
    }

    // ---- the list

    @Test
    public void withoutAProviderTheListIsTodaysOneSource() {
        assertEquals(1, sourceCount());
        assertEquals(0, sourceIndex());
        assertTrue(value(product.soap("SourceXml"), "Value").contains("&lt;SystemName&gt;Playlist&lt;/SystemName&gt;"));
    }

    @Test
    public void aProviderWithoutGroupsKeepsTheSingleSourceOfToday() {
        core.setSourceProvider(provider);

        assertEquals(1, sourceCount());
        assertEquals("the very text of today", OhSourceXml_playlistOnly(), value(product.soap("SourceXml"), "Value"));
    }

    private static String OhSourceXml_playlistOnly() {
        return de.yaacc.openhome.OhEscape.xml(de.yaacc.openhome.OhSourceXml.playlistOnly());
    }

    @Test
    public void theGroupsAreListedAfterThisDeviceAndTheListIsReadByIndex() {
        withGroups();

        assertEquals(3, sourceCount());
        String xml = value(product.soap("SourceXml"), "Value");
        assertTrue(xml, xml.indexOf("Playlist") < xml.indexOf("Attic") && xml.indexOf("Attic") < xml.indexOf("Kitchen"));
        assertFalse("no id and no address in the list", xml.contains("g1") || xml.contains("192.168") || xml.contains("1705"));
        StreamResponseMessage first = product.soap("Source", "Index", "1");
        assertEquals("Attic", value(first, "Name"));
        assertEquals("Playlist", value(first, "Type"));
        assertEquals("1", value(first, "Visible"));
        assertTrue(value(first, "SystemName").matches("snapcast-[0-9a-f]{16}"));
        assertFault(402, product.soap("Source", "Index", "3"));
    }

    // ---- choosing a source

    @Test
    public void setSourceIndexChoosesTheGroupAndTheIndexFollows() {
        withGroups();

        assertTrue(ServiceHarness.succeeded(product.soap("SetSourceIndex", "Value", "2")));

        assertEquals(1, provider.chosen.size());
        assertEquals(id("g1"), provider.chosen.get(0).receiverId());
        assertEquals(2, sourceIndex());
        assertEquals("Kitchen", value(product.soap("Source", "Index", String.valueOf(sourceIndex())), "Name"));
    }

    @Test
    public void setSourceIndexByNameAndBySystemNameChooseToo() {
        withGroups();
        String token = value(product.soap("Source", "Index", "2"), "SystemName");

        assertTrue(ServiceHarness.succeeded(product.soap("SetSourceIndexByName", "Value", "Attic")));
        assertEquals(1, sourceIndex());
        assertTrue(ServiceHarness.succeeded(product.soap("SetSourceBySystemName", "Value", token)));
        assertEquals(2, sourceIndex());
        assertTrue(ServiceHarness.succeeded(product.soap("SetSourceBySystemName", "Value", "Playlist")));
        assertEquals("this device again", 0, sourceIndex());
        assertEquals(3, provider.chosen.size());
    }

    @Test
    public void aSourceThatIsNotInTheListIsAnInvalidArgumentAndNothingIsChosen() {
        withGroups();

        assertFault(402, product.soap("SetSourceIndex", "Value", "3"));
        // (a negative number never reaches the handler: Cling's unsigned type turns it into 0, which is this device)
        assertFault(402, product.soap("SetSourceIndex", "Value", "4294967295"));
        assertFault(402, product.soap("SetSourceIndexByName", "Value", "Garage"));
        assertFault(402, product.soap("SetSourceBySystemName", "Value", "Radio"));
        assertFault(402, product.soap("SetSourceBySystemName", "Value", id("g1")));

        assertTrue(provider.chosen.isEmpty());
        assertEquals(0, sourceIndex());
    }

    @Test
    public void aRefusedChoiceIsAnInvalidArgumentAndTheControllerStillSeesTheOldSource() {
        withGroups();
        provider.refuse = true;

        assertFault(402, product.soap("SetSourceIndex", "Value", "2"));

        assertEquals(0, sourceIndex());
        assertEquals(3, sourceCount());
    }

    @Test
    public void aGroupThatVanishedBetweenTheListAndTheChoiceRefreshesWhatTheControllerSees() {
        withGroups();
        provider.groups.remove(0); // Attic is gone; nobody told the core yet
        provider.refuse = true; // the provider checks the live groups

        assertFault(402, product.soap("SetSourceIndex", "Value", "1"));

        assertEquals("the list is read again after a refusal, so the controller sees the truth", 2, sourceCount());
    }

    // ---- the list and the current source change

    @Test
    public void whenGroupsComeOrGoTheControllerGetsTheNewListAsAnEvent() throws Exception {
        core.setSourceProvider(provider);
        assertEquals(200, product.subscribeExchange());
        await("initial event", () -> !product.sentEvents.isEmpty());
        int before = product.sentEvents.size();
        String countBefore = value(product.soap("SourceXmlChangeCount"), "Value");

        provider.groups.add(new GroupRef(id("g1"), "Kitchen"));
        core.refreshSources();

        await("source event", () -> product.sentEvents.size() > before);
        String events = String.join("", product.sentEvents.subList(before, product.sentEvents.size()));
        assertTrue(events, events.contains("SourceXml") && events.contains("Kitchen"));
        assertTrue(events, events.contains("SourceCount"));
        assertEquals(2, sourceCount());
        assertFalse("the change count went up", countBefore.equals(value(product.soap("SourceXmlChangeCount"), "Value")));
    }

    @Test
    public void aChangeOfTheCurrentSourceAloneIsAnEventOfTheIndex() throws Exception {
        withGroups();
        assertEquals(200, product.subscribeExchange());
        await("initial event", () -> !product.sentEvents.isEmpty());
        Thread.sleep(50);
        int before = product.sentEvents.size();

        provider.chosenGroup = id("g1"); // the settings screen changed the output
        core.refreshSources();

        await("index event", () -> product.sentEvents.size() > before);
        String events = String.join("", product.sentEvents.subList(before, product.sentEvents.size()));
        assertTrue(events, events.contains("SourceIndex"));
        assertEquals(2, sourceIndex());
        assertEquals("Kitchen", value(product.soap("Source", "Index", "2"), "Name"));
    }

    @Test
    public void aRefreshThatChangesNothingSendsNothing() throws Exception {
        withGroups();
        assertEquals(200, product.subscribeExchange());
        await("initial event", () -> !product.sentEvents.isEmpty());
        Thread.sleep(100);
        int before = product.sentEvents.size();
        String countBefore = value(product.soap("SourceXmlChangeCount"), "Value");

        core.refreshSources();
        core.refreshSources();
        Thread.sleep(150);

        assertEquals(before, product.sentEvents.size());
        assertEquals(countBefore, value(product.soap("SourceXmlChangeCount"), "Value"));
    }

    @Test
    public void aProviderThatFailsLeavesTheListAsItWas() {
        withGroups();
        OhSourceProvider broken = new OhSourceProvider() {
            @Override
            public SnapcastRendererSources.Snapshot snapshot() {
                throw new IllegalStateException("hub");
            }

            @Override
            public boolean select(Entry entry) {
                throw new IllegalStateException("hub");
            }
        };
        core.setSourceProvider(broken);
        core.refreshSources();

        assertEquals(3, sourceCount());
        assertFault(402, product.soap("SetSourceIndex", "Value", "1"));
    }

    @Test
    public void standbyAndTheOtherActionsAreUntouched() {
        withGroups();

        assertEquals("0", value(product.soap("Standby"), "Value"));
        assertTrue(ServiceHarness.succeeded(product.soap("SetStandby", "Value", "true")));
        assertEquals("1", value(product.soap("Standby"), "Value"));
        assertEquals("choosing a source is not a wake-up or a standby", 3, sourceCount());
        assertEquals("Test renderer", value(product.soap("Product"), "Name"));
    }

    @Test
    public void theListIsReadBackByTheControllerCodeOfTheAppAndAHostileNameCanNotAddASource() {
        provider.groups.add(new GroupRef(id("g1"), "</Name><Type>Radio</Type></Source><Source><Name>x"));
        provider.groups.add(new GroupRef(id("g2"), "Kitchen & \"Hall\" <b>"));
        core.setSourceProvider(provider);

        String xml = core.stateValue("product", "SourceXml");
        java.util.List<de.yaacc.openhome.OhSources.Source> read = de.yaacc.openhome.OhSources.parse(xml);

        assertEquals("three sources, the name did not open a fourth: " + xml, 3, read.size());
        assertEquals("Playlist", read.get(0).name);
        assertEquals("Playlist", de.yaacc.openhome.OhSources.findPlaylist(read).type);
        assertEquals("the first source of the type Playlist is this device", 0, de.yaacc.openhome.OhSources.findPlaylist(read).index);
        for (de.yaacc.openhome.OhSources.Source source : read) {
            assertEquals(source.name, "Playlist", source.type);
            assertTrue(source.visible);
        }
        assertTrue(read.get(1).name.contains("Radio") || read.get(2).name.contains("Radio"));
    }
}
