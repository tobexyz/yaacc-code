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
package de.yaacc.upnp.server.http;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

public class ClientDisconnectsTest {

    @Test
    public void aBrokenPipeIsAClientDisconnect() {
        assertTrue(ClientDisconnects.isClientDisconnect(new IOException("Broken pipe")));
    }

    @Test
    public void aResetByPeerIsAClientDisconnect() {
        assertTrue(ClientDisconnects.isClientDisconnect(new IOException("Connection reset by peer")));
        assertTrue(ClientDisconnects.isClientDisconnect(new IOException("Software caused connection abort")));
    }

    @Test
    public void theMessageIsMatchedIgnoringCase() {
        assertTrue(ClientDisconnects.isClientDisconnect(new IOException("BROKEN PIPE")));
    }

    @Test
    public void aDisconnectBehindAWrapperIsFound() {
        assertTrue(ClientDisconnects.isClientDisconnect(new RuntimeException("wrapped", new IOException("Broken pipe"))));
    }

    @Test
    public void otherIoErrorsAreNot() {
        assertFalse(ClientDisconnects.isClientDisconnect(new IOException("No space left on device")));
        assertFalse(ClientDisconnects.isClientDisconnect(new SocketTimeoutException("timed out")));
    }

    @Test
    public void exceptionsWithoutAMessageAreNot() {
        assertFalse(ClientDisconnects.isClientDisconnect(new IOException()));
        assertFalse(ClientDisconnects.isClientDisconnect(null));
    }

    @Test
    public void aNonIoExceptionWithTheTextIsNot() {
        assertFalse(ClientDisconnects.isClientDisconnect(new IllegalStateException("Broken pipe")));
    }

    @Test
    public void aCyclicCauseChainEnds() {
        RuntimeException e = new RuntimeException("loop");
        e.initCause(new RuntimeException("inner", e));
        assertFalse(ClientDisconnects.isClientDisconnect(e));
    }
}
