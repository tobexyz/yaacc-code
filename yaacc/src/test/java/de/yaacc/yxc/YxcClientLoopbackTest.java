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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/** {@link YxcClient} over the real transport against a server on the loopback address. */
public class YxcClientLoopbackTest {

    private static YxcClient clientFor(LoopbackYxcServer server, int eventPort) {
        return new YxcClient(new YxcHttpTransport("localhost", server.port(), eventPort, 2000, 2000));
    }

    @Test
    public void readsTheDeviceInfoFromTheFixture() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json(YxcFixtures.read("device_info.json")))) {
            YxcDeviceInfo info = clientFor(server, 0).getDeviceInfo();

            assertEquals("TestModel-A", info.getModelName());
            assertEquals("/YamahaExtendedControl/v1/system/getDeviceInfo", server.received().get(0).path);
        }
    }

    @Test
    public void readsTheDistributionInfoOfAServer() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json(YxcFixtures.read("dist_info_server.json")))) {
            YxcDistributionInfo info = clientFor(server, 0).getDistributionInfo();

            assertEquals(YxcDistributionInfo.Role.SERVER, info.getRole());
            assertEquals(Arrays.asList("192.0.2.12", "192.0.2.13"), info.getClients());
        }
    }

    @Test
    public void everyCallCarriesTheRegistrationHeadersWhenAnEventPortIsSet() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json("{\"response_code\":0}"))) {
            YxcClient client = clientFor(server, 41234);
            client.getDistributionInfo();
            client.clearClientInfo();

            for (LoopbackYxcServer.Received request : server.received()) {
                assertEquals("MusicCast/1.0", request.header("X-AppName"));
                assertEquals("41234", request.header("X-AppPort"));
            }
        }
    }

    @Test
    public void aGroupCallPostsTheBodyToTheRightPath() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json(YxcFixtures.read("ok.json")))) {
            clientFor(server, 0).setClientInfo("0123456789ABCDEF0123456789ABCDEF", "main", "192.0.2.10");

            LoopbackYxcServer.Received request = server.received().get(0);
            assertEquals("POST", request.method);
            assertEquals("/YamahaExtendedControl/v1/dist/setClientInfo", request.path);
            JSONObject body = new JSONObject(request.body);
            assertEquals("main", body.getJSONArray("zone").getString(0));
            assertEquals("192.0.2.10", body.getString("server_ip_address"));
        }
    }

    @Test
    public void aGuardedAnswerIsAnException() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json(YxcFixtures.read("error_guarded.json")))) {
            try {
                clientFor(server, 0).setInput("main", "mc_link", "");
                fail("the device said no");
            } catch (YxcException e) {
                assertEquals(5, e.getResponseCode());
                assertEquals("main/setInput", e.getCall());
            }
        }
    }

    @Test
    public void anHttpErrorIsAnExceptionWithTheStatus() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer((request, response) -> LoopbackYxcServer.respondJson(response, 500, YxcFixtures.read("ok.json")))) {
            try {
                clientFor(server, 0).getFeatures();
                fail("an error status is no answer even with a good body");
            } catch (YxcException e) {
                assertEquals(500, e.getHttpStatus());
                assertEquals(YxcException.NONE, e.getResponseCode());
            }
        }
    }

    @Test
    public void aSlowDeviceIsAnExceptionWithTheTimeoutAsCause() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer((request, response) -> {
            Thread.sleep(1200);
            LoopbackYxcServer.respondJson(response, 200, YxcFixtures.read("ok.json"));
        })) {
            YxcClient client = new YxcClient(new YxcHttpTransport("localhost", server.port(), 0, 2000, 250));
            try {
                client.stopDistribution();
                fail();
            } catch (YxcException e) {
                assertTrue(e.getCause() instanceof java.net.SocketTimeoutException);
            }
        }
    }

    @Test
    public void featuresFromTheFixtureThroughTheWire() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json(YxcFixtures.read("features_two_zones.json")))) {
            YxcFeatures features = clientFor(server, 0).getFeatures();

            assertEquals(2, features.getZones().size());
            assertNull(features.getZone("zone4"));
        }
    }
}
