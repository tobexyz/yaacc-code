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
package de.yaacc.yxc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The monitor with a fake clock, a fake event source and fake devices: lifetime, polling, debounce, trust, failures.
 * Devices: {@code 192.0.2.7} speaks the protocol (its description is the captured one, UDN suffix 00000000000c),
 * {@code 192.0.2.9} is some other renderer.
 */
public class YxcMonitorTest {
    private static final String YXC_UDN = "uuid:9ab0c000-f668-11de-9976-00000000000c";
    private static final String YXC_URL = "http://192.0.2.7:49154/desc.xml";
    private static final String OTHER_UDN = "uuid:other";
    private static final String OTHER_URL = "http://192.0.2.9:49154/desc.xml";
    private static final String OTHER_DESCRIPTION =
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device><friendlyName>x</friendlyName></device></root>";

    private final FakeClock clock = new FakeClock();
    private final List<FakeSource> sources = new ArrayList<>();
    private boolean sourceFails;
    private boolean transportFails;
    private Runnable duringFetch;
    private final QueueExecutor executor = new QueueExecutor();
    private final Map<String, FakeDevice> devices = new HashMap<>();
    private final List<Integer> eventPorts = new ArrayList<>();
    private final List<String> fetches = new ArrayList<>();
    private final List<String> notified = new ArrayList<>();
    private final Map<String, YxcDeviceState> last = new HashMap<>();
    private final YxcMonitor.Listener listener = (udn, state) -> {
        notified.add(udn);
        last.put(udn, state);
    };

    private final YxcMonitor monitor = new YxcMonitor(
            new YxcDiscovery(url -> {
                fetches.add(url);
                if (duringFetch != null) {
                    Runnable hook = duringFetch;
                    duringFetch = null;
                    hook.run();
                }
                return url.startsWith("http://192.0.2.7") ? YxcFixtures.read("captured/c_upnpDescription.xml") : OTHER_DESCRIPTION;
            }),
            receiver -> {
                if (sourceFails) {
                    throw new IOException("simulated");
                }
                FakeSource source = new FakeSource(receiver, 40000 + sources.size());
                sources.add(source);
                return source;
            },
            (endpoint, port) -> {
                if (transportFails) {
                    throw new IllegalArgumentException("simulated: not an address");
                }
                eventPorts.add(port);
                return devices.get(endpoint.getHost());
            },
            executor,
            clock);

    // ---- fakes

    /** Runs work at once, or, when deferred, only when the test says so: a read that is "in flight". */
    private static final class QueueExecutor implements Executor {
        boolean deferred;
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            if (deferred) {
                queue.add(command);
            } else {
                command.run();
            }
        }

        void runAll() {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }

    private static final class FakeClock implements YxcMonitor.Scheduler {
        private long now;
        private final List<Task> tasks = new ArrayList<>();

        private static final class Task implements YxcMonitor.Cancellable {
            final long at;
            final Runnable run;
            boolean cancelled;

            Task(long at, Runnable run) {
                this.at = at;
                this.run = run;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        }

        @Override
        public YxcMonitor.Cancellable schedule(Runnable task, long delayMillis) {
            Task scheduled = new Task(now + delayMillis, task);
            tasks.add(scheduled);
            return scheduled;
        }

        void advance(long millis) {
            long target = now + millis;
            while (true) {
                Task next = null;
                for (Task task : tasks) {
                    if (!task.cancelled && task.at <= target && (next == null || task.at < next.at)) {
                        next = task;
                    }
                }
                if (next == null) {
                    break;
                }
                tasks.remove(next);
                now = Math.max(now, next.at);
                next.run.run();
            }
            now = target;
        }
    }

    private static final class FakeSource implements YxcEventSource {
        final Receiver receiver;
        final int port;
        boolean closed;

        FakeSource(Receiver receiver, int port) {
            this.receiver = receiver;
            this.port = port;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public void close() {
            closed = true;
        }

        void emit(String from, String json) throws Exception {
            receiver.onEvent(InetAddress.getByName(from), new YxcEventParser().parse(json));
        }
    }

