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

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;

/** The transport against a real server on the loopback address. */
public class YxcHttpTransportTest {
    private static final String HOST = "localhost";

    private static YxcHttpTransport transportTo(LoopbackYxcServer server, int eventPort) {
        return new YxcHttpTransport(HOST, server.port(), eventPort, 2000, 2000);
    }

    @Test
    public void getReturnsTheBodyAndAsksForTheControlUrl() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json("{\"response_code\":0,\"x\":\"ä€\"}"))) {
            String body = transportTo(server, 0).get("system/getDeviceInfo");

            assertEquals("{\"response_code\":0,\"x\":\"ä€\"}", body);
            LoopbackYxcServer.Received request = server.received().get(0);
            assertEquals("GET", request.method);
            assertEquals("/YamahaExtendedControl/v1/system/getDeviceInfo", request.path);
            assertNull("a GET has no body", request.body);
        }
    }

    @Test
    public void theQueryIsSentAsGiven() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json("{}"))) {
            transportTo(server, 0).get("main/setInput?input=mc_link&mode=");

            assertEquals("/YamahaExtendedControl/v1/main/setInput?input=mc_link&mode=", server.received().get(0).path);
        }
    }

    @Test
    public void noRegistrationHeadersWithoutAnEventPort() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json("{}"))) {
            transportTo(server, 0).get("system/getDeviceInfo");
            transportTo(server, 0).post("dist/setGroupName", "{}");

            for (LoopbackYxcServer.Received request : server.received()) {
                assertNull(request.header("X-AppName"));
                assertNull(request.header("X-AppPort"));
            }
        }
    }

    @Test
    public void registrationHeadersOnGetAndPostWhenAnEventPortIsConfigured() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json("{}"))) {
            YxcHttpTransport transport = transportTo(server, 40123);
            transport.get("system/getDeviceInfo");
            transport.post("dist/setGroupName", "{\"name\":\"a\"}");

            List<LoopbackYxcServer.Received> received = server.received();
            assertEquals(2, received.size());
            for (LoopbackYxcServer.Received request : received) {
                assertEquals("MusicCast/1.0", request.header("X-AppName"));
                assertEquals("40123", request.header("X-AppPort"));
            }
        }
    }

    @Test
    public void postSendsTheBodyAsUtf8Text() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json("{\"response_code\":0}"))) {
            String answer = transportTo(server, 0).post("dist/setGroupName", "{\"name\":\"Küche €\"}");

            assertEquals("{\"response_code\":0}", answer);
            LoopbackYxcServer.Received request = server.received().get(0);
            assertEquals("POST", request.method);
            assertEquals("/YamahaExtendedControl/v1/dist/setGroupName", request.path);
            assertEquals("{\"name\":\"Küche €\"}", request.body);
            assertEquals("text/plain; charset=utf-8", request.header("Content-Type"));
        }
    }

    @Test
    public void errorStatusesAreReportedWithTheirCode() throws Exception {
        for (final int status : new int[]{400, 404, 500, 503}) {
            try (LoopbackYxcServer server = new LoopbackYxcServer((request, response) -> LoopbackYxcServer.respondJson(response, status, "{\"response_code\":0}"))) {
                try {
                    transportTo(server, 0).get("system/getDeviceInfo");
                    fail("status " + status + " must not be an answer");
                } catch (YxcHttpStatusException e) {
                    assertEquals(status, e.getStatus());
                }
            }
        }
    }

    @Test
    public void errorStatusOfAPostIsReportedToo() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer((request, response) -> LoopbackYxcServer.respondJson(response, 500, "{}"))) {
            try {
                transportTo(server, 0).post("dist/setServerInfo", "{}");
                fail();
            } catch (YxcHttpStatusException e) {
                assertEquals(500, e.getStatus());
            }
        }
    }

    @Test
    public void redirectsAreNotFollowed() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer((request, response) -> {
            response.setCode(302);
            response.setHeader("Location", "http://localhost:" + 9 + "/elsewhere");
        })) {
            try {
                transportTo(server, 0).get("system/getDeviceInfo");
                fail("a redirect is not an answer");
            } catch (YxcHttpStatusException e) {
                assertEquals(302, e.getStatus());
            }
            assertEquals(1, server.received().size());
        }
    }

    @Test
    public void aSlowDeviceEndsInAReadTimeout() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer((request, response) -> {
            Thread.sleep(1500);
            LoopbackYxcServer.respondJson(response, 200, "{}");
        })) {
            YxcHttpTransport transport = new YxcHttpTransport(HOST, server.port(), 0, 2000, 300);
            long start = System.nanoTime();
            try {
                transport.get("system/getDeviceInfo");
                fail("expected a timeout");
            } catch (SocketTimeoutException expected) {
                assertTrue("gave up after the read timeout, not after the answer",
                        (System.nanoTime() - start) / 1_000_000 < 1300);
            }
        }
    }

    @Test
    public void aClosedPortIsAnIoException() throws Exception {
        int port;
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json("{}"))) {
            port = server.port();
        }
        try {
            new YxcHttpTransport(HOST, port, 0, 1000, 1000).get("system/getDeviceInfo");
            fail("nobody listens");
        } catch (ConnectException expected) {
            // the usual outcome
        } catch (IOException alsoFine) {
            // another way of failing to connect is just as good
        }
    }

    @Test
    public void anAnswerOverTheLimitIsRefused() throws Exception {
        StringBuilder big = new StringBuilder("{\"x\":\"");
        for (int i = 0; i < 1024 * 1024; i++) {
            big.append('a');
        }
        big.append("\"}");
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json(big.toString()))) {
            try {
                transportTo(server, 0).get("system/getFeatures");
                fail("a device does not send a megabyte");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("larger"));
            }
        }
    }

    @Test
    public void onlyTheConfiguredAddressIsUsed() throws Exception {
        try (LoopbackYxcServer server = new LoopbackYxcServer(LoopbackYxcServer.json("{}"))) {
            YxcHttpTransport transport = transportTo(server, 0);
            String[] notRelative = {null, "", "/system/getDeviceInfo", "http://192.0.2.1/x", "//192.0.2.1/x", "../x", "a/../../x",
                    "a b", "a\r\nX-Evil: 1", "a#b", "a@b", "a\\b"};
            for (String path : notRelative) {
                try {
                    transport.get(path);
                    fail("not a control path: " + path);
                } catch (IllegalArgumentException expected) {
                    // refused before anything was sent
                }
            }
            assertTrue(server.received().isEmpty());
        }
    }

    @Test
    public void hostsThatAreNotAddressesAreRefused() {
        String[] bad = {null, "", "evil.example/x", "user@192.0.2.1", "192.0.2.1:80", "a b", "192.0.2.1\r\n", "fe80::1%eth0", "[::1]", "::1/x"};
        for (String host : bad) {
            try {
                new YxcHttpTransport(host, 0);
                fail("not a host: " + host);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
        new YxcHttpTransport("192.0.2.10", 0);
        new YxcHttpTransport("device-1.local", 0);
        new YxcHttpTransport("fe80::1", 0);
    }

    @Test
    public void portsAndTimeoutsAreChecked() {
        for (int[] bad : new int[][]{{0, 0, 1000, 1000}, {70000, 0, 1000, 1000}, {80, -1, 1000, 1000}, {80, 70000, 1000, 1000},
                {80, 0, 0, 1000}, {80, 0, 1000, 0}}) {
            try {
                new YxcHttpTransport("192.0.2.10", bad[0], bad[1], bad[2], bad[3]);
                fail("accepted " + java.util.Arrays.toString(bad));
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }

    @Test
    public void defaultsAreThreeAndFiveSeconds() {
        assertEquals(3000, YxcHttpTransport.DEFAULT_CONNECT_TIMEOUT_MILLIS);
        assertEquals(5000, YxcHttpTransport.DEFAULT_READ_TIMEOUT_MILLIS);
        assertEquals(80, YxcHttpTransport.DEFAULT_PORT);
    }

}
