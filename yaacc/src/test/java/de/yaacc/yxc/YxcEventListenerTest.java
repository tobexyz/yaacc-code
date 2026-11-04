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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The real UDP listener on loopback. */
public class YxcEventListenerTest {

    private static void send(int port, byte[] data) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), port));
        }
    }

    @Test
    public void aDatagramArrivesParsedWithItsSender() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        List<YxcEvent> events = new CopyOnWriteArrayList<>();
        List<InetAddress> senders = new CopyOnWriteArrayList<>();
        YxcEventListener listener = YxcEventListener.start((from, event) -> {
            senders.add(from);
            events.add(event);
            received.countDown();
        });
        try {
            send(listener.getPort(), YxcFixtures.read("captured/a_event_volume.json").getBytes(StandardCharsets.UTF_8));

            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertTrue(events.get(0).isParsable());
            assertEquals("00000000000A", events.get(0).getDeviceId());
            assertTrue(senders.get(0).isLoopbackAddress());
        } finally {
            listener.close();
        }
    }

    @Test
    public void garbageBecomesAnUnparsableEventAndTheListenerGoesOn() throws Exception {
        CountDownLatch received = new CountDownLatch(2);
        List<YxcEvent> events = Collections.synchronizedList(new ArrayList<>());
        YxcEventListener listener = YxcEventListener.start((from, event) -> {
            events.add(event);
            received.countDown();
        });
        try {
            send(listener.getPort(), new byte[]{(byte) 0xff, 0x00, 0x7b});
            send(listener.getPort(), new byte[YxcEventListener.MAX_DATAGRAM_BYTES]);
            assertTrue(received.await(5, TimeUnit.SECONDS));
            for (YxcEvent event : events) {
                assertFalse(event.isParsable());
            }
        } finally {
            listener.close();
        }
    }

    @Test
    public void aThrowingReceiverDoesNotEndTheListener() throws Exception {
        CountDownLatch second = new CountDownLatch(1);
        int[] calls = {0};
        YxcEventListener listener = YxcEventListener.start((from, event) -> {
            calls[0]++;
            if (calls[0] == 1) {
                throw new IllegalStateException("simulated");
            }
            second.countDown();
        });
        try {
            send(listener.getPort(), "{}".getBytes(StandardCharsets.UTF_8));
            send(listener.getPort(), "{}".getBytes(StandardCharsets.UTF_8));

            assertTrue(second.await(5, TimeUnit.SECONDS));
        } finally {
            listener.close();
        }
    }

    @Test
    public void closeFreesThePort() throws Exception {
        YxcEventListener listener = YxcEventListener.start((from, event) -> {
        });
        int port = listener.getPort();

        listener.close();
        listener.close(); // idempotent

        try (DatagramSocket again = new DatagramSocket(port)) {
            assertEquals(port, again.getLocalPort());
        }
    }

    @Test
    public void datagramsFromAddressesThatDoNotMatterAreNotParsed() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);
        int[] parsed = {0};
        YxcEventListener listener = YxcEventListener.start(new YxcEventSource.Receiver() {
            @Override
            public boolean accepts(InetAddress from) {
                asked.countDown();
                return false;
            }

            @Override
            public void onEvent(InetAddress from, YxcEvent event) {
                parsed[0]++;
            }
        });
        try {
            send(listener.getPort(), "{}".getBytes(StandardCharsets.UTF_8));

            assertTrue(asked.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertEquals(0, parsed[0]);
        } finally {
            listener.close();
        }
    }
}