    private static final class FakeDevice implements YxcTransport {
        final List<String> calls = new ArrayList<>();
        boolean unreachable;
        Runnable onStatus;
        String status = YxcFixtures.read("captured/c_status_linked_client.json");
        String dist = YxcFixtures.read("captured/c_distInfo_client.json");
        String play = YxcFixtures.read("captured/c_playInfo.json");
        String tuner = YxcFixtures.read("captured/c_tunerPlayInfo_fm.json");
        String features = YxcFixtures.read("captured/c_features.json");

        @Override
        public String get(String path) throws IOException {
            calls.add(path);
            if (unreachable) {
                throw new java.net.SocketTimeoutException("simulated");
            }
            switch (path) {
                case "main/getStatus":
                    if (onStatus != null) {
                        Runnable hook = onStatus;
                        onStatus = null;
                        hook.run();
                    }
                    return status;
                case "dist/getDistributionInfo":
                    return dist;
                case "netusb/getPlayInfo":
                    return play;
                case "tuner/getPlayInfo":
                    return tuner;
                case "system/getFeatures":
                    return features;
                default:
                    return "{\"response_code\":3}";
            }
        }

        @Override
        public String post(String path, String jsonBody) throws IOException {
            return get(path);
        }

        int count(String path) {
            int count = 0;
            for (String call : calls) {
                if (call.equals(path)) {
                    count++;
                }
            }
            return count;
        }
    }

    private FakeDevice yxcDevice() {
        FakeDevice device = new FakeDevice();
        devices.put("192.0.2.7", device);
        return device;
    }

    private static Map<String, String> watch(String... udnAndUrl) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < udnAndUrl.length; i += 2) {
            result.put(udnAndUrl[i], udnAndUrl[i + 1]);
        }
        return result;
    }

    private static String event(String fixture) {
        return YxcFixtures.read("captured/" + fixture + ".json").replace("00000000000A", "00000000000c");
    }

    // ---- reading

