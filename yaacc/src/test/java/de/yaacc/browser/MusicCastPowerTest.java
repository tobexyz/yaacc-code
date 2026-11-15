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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.yaacc.yxc.YxcClient;
import de.yaacc.yxc.YxcDeviceState;
import de.yaacc.yxc.YxcTransport;

/**
 * The power button of a MusicCast card (task 12.4): the capability comes from the features of the device ({@code power} in the main
 * zone's function list), the state from the zone status and its events, and the change is the documented {@code setPower} of the
 * main zone ({@code power=on} or {@code power=standby}), checked here against the client the app already has. Plain JVM, fixtures
 * of captured devices.
 */
public class MusicCastPowerTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = MusicCastPowerTest.class.getResourceAsStream("/yxc/captured/" + name + ".json")) {
            if (in == null) {
                throw new AssertionError("fixture not found: " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Answers by path and records what was asked. */
    private static class Device implements YxcTransport {
        final List<String> paths = new ArrayList<>();
        String status = "{\"response_code\":0,\"power\":\"on\"}";
        String features;
        IOException failure;

        @Override
        public String get(String path) throws IOException {
            paths.add(path);
            if (failure != null) {
                throw failure;
            }
            if (path.equals("main/getStatus")) {
                return status;
            }
            if (path.equals("system/getFeatures")) {
                return features;
            }
            return "{\"response_code\":0}";
        }

        @Override
        public String post(String path, String jsonBody) {
            throw new AssertionError("no post expected");
        }
    }

    private static YxcDeviceState state(String statusJson, boolean withPowerCapability, boolean reachable) throws Exception {
        Device device = new Device();
        device.status = statusJson;
        device.features = withPowerCapability ? fixture("a_features")
                : "{\"response_code\":0,\"zone\":[{\"id\":\"main\",\"func_list\":[\"volume\"]}]}";
        YxcClient client = new YxcClient(device);
        YxcDeviceState known = YxcDeviceState.unknown().withZone(client.getZoneStatus("main"))
                .withCapabilities(client.getFeatures().getSourceCapabilities());
        return reachable ? known : known.unreachable();
    }

    // ---- capability and state

    @Test
    public void aDeviceThatListsPowerShowsItsState() throws Exception {
        assertEquals(RendererPower.of(RendererPower.State.ON),
                MusicCastPower.of(state("{\"response_code\":0,\"power\":\"on\"}", true, true)));
        assertEquals(RendererPower.of(RendererPower.State.STANDBY),
                MusicCastPower.of(state("{\"response_code\":0,\"power\":\"standby\"}", true, true)));
    }

    @Test
    public void aDeviceThatDoesNotListPowerHasNoButton() throws Exception {
        assertEquals(RendererPower.unsupported(), MusicCastPower.of(state("{\"response_code\":0,\"power\":\"on\"}", false, true)));
    }

    @Test
    public void aDeviceThatDoesNotAnswerOrIsNotKnownYetHasNoButton() throws Exception {
        assertEquals(RendererPower.unsupported(), MusicCastPower.of(state("{\"response_code\":0,\"power\":\"on\"}", true, false)));
        assertEquals(RendererPower.unsupported(), MusicCastPower.of(null));
        assertEquals("the capabilities are not read yet", RendererPower.unsupported(), MusicCastPower.of(YxcDeviceState.unknown()));
    }

    @Test
    public void aStatusWithoutAPowerWordIsAnUnknownStateOfASupportingDevice() throws Exception {
        assertEquals(RendererPower.of(RendererPower.State.UNKNOWN), MusicCastPower.of(state("{\"response_code\":0}", true, true)));
    }

    @Test
    public void everyWordButOnIsStandbyAsTheRowTextReadsIt() {
        assertEquals(RendererPower.State.ON, MusicCastPower.stateOf("on"));
        assertEquals(RendererPower.State.ON, MusicCastPower.stateOf("ON"));
        assertEquals(RendererPower.State.STANDBY, MusicCastPower.stateOf("standby"));
        assertEquals(RendererPower.State.STANDBY, MusicCastPower.stateOf("Standby"));
        assertEquals(RendererPower.State.UNKNOWN, MusicCastPower.stateOf(null));
        assertEquals(RendererPower.State.UNKNOWN, MusicCastPower.stateOf(""));
        assertEquals("a word the app does not know is not guessed", RendererPower.State.UNKNOWN, MusicCastPower.stateOf("toggle"));
    }

    @Test
    public void aPowerEventOfTheDeviceChangesWhatTheCardShows() throws Exception {
        YxcDeviceState on = state("{\"response_code\":0,\"power\":\"on\"}", true, true);
        YxcDeviceState afterEvent = on.withEvent(new de.yaacc.yxc.YxcEventParser().parse(fixture("a_event_power_standby")));

        assertEquals(RendererPower.of(RendererPower.State.STANDBY), MusicCastPower.of(afterEvent));
    }

    // ---- the call

    @Test
    public void theDeviceIsReadAndSwitchedWithTheDocumentedCallsOfTheMainZone() throws Exception {
        Device device = new Device();
        device.status = "{\"response_code\":0,\"power\":\"standby\"}";
        RendererPowerToggle.Source source = MusicCastPower.source(new YxcClient(device));

        assertEquals(RendererPower.State.STANDBY, source.read());
        source.set(true);
        source.set(false);

        assertEquals("main/getStatus", device.paths.get(0));
        assertEquals("main/setPower?power=on", device.paths.get(1));
        assertEquals("main/setPower?power=standby", device.paths.get(2));
        assertEquals(3, device.paths.size());
    }

    @Test
    public void aDeviceThatFailsIsAPowerExceptionWithoutItsText() throws Exception {
        Device device = new Device();
        device.failure = new IOException("connect to 192.168.1.9 failed");
        RendererPowerToggle.Source source = MusicCastPower.source(new YxcClient(device));

        try {
            source.read();
            fail();
        } catch (RendererPowerToggle.PowerException e) {
            assertEquals(null, e.getMessage());
        }
        try {
            source.set(true);
            fail();
        } catch (RendererPowerToggle.PowerException e) {
            assertTrue(!String.valueOf(e).contains("192.168"));
        }
    }

    @Test
    public void aRefusalOfTheDeviceIsAPowerException() throws Exception {
        Device device = new Device() {
            @Override
            public String get(String path) {
                return "{\"response_code\":3}";
            }
        };
        try {
            MusicCastPower.source(new YxcClient(device)).set(true);
            fail();
        } catch (RendererPowerToggle.PowerException expected) {
            // refused by response_code
        }
    }
}
