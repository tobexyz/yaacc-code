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
package de.yaacc.multiroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.multiroom.control.SnapcastTransport;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastResolver;

/** The session's transport connects to the address the resolver gave, and looks nothing up for a literal. */
public class ResolvingTransportTest {

    private static final InetAddress LOOPBACK;

    static {
        try {
            LOOPBACK = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    public void aLiteralEndpointConnectsWithoutAnyLookup() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        SnapcastResolver resolver = new SnapcastResolver(host -> {
            lookups.incrementAndGet();
            return LOOPBACK;
        }, 1_000);
        try (ServerSocket server = new ServerSocket(0, 5, LOOPBACK)) {
            SnapcastEndpoint endpoint = SnapcastEndpoint.parse("127.0.0.1:" + server.getLocalPort());

            SnapcastTransport.Connection connection = new ResolvingTransport(endpoint, resolver).open(2_000);

            assertNotNull(server.accept());
            assertEquals(LOOPBACK, connection.getPeerAddress());
            connection.close();
        }
        assertEquals(0, lookups.get());
    }

    @Test
    public void aHostNameConnectsToTheResolvedAddress() throws Exception {
        SnapcastResolver resolver = new SnapcastResolver(host -> LOOPBACK, 1_000);
        try (ServerSocket server = new ServerSocket(0, 5, LOOPBACK)) {
            SnapcastEndpoint endpoint = SnapcastEndpoint.parse("snap.local:" + server.getLocalPort());

            SnapcastTransport.Connection connection = new ResolvingTransport(endpoint, resolver).open(2_000);

            assertNotNull(server.accept());
            connection.close();
        }
    }

    @Test
    public void aNameThatDoesNotResolveIsAConnectionFailure() {
        SnapcastResolver resolver = new SnapcastResolver(host -> {
            throw new java.net.UnknownHostException();
        }, 1_000);
        try {
            new ResolvingTransport(SnapcastEndpoint.parse("nope.local"), resolver).open(500);
            fail("should fail");
        } catch (IOException expected) {
            // fine: the session reports it and tries again later
        }
    }
}
