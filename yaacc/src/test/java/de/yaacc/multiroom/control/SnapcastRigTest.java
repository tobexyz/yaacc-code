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
package de.yaacc.multiroom.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in integration test against a real snapserver (the Docker rig in {@code tools/snapcast-rig/}). It is skipped
 * unless the system property {@code snapcast.rig} (or the environment variable {@code SNAPCAST_RIG}) names the host,
 * so it never runs in the normal gate:
 * <pre>
 *   SNAPCAST_RIG=localhost ./gradlew :yaacc:testDebugUnitTest --offline --tests 'de.yaacc.multiroom.control.SnapcastRigTest'
 * </pre>
 * (A {@code -D} option on the Gradle command line reaches the test JVM only if the build forwards it, so the
 * environment variable is the reliable switch.) It talks to the control port 1705 only and changes nothing that it
 * does not restore: the volume of one client is set and put back, and the stream named {@code YAACC rig test} is
 * added and removed again.
 */
public class SnapcastRigTest {

    private static final int CONTROL_PORT = 1705;
    private static final String STREAM_NAME = "YAACC rig test";

    private final List<SnapcastRpc> opened = new ArrayList<>();
    private String host;

    @Before
    public void requireTheRig() {
        host = System.getProperty("snapcast.rig");
        if (host == null || host.trim().isEmpty()) {
            host = System.getenv("SNAPCAST_RIG");
        }
        Assume.assumeTrue("no rig: set -Dsnapcast.rig=<host> or SNAPCAST_RIG=<host> to run this test",
                host != null && !host.trim().isEmpty());
        host = host.trim();
    }

    @After
    public void closeConnections() {
        for (SnapcastRpc rpc : opened) {
            rpc.close();
        }
    }

    private SnapcastRpc connect(SnapcastRpc.Listener listener) throws Exception {
        SnapcastRpc rpc = SnapcastRpc.forAddress(host, CONTROL_PORT);
        opened.add(rpc);
        if (listener != null) {
            rpc.addListener(listener);
        }
        rpc.start();
        return rpc;
    }

    @Test
    public void getStatusListsTheServerAndItsClients() throws Exception {
        SnapcastServerStatus status = new SnapcastOperations(connect(null)).getStatus();

        assertNotNull(status);
        assertTrue("a version string", !status.getInfo().getVersion().isEmpty());
        assertTrue("the rig has at least the default stream", !status.getStreams().isEmpty());
        int clients = 0;
        for (SnapcastGroup group : status.getGroups()) {
            clients += group.getClients().size();
        }
        assertTrue("the rig starts two snapclients, found " + clients, clients >= 1);
    }

    @Test
    public void aVolumeChangeIsSeenByASecondConnection() throws Exception {
        final List<JSONObject> seen = java.util.Collections.synchronizedList(new ArrayList<JSONObject>());
        SnapcastRpc watcher = connect(new SnapcastRpc.Listener() {
            @Override
            public void onNotification(String method, JSONObject params) {
                if ("Client.OnVolumeChanged".equals(method)) {
                    seen.add(params);
                }
            }
        });
        SnapcastRpc changer = connect(null);
        SnapcastOperations operations = new SnapcastOperations(changer);
        SnapcastServerStatus status = operations.getStatus();
        SnapcastClient client = firstClient(status);
        Assume.assumeNotNull(client);
        SnapcastVolume original = client.getVolume();
        int changed = original.getPercent() >= 50 ? original.getPercent() - 7 : original.getPercent() + 7;
        SnapcastModelUpdater updater = new SnapcastModelUpdater(MonotonicClock.SYSTEM, () -> { });
        updater.setStatus(new SnapcastOperations(watcher).getStatus());

        try {
            operations.setClientVolume(client.getId(), original.withPercent(changed));

            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (seen.isEmpty() && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            assertTrue("the second connection saw the change within 2 s", !seen.isEmpty());
            JSONObject notification = seen.get(0);
            assertEquals(client.getId(), notification.getString("id"));
            assertEquals(changed, notification.getJSONObject("volume").getInt("percent"));
            // and the patched model equals a fresh read
            SnapcastServerStatus patched = updater.apply("Client.OnVolumeChanged", notification);
            assertEquals(new SnapcastOperations(watcher).getStatus(), patched);
        } finally {
            operations.setClientVolume(client.getId(), original);
        }
    }

    private static SnapcastClient firstClient(SnapcastServerStatus status) {
        for (SnapcastGroup group : status.getGroups()) {
            for (SnapcastClient client : group.getClients()) {
                return client;
            }
        }
        return null;
    }

    @Test
    public void aTcpStreamCanBeAddedAndRemoved() throws Exception {
        SnapcastOperations operations = new SnapcastOperations(connect(null));
        SnapcastServerStatus before = operations.getStatus();
        if (before.findStream(STREAM_NAME) != null) {
            operations.removeStream(STREAM_NAME); // a leftover of an earlier run, with our exact name only
        }
        int port = 4953;
        while (port < 5100 && portInUse(operations.getStatus(), port)) {
            port++;
        }
        String uri = "tcp://0.0.0.0:" + port + "?name=YAACC%20rig%20test&mode=server&sampleformat=48000:16:2&codec=flac";
        String id = null;
        try {
            id = operations.addStream(uri);

            assertEquals(STREAM_NAME, id);
            SnapcastStream stream = operations.getStatus().findStream(id);
            assertNotNull(stream);
            assertEquals("tcp", stream.getScheme());
            assertEquals(port, stream.getPort());
            assertEquals("server", stream.getQueryParameter("mode"));
        } finally {
            operations.removeStream(id == null ? STREAM_NAME : id);
        }
        assertNull(operations.getStatus().findStream(STREAM_NAME));
    }

    private static boolean portInUse(SnapcastServerStatus status, int port) {
        for (SnapcastStream stream : status.getStreams()) {
            if (stream.getPort() == port) {
                return true;
            }
        }
        return false;
    }
}