    @Test
    public void aSubscriberGetsTheStateOfADeviceThatSpeaksTheProtocol() {
        yxcDevice();

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL, OTHER_UDN, OTHER_URL));

        YxcDeviceState state = last.get(YXC_UDN);
        assertNotNull(state);
        assertTrue(state.isReachable());
        assertTrue(state.isPowerOn());
        assertTrue(state.isLinkInput());
        assertEquals(YxcDistributionInfo.Role.CLIENT, state.getRole());
        assertEquals("the same state is available without a listener", state, monitor.getState(YXC_UDN));
    }

    @Test
    public void theTunerPlayInfoIsReadOnlyWhileTheInputIsTheTuner() {
        FakeDevice device = yxcDevice();
        device.status = YxcFixtures.read("captured/c_status_unlinked.json");
        device.dist = YxcFixtures.read("captured/c_distInfo_none.json");

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));

        YxcDeviceState state = last.get(YXC_UDN);
        assertEquals("tuner", state.getInput());
        assertEquals(1, device.count("tuner/getPlayInfo"));
        assertNotNull(state.getTuner());
        assertEquals("FM 87.55 MHz \u00b7 TEST 1 \u00b7 Test Song - Test Artist", YxcNowPlaying.describe(state));
    }

    @Test
    public void aPlayInfoRefreshReadsNeitherTheZoneNorTheGroup() {
        FakeDevice device = yxcDevice();
        device.status = YxcFixtures.read("captured/c_status_unlinked.json");
        device.dist = YxcFixtures.read("captured/c_distInfo_none.json");
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        int zone = device.count("main/getStatus");
        int dist = device.count("dist/getDistributionInfo");
        int tuner = device.count("tuner/getPlayInfo");

        monitor.refreshPlayInfoNow(YXC_UDN);

        assertEquals(zone, device.count("main/getStatus"));
        assertEquals(dist, device.count("dist/getDistributionInfo"));
        assertEquals("the tuner info is read again while a scan runs", tuner + 1, device.count("tuner/getPlayInfo"));
    }

    @Test
    public void theFeaturesAreReadOnceAndGiveTheBandsAndPresetCounts() {
        FakeDevice device = yxcDevice();

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        monitor.refreshNow(YXC_UDN);
        clock.advance(YxcMonitor.DEBOUNCE_MILLIS + 1);

        assertEquals(1, device.count("system/getFeatures"));
        YxcSourceCapabilities caps = last.get(YXC_UDN).getCapabilities();
        assertTrue(caps.hasFm() && caps.hasDab());
        assertEquals(40, caps.getTunerPresets());
    }

    @Test
    public void theTunerPlayInfoIsNotReadWhileAnotherInputIsSelected() {
        FakeDevice device = yxcDevice();

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));

        assertEquals("the device is on the link input", 0, device.count("tuner/getPlayInfo"));
        assertNull(last.get(YXC_UDN).getTuner());
    }

    @Test
    public void otherRenderersAreLookedAtOnceAndNeverTalkedToAgain() {
        yxcDevice();

        monitor.subscribe(listener, watch(OTHER_UDN, OTHER_URL));
        clock.advance(5 * YxcMonitor.POLL_INTERVAL_MILLIS);

        assertNull(monitor.getState(OTHER_UDN));
        assertFalse(notified.contains(OTHER_UDN));
        assertEquals("one fetch of the description, no more", 1, fetches.size());
    }

    @Test
    public void theDevicesAreToldThePortOfTheEventSource() {
        yxcDevice();

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));

        assertEquals(Collections.singletonList(40000), eventPorts);
    }

    @Test
    public void withoutAnEventSourceThePortIsZeroAndPollingStillWorks() {
        sourceFails = true;
        FakeDevice device = yxcDevice();

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        clock.advance(YxcMonitor.POLL_INTERVAL_MILLIS);

        assertEquals(Collections.singletonList(0), eventPorts);
        assertEquals(2, device.count("main/getStatus"));
    }

    @Test
    public void everyDeviceIsReadCompletelyEveryPollInterval() {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        assertEquals(1, device.count("main/getStatus"));

        clock.advance(YxcMonitor.POLL_INTERVAL_MILLIS - 1);
        assertEquals(1, device.count("main/getStatus"));
        clock.advance(1);

        assertEquals(2, device.count("main/getStatus"));
        assertEquals(2, device.count("dist/getDistributionInfo"));
        assertEquals(2, device.count("netusb/getPlayInfo"));
        clock.advance(YxcMonitor.POLL_INTERVAL_MILLIS);
        assertEquals(3, device.count("main/getStatus"));
    }

    @Test
    public void listenersAreNotCalledWhenNothingChanged() {
        yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        int before = notified.size();

        clock.advance(3 * YxcMonitor.POLL_INTERVAL_MILLIS);

        assertEquals(before, notified.size());
    }

    // ---- events

    @Test
    public void aVolumeEventIsAppliedWithoutAnyCall() throws Exception {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        int calls = device.calls.size();

        sources.get(0).emit("192.0.2.7", event("a_event_volume"));

        assertEquals(calls, device.calls.size());
        assertEquals(Integer.valueOf(new YxcEventParser().parse(event("a_event_volume")).getZones().get("main").getVolume()),
                monitor.getState(YXC_UDN).getVolume());
    }

    @Test
    public void aTrackChangeBurstMakesOneReadOfThePlayInfoAfterTheBurst() throws Exception {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        int before = device.count("netusb/getPlayInfo");
        String json = event("a_event_track_change");

        for (int i = 0; i < 5; i++) {
            sources.get(0).emit("192.0.2.7", json);
            clock.advance(300);
        }
        assertEquals("still in the burst", before, device.count("netusb/getPlayInfo"));
        clock.advance(YxcMonitor.DEBOUNCE_MILLIS);

        assertEquals(before + 1, device.count("netusb/getPlayInfo"));
        assertEquals("only what changed is read", 1, device.count("main/getStatus"));
    }

    @Test
    public void aGroupEventReadsTheGroupAndTheRowFollows() throws Exception {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        assertEquals(YxcDistributionInfo.Role.CLIENT, last.get(YXC_UDN).getRole());
        device.dist = YxcFixtures.read("captured/c_distInfo_none.json");

        sources.get(0).emit("192.0.2.7", event("a_event_dist_info_updated"));
        sources.get(0).emit("192.0.2.7", event("a_event_dist_info_updated"));
        clock.advance(YxcMonitor.DEBOUNCE_MILLIS);

        assertEquals(2, device.count("dist/getDistributionInfo"));
        assertEquals("a client's input follows the link", 2, device.count("main/getStatus"));
        assertEquals(YxcDistributionInfo.Role.NONE, last.get(YXC_UDN).getRole());
    }

    @Test
    public void playTimeTicksAndSignalInfoCostNothing() throws Exception {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        int calls = device.calls.size();

        sources.get(0).emit("192.0.2.7", event("a_event_netusb_play_time"));
        sources.get(0).emit("192.0.2.7", event("a_event_signal_info_updated"));
        clock.advance(YxcMonitor.DEBOUNCE_MILLIS * 2);

        assertEquals(calls, device.calls.size());
    }

    @Test
    public void aPowerEventIsAppliedAndTheZoneIsReadAgain() throws Exception {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        device.status = YxcFixtures.read("captured/a_status_unlinked.json").replace("standby", "on");

        sources.get(0).emit("192.0.2.7", event("a_event_power_on"));
        assertTrue(last.get(YXC_UDN).isPowerOn());
        clock.advance(YxcMonitor.DEBOUNCE_MILLIS);

        assertEquals("tuner", last.get(YXC_UDN).getInput());
        assertEquals(2, device.count("main/getStatus"));
    }

    @Test
    public void eventsFromOtherAddressesAreDropped() throws Exception {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        int calls = device.calls.size();
        YxcDeviceState before = last.get(YXC_UDN);

        sources.get(0).emit("192.0.2.66", event("a_event_volume"));
        sources.get(0).emit("192.0.2.66", event("a_event_dist_info_updated"));
        clock.advance(YxcMonitor.DEBOUNCE_MILLIS);

        assertEquals(calls, device.calls.size());
        assertEquals(before, last.get(YXC_UDN));
    }

    @Test
    public void anEventOfAnotherDeviceIdFromTheRightAddressIsDropped() throws Exception {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        int calls = device.calls.size();
        YxcDeviceState before = last.get(YXC_UDN);

        // the device id of the file is 00000000000A, the device here is ...0c
        sources.get(0).emit("192.0.2.7", YxcFixtures.read("captured/a_event_volume.json"));
        sources.get(0).emit("192.0.2.7", YxcFixtures.read("captured/a_event_dist_info_updated.json"));
        clock.advance(YxcMonitor.DEBOUNCE_MILLIS);

        assertEquals(calls, device.calls.size());
        assertEquals(before, last.get(YXC_UDN));
    }

    @Test
    public void garbageIsDropped() throws Exception {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        int calls = device.calls.size();

        sources.get(0).emit("192.0.2.7", "not json");
        sources.get(0).emit("192.0.2.7", "[1,2,3]");
        clock.advance(YxcMonitor.DEBOUNCE_MILLIS);

        assertEquals(calls, device.calls.size());
    }

    // ---- failures

    @Test
    public void aDeviceThatDoesNotAnswerIsMarkedUnreachableAndComesBack() {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        assertTrue(last.get(YXC_UDN).isReachable());

        device.unreachable = true;
        clock.advance(YxcMonitor.POLL_INTERVAL_MILLIS);
        assertFalse(last.get(YXC_UDN).isReachable());
        assertEquals("the group stays for the row", YxcDistributionInfo.Role.CLIENT, last.get(YXC_UDN).getRole());

        device.unreachable = false;
        clock.advance(YxcMonitor.POLL_INTERVAL_MILLIS);
        assertTrue(last.get(YXC_UDN).isReachable());
        assertTrue(last.get(YXC_UDN).isPowerOn());
    }

    @Test
    public void aDeviceWithoutPlayInfoOrGroupCallsIsStillReachable() {
        FakeDevice device = yxcDevice();
        device.play = "{\"response_code\":3}";
        device.dist = "{\"response_code\":3}";

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));

        YxcDeviceState state = last.get(YXC_UDN);
        assertTrue(state.isReachable());
        assertTrue(state.isPowerOn());
        assertEquals(YxcDistributionInfo.Role.UNKNOWN, state.getRole());
        assertNull(state.getPlayback());
    }

    @Test
    public void aListenerThatThrowsDoesNotStopTheOthers() {
        yxcDevice();
        List<String> second = new ArrayList<>();
        monitor.subscribe((udn, state) -> {
            throw new IllegalStateException("simulated");
        }, Collections.emptyMap());

        monitor.subscribe((udn, state) -> second.add(udn), watch(YXC_UDN, YXC_URL));

        assertEquals(Collections.singletonList(YXC_UDN), second);
    }

    // ---- lifetime

    @Test
    public void theSourceStaysOpenForThirtySecondsAfterTheLastSubscriberLeft() throws Exception {
        yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));

        monitor.unsubscribe(listener);
        clock.advance(YxcMonitor.STOP_DELAY_MILLIS - 1);
        assertFalse(sources.get(0).closed);
        assertTrue(monitor.isActive());
        clock.advance(1);

        assertTrue(sources.get(0).closed);
        assertFalse(monitor.isActive());
    }

    @Test
    public void aSubscriberWithinTheDelayKeepsEverythingRunning() {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        monitor.unsubscribe(listener);
        clock.advance(10_000);

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        clock.advance(YxcMonitor.STOP_DELAY_MILLIS * 2);

        assertEquals("one source for the whole time", 1, sources.size());
        assertFalse(sources.get(0).closed);
        assertTrue("polling went on", device.count("main/getStatus") >= 2);
    }

    @Test
    public void afterTheStopNothingIsPolledAndEventsAreIgnored() throws Exception {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        monitor.unsubscribe(listener);
        clock.advance(YxcMonitor.STOP_DELAY_MILLIS);
        int calls = device.calls.size();
        YxcDeviceState before = last.get(YXC_UDN);

        clock.advance(10 * YxcMonitor.POLL_INTERVAL_MILLIS);
        sources.get(0).emit("192.0.2.7", event("a_event_volume"));
        clock.advance(YxcMonitor.DEBOUNCE_MILLIS);

        assertEquals(calls, device.calls.size());
        assertEquals(before, last.get(YXC_UDN));
    }

    @Test
    public void subscribingAgainAfterTheStopOpensANewSourceAndReadsEverythingAgain() {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        monitor.unsubscribe(listener);
        clock.advance(YxcMonitor.STOP_DELAY_MILLIS);
        int readsBefore = device.count("main/getStatus"); // the first poll fell on the same second as the stop

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));

        assertEquals(2, sources.size());
        assertEquals(java.util.Arrays.asList(40000, 40001), eventPorts);
        assertEquals(readsBefore + 1, device.count("main/getStatus"));
        assertEquals("the description is not fetched again", 1, fetches.size());
    }

    @Test
    public void unsubscribingOneOfTwoKeepsTheMonitorRunning() {
        yxcDevice();
        YxcMonitor.Listener other = (udn, state) -> {
        };
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        monitor.subscribe(other, Collections.emptyMap());

        monitor.unsubscribe(listener);
        clock.advance(YxcMonitor.STOP_DELAY_MILLIS * 3);

        assertTrue(monitor.isActive());
        assertFalse(sources.get(0).closed);
    }

    @Test
    public void aChangedDescriptionUrlIsDiscoveredAgain() {
        yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));

        monitor.subscribe(listener, watch(YXC_UDN, "http://192.0.2.7:49200/desc.xml"));

        assertEquals(2, fetches.size());
    }

    // ---- reads in flight and failures inside a read

    @Test
    public void aTransportThatCanNotBeCreatedDoesNotBlockTheDeviceForever() {
        FakeDevice device = yxcDevice();
        transportFails = true;

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        assertNull("nothing could be read", last.get(YXC_UDN));

        transportFails = false;
        clock.advance(YxcMonitor.POLL_INTERVAL_MILLIS);

        assertTrue("the next poll reads it", last.get(YXC_UDN).isReachable());
        assertEquals(1, device.count("main/getStatus"));
    }

    @Test
    public void anAddressThatChangedWhileItWasLookedAtIsLookedAtAgain() {
        yxcDevice();
        String newUrl = "http://192.0.2.7:49200/desc.xml";
        duringFetch = () -> monitor.subscribe(listener, watch(YXC_UDN, newUrl));

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));

        assertEquals(java.util.Arrays.asList(YXC_URL, newUrl), fetches);
        assertTrue(last.get(YXC_UDN).isReachable());
    }

    @Test
    public void aStopAndStartDuringAReadLeavesOneConsistentRead() {
        FakeDevice device = yxcDevice();
        executor.deferred = true;
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL)); // the read is queued, not run
        monitor.unsubscribe(listener);
        clock.advance(YxcMonitor.STOP_DELAY_MILLIS);
        assertFalse(monitor.isActive());

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL)); // a second read, of the new generation
        executor.runAll();

        assertEquals("the old read does nothing, the new one reads once", 1, device.count("main/getStatus"));
        assertTrue(last.get(YXC_UDN).isReachable());
        // and the device is not stuck: the next poll reads it
        executor.deferred = false;
        clock.advance(YxcMonitor.POLL_INTERVAL_MILLIS);
        assertEquals(2, device.count("main/getStatus"));
    }

    @Test
    public void anEventWhileAReadIsRunningIsReadAfterwards() throws Exception {
        FakeDevice device = yxcDevice();
        // the first read has found the device and is in its first call when a track change comes in
        device.onStatus = () -> {
            try {
                sources.get(0).emit("192.0.2.7", event("a_event_track_change"));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            clock.advance(YxcMonitor.DEBOUNCE_MILLIS);
        };

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));

        assertEquals("the full read and the one the event asked for", 2, device.count("netusb/getPlayInfo"));
        assertEquals(1, device.count("main/getStatus"));
    }

    @Test
    public void anEventBeforeTheFirstReadFinishedIsDroppedWithoutHarm() throws Exception {
        FakeDevice device = yxcDevice();
        executor.deferred = true;
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));

        sources.get(0).emit("192.0.2.7", event("a_event_volume")); // the address is not known yet
        executor.runAll();

        assertTrue(last.get(YXC_UDN).isReachable());
        assertEquals(1, device.count("main/getStatus"));
    }

    @Test
    public void onlyAHandfulOfDevicesAreWatched() {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < YxcMonitor.MAX_DEVICES + 10; i++) {
            many.put("uuid:device-" + i, "http://192.0.2." + (100 + i % 100) + "/d" + i + ".xml");
        }

        monitor.subscribe(listener, many);

        assertEquals(YxcMonitor.MAX_DEVICES, fetches.size());
    }

    @Test
    public void aRestartKeepsTheKnownStateVisibleWhileItIsReadAgain() {
        yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        monitor.unsubscribe(listener);
        clock.advance(YxcMonitor.STOP_DELAY_MILLIS);
        executor.deferred = true;

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL)); // the read is queued, not run yet

        assertNotNull("a row bound now still shows the line", monitor.getState(YXC_UDN));
        executor.runAll();
        assertTrue(monitor.getState(YXC_UDN).isReachable());
    }

    @Test
    public void aVolumeChangeAloneDoesNotWakeTheListeners() throws Exception {
        yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        int before = notified.size();

        sources.get(0).emit("192.0.2.7", event("a_event_volume"));
        sources.get(0).emit("192.0.2.7", event("a_event_mute_on"));

        assertEquals("the row shows neither", before, notified.size());
        assertEquals(Boolean.TRUE, monitor.getState(YXC_UDN).getMute());
    }

    @Test
    public void aPowerChangeWakesTheListeners() throws Exception {
        yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        int before = notified.size();

        sources.get(0).emit("192.0.2.7", event("a_event_power_standby"));

        assertEquals(before + 1, notified.size());
    }

    @Test
    public void onlyTheAddressesOfWatchedDevicesAreAskedFor() throws Exception {
        yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL, OTHER_UDN, OTHER_URL));
        YxcEventSource.Receiver receiver = sources.get(0).receiver;

        assertTrue(receiver.accepts(InetAddress.getByName("192.0.2.7")));
        assertFalse("a renderer without the protocol", receiver.accepts(InetAddress.getByName("192.0.2.9")));
        assertFalse("a stranger", receiver.accepts(InetAddress.getByName("192.0.2.66")));
        assertFalse(receiver.accepts(null));

        monitor.unsubscribe(listener);
        clock.advance(YxcMonitor.STOP_DELAY_MILLIS);
        assertFalse("after the stop nothing is asked for", receiver.accepts(InetAddress.getByName("192.0.2.7")));
    }

    @Test
    public void aTunerFloodCostsNoCallAndNoNotification() throws Exception {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        int calls = device.calls.size();
        int told = notified.size();
        String flood = event("c_event_tuner_play_info_updated");

        for (int i = 0; i < 90; i++) { // 27 s: the real flood was about 3 datagrams a second, and the poll is at 30 s
            sources.get(0).emit("192.0.2.7", flood);
            clock.advance(300);
        }

        assertEquals(calls, device.calls.size());
        assertEquals(told, notified.size());
    }

    @Test
    public void aClientThatLeavesTheGroupIsReadOnceAfterTheDatagram() throws Exception {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        assertTrue(last.get(YXC_UDN).isLinkInput());
        device.status = YxcFixtures.read("captured/a_status_unlinked.json").replace("standby", "on");
        device.dist = YxcFixtures.read("captured/c_distInfo_none.json");

        sources.get(0).emit("192.0.2.7", event("c_event_unlink_input_tuner").replace("00000000000C", "00000000000c"));
        assertEquals("the input of the event shows at once", "tuner", last.get(YXC_UDN).getInput());
        clock.advance(YxcMonitor.DEBOUNCE_MILLIS);

        assertEquals(YxcDistributionInfo.Role.NONE, last.get(YXC_UDN).getRole());
        assertEquals(2, device.count("main/getStatus"));
        assertEquals(2, device.count("dist/getDistributionInfo"));
        assertEquals(2, device.count("netusb/getPlayInfo"));
    }

    @Test
    public void theGroupInfoCanBeReadNowEvenWhileTheMonitorIsStopped() {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        monitor.unsubscribe(listener);
        clock.advance(YxcMonitor.STOP_DELAY_MILLIS);
        assertFalse(monitor.isActive());
        assertEquals(YxcDistributionInfo.Role.CLIENT, monitor.getState(YXC_UDN).getRole());
        device.dist = YxcFixtures.read("captured/c_distInfo_none.json");
        int statusCalls = device.count("main/getStatus");

        monitor.refreshGroupInfoNow(java.util.Arrays.asList(YXC_UDN, OTHER_UDN, "uuid:unknown"));

        assertEquals(YxcDistributionInfo.Role.NONE, monitor.getState(YXC_UDN).getRole());
        assertEquals("only the group is read", statusCalls, device.count("main/getStatus"));
    }

    @Test
    public void aDeviceThatDoesNotAnswerTheGroupReadIsMarkedUnreachable() {
        FakeDevice device = yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        device.unreachable = true;

        monitor.refreshGroupInfoNow(java.util.Collections.singletonList(YXC_UDN));

        assertFalse(monitor.getState(YXC_UDN).isReachable());
    }

    @Test
    public void theLastPlainInputIsRememberedAndNotReplacedByTheLinkInput() throws Exception {
        FakeDevice device = yxcDevice();
        device.status = YxcFixtures.read("captured/a_status_unlinked.json").replace("standby", "on"); // input tuner
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        assertEquals("tuner", monitor.getLastPlainInput(YXC_UDN));

        sources.get(0).emit("192.0.2.7", "{\"main\":{\"input\":\"mc_link\"},\"device_id\":\"00000000000c\"}");
        assertEquals("tuner", monitor.getLastPlainInput(YXC_UDN));
        sources.get(0).emit("192.0.2.7", "{\"main\":{\"input\":\"net_radio\"},\"device_id\":\"00000000000c\"}");

        assertEquals("net_radio", monitor.getLastPlainInput(YXC_UDN));
        assertNull(monitor.getLastPlainInput("uuid:unknown"));
    }

    @Test
    public void theSnapshotHoldsOnlyDevicesThatSpeakTheProtocol() {
        yxcDevice();

        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL, OTHER_UDN, OTHER_URL));

        assertEquals(1, monitor.snapshot().size());
        assertEquals(YXC_UDN, monitor.snapshot().get(0).getUdn());
        assertEquals("192.0.2.7", monitor.snapshot().get(0).getHost());
        assertNotNull(monitor.getEndpoint(YXC_UDN));
        assertNull(monitor.getEndpoint(OTHER_UDN));
    }

    @Test
    public void devicesCanBePreparedWithoutStartingTheMonitor() {
        FakeDevice device = yxcDevice();

        monitor.prepareNow(watch(YXC_UDN, YXC_URL, OTHER_UDN, OTHER_URL));

        assertFalse(monitor.isActive());
        assertTrue("no event source was opened", sources.isEmpty());
        assertEquals(1, monitor.snapshot().size());
        assertEquals(YxcDistributionInfo.Role.UNKNOWN, monitor.snapshot().get(0).getState().getRole());
        assertTrue("nothing was read from the device yet", device.calls.isEmpty());

        monitor.refreshGroupInfoNow(java.util.Collections.singletonList(YXC_UDN));

        assertEquals(YxcDistributionInfo.Role.CLIENT, monitor.getState(YXC_UDN).getRole());
        assertEquals(1, device.calls.size());
        monitor.prepareNow(watch(YXC_UDN, YXC_URL));
        assertEquals("the verdict is remembered", 2, fetches.size());
    }

    @Test
    public void theReadsForASendStopWhenTheBudgetIsUsedUp() {
        FakeDevice device = yxcDevice();
        monitor.prepareNow(watch(YXC_UDN, YXC_URL), 5000);
        device.calls.clear();

        monitor.refreshGroupInfoNow(java.util.Collections.singletonList(YXC_UDN), 0);

        assertTrue("no budget, no read", device.calls.isEmpty());
        monitor.prepareNow(watch(OTHER_UDN, OTHER_URL), 0);
        assertEquals("no budget, no fetch of the other description", 1, fetches.size());
    }

    @Test
    public void aTransportThatCanNotBeMadeGivesNoClientAndNoCrash() {
        yxcDevice();
        monitor.subscribe(listener, watch(YXC_UDN, YXC_URL));
        transportFails = true;

        assertNull(monitor.newClient(YXC_UDN));
        monitor.refreshGroupInfoNow(java.util.Collections.singletonList(YXC_UDN)); // no exception
    }
}
