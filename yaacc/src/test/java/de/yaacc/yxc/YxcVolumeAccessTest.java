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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/** The row's volume and mute through the device's own interface (a2: device scale 0..60, captured status). */
public class YxcVolumeAccessTest {
    private static final String UDN = "uuid:device";

    private final RecordingTransport transport = new RecordingTransport()
            .answer("main/getStatus", YxcFixtures.read("captured/a_status_unlinked.json")); // volume 27 of 60, mute false
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean deferred;
    private final Executor writer = command -> {
        if (deferred) {
            queue.add(command);
        } else {
            command.run();
        }
    };
    private boolean mainThread;
    private YxcDeviceState lastState = YxcDeviceState.unknown();
    private final YxcVolumeAccess access = new YxcVolumeAccess(
            udn -> UDN.equals(udn) ? new YxcClient(transport) : null,
            udn -> UDN.equals(udn) ? lastState : null, () -> mainThread, writer);

    private int count(String prefix) {
        int count = 0;
        for (RecordingTransport.Request request : transport.requests) {
            if (request.path.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private String lastPath() {
        return transport.requests.get(transport.requests.size() - 1).path;
    }

    @Test
    public void onlyDevicesThatSpeakTheProtocolAreHandled() {
        assertTrue(access.handles(UDN));
        assertFalse(access.handles("uuid:other"));
        assertFalse(access.handles(null));
        assertEquals(-1, access.readPercent("uuid:other"));
        assertNull(access.readMute("uuid:other"));
    }

    @Test
    public void theVolumeIsReadAsAPercentageOfTheDevicesScale() {
        assertEquals(45, access.readPercent(UDN)); // 27 of 60
        assertEquals(Boolean.FALSE, access.readMute(UDN));
    }

    @Test
    public void aPercentageIsSetOnTheDevicesOwnScale() {
        access.setPercent(UDN, 50);

        assertEquals("main/setVolume?volume=30", lastPath());
        access.setPercent(UDN, 0);
        assertEquals("main/setVolume?volume=0", lastPath());
        access.setPercent(UDN, 100);
        assertEquals("main/setVolume?volume=60", lastPath());
        access.setPercent(UDN, 250);
        assertEquals("a value beyond the slider is cut", "main/setVolume?volume=60", lastPath());
        assertEquals("the scale is read once", 1, count("main/getStatus"));
    }

    @Test
    public void whileAWriteIsOnItsWayOnlyTheLatestValueIsSent() {
        deferred = true;
        access.setPercent(UDN, 10);
        access.setPercent(UDN, 20);
        access.setPercent(UDN, 90);
        assertEquals("nothing sent yet", 0, count("main/setVolume"));
        assertEquals("one write task for the device", 1, queue.size());

        queue.poll().run();

        assertEquals(1, count("main/setVolume"));
        assertEquals("main/setVolume?volume=54", lastPath()); // 90 % of 60
    }

    @Test
    public void aNewValueAfterTheWriteStartsAnotherWrite() {
        deferred = true;
        access.setPercent(UDN, 10);
        queue.poll().run();
        access.setPercent(UDN, 20);

        assertEquals(1, queue.size());
    }

    @Test
    public void muteIsSetAndReadBack() {
        access.setMute(UDN, true);

        assertEquals("main/setMute?enable=true", lastPath());
        access.setMute(UDN, false);
        assertEquals("main/setMute?enable=false", lastPath());
    }

    @Test
    public void aDeviceThatDoesNotAnswerChangesNothingAndThrowsNothing() {
        RecordingTransport broken = new RecordingTransport().failWith(new IOException("down"));
        YxcVolumeAccess offline = new YxcVolumeAccess(udn -> new YxcClient(broken), udn -> null, () -> false, Runnable::run);

        offline.setPercent(UDN, 40);
        offline.setMute(UDN, true);

        assertEquals(-1, offline.readPercent(UDN));
        assertNull(offline.readMute(UDN));
    }

    @Test
    public void theScalesMapBothWaysAtTheEnds() {
        assertEquals(0, YxcVolumeAccess.toPercent(0, 60));
        assertEquals(100, YxcVolumeAccess.toPercent(60, 60));
        assertEquals(100, YxcVolumeAccess.toPercent(70, 60));
        assertEquals(0, YxcVolumeAccess.fromPercent(0, 60));
        assertEquals(60, YxcVolumeAccess.fromPercent(100, 60));
        assertEquals(1, YxcVolumeAccess.fromPercent(2, 60));
        assertEquals(45, YxcVolumeAccess.toPercent(YxcVolumeAccess.fromPercent(45, 60), 60));
    }

    @Test
    public void theClientRejectsNegativeVolumesAndBadZones() throws Exception {
        YxcClient client = new YxcClient(new RecordingTransport());
        try {
            client.setVolume("main", -1);
            fail("accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            client.setVolume("../x", 1);
            fail("accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void onTheMainThreadNothingIsReadFromTheNetwork() throws Exception {
        lastState = YxcDeviceState.unknown().withZone(
                new YxcClient(transport).getZoneStatus("main")); // volume 27 of 60, mute false
        transport.requests.clear();
        mainThread = true;

        assertEquals(45, access.readPercent(UDN));
        assertEquals(Boolean.FALSE, access.readMute(UDN));
        assertEquals(-1, access.readPercent("uuid:other"));
        assertNull(access.readMute("uuid:other"));
        assertTrue("no request was made", transport.requests.isEmpty());
    }

    @Test
    public void onTheMainThreadAnUnknownStateGivesNoValue() {
        mainThread = true;

        assertEquals(-1, access.readPercent(UDN));
        assertNull(access.readMute(UDN));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void aHugeOrBrokenScaleDoesNotOverflowOrDivideByZero() {
        assertEquals(Integer.MAX_VALUE, YxcVolumeAccess.fromPercent(100, Integer.MAX_VALUE));
        assertEquals(50, YxcVolumeAccess.toPercent(Integer.MAX_VALUE / 2, Integer.MAX_VALUE));
        assertEquals(100, YxcVolumeAccess.toPercent(Integer.MAX_VALUE, 1));
    }

    @Test
    public void aClientThatCanNotBeMadeMeansTheDeviceIsNotHandled() {
        YxcVolumeAccess broken = new YxcVolumeAccess(udn -> {
            throw new IllegalArgumentException("not an address");
        }, udn -> null, () -> false, Runnable::run);

        assertFalse(broken.handles(UDN));
        assertEquals(-1, broken.readPercent(UDN));
        broken.setPercent(UDN, 10); // no exception
        broken.setMute(UDN, true);
    }

    @Test
    public void anUnexpectedFailureDuringAWriteDoesNotBlockTheNextWrite() {
        int[] calls = {0};
        YxcVolumeAccess flaky = new YxcVolumeAccess(udn -> {
            if (calls[0]++ == 0) {
                throw new IllegalStateException("simulated");
            }
            return new YxcClient(transport);
        }, udn -> null, () -> false, command -> {
            try {
                command.run();
            } catch (IllegalStateException expected) {
                // the executor thread would die here
            }
        });

        flaky.setPercent(UDN, 10);
        flaky.setPercent(UDN, 20);

        assertTrue("the second write was sent", count("main/setVolume") >= 1);
    }

    @Test
    public void aValueThatArrivesWhileASendIsOnItsWayIsSentAfterwards() {
        java.util.List<String> sent = new java.util.ArrayList<>();
        YxcVolumeAccess[] holder = new YxcVolumeAccess[1];
        RecordingTransport inner = new RecordingTransport()
                .answer("main/getStatus", YxcFixtures.read("captured/a_status_unlinked.json"));
        YxcTransport during = new YxcTransport() {
            @Override
            public String get(String path) throws IOException {
                if (path.startsWith("main/setVolume") && sent.isEmpty()) {
                    holder[0].setPercent(UDN, 80); // arrives while the first value is being sent
                }
                if (path.startsWith("main/setVolume")) {
                    sent.add(path);
                }
                return inner.get(path);
            }

            @Override
            public String post(String path, String body) throws IOException {
                return inner.post(path, body);
            }
        };
        holder[0] = new YxcVolumeAccess(udn -> new YxcClient(during), udn -> null, () -> false, Runnable::run);

        holder[0].setPercent(UDN, 10);

        assertEquals(java.util.Arrays.asList("main/setVolume?volume=6", "main/setVolume?volume=48"), sent);
    }

    @Test
    public void aMuteThatArrivesWhileASendIsOnItsWayIsSentAfterwards() {
        java.util.List<String> sent = new java.util.ArrayList<>();
        YxcVolumeAccess[] holder = new YxcVolumeAccess[1];
        RecordingTransport inner = new RecordingTransport();
        YxcTransport during = new YxcTransport() {
            @Override
            public String get(String path) throws IOException {
                if (path.startsWith("main/setMute") && sent.isEmpty()) {
                    holder[0].setMute(UDN, false);
                }
                if (path.startsWith("main/setMute")) {
                    sent.add(path);
                }
                return inner.get(path);
            }

            @Override
            public String post(String path, String body) throws IOException {
                return inner.post(path, body);
            }
        };
        holder[0] = new YxcVolumeAccess(udn -> new YxcClient(during), udn -> null, () -> false, Runnable::run);

        holder[0].setMute(UDN, true);

        assertEquals(java.util.Arrays.asList("main/setMute?enable=true", "main/setMute?enable=false"), sent);
    }
}
