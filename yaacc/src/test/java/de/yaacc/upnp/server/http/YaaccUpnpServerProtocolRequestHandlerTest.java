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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.fourthline.cling.model.message.UpnpResponse;
import org.junit.Test;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import de.yaacc.upnp.protocol.ProtocolCreationException;
import de.yaacc.upnp.protocol.ReceivingSync;
import de.yaacc.upnp.protocol.UpnpProtocolHandler;

/**
 * The handler resolves the protocol handler per request: it used to capture it once when the HTTP server was created,
 * so a service started without a network answered every sync request with a NullPointerException.
 */
public class YaaccUpnpServerProtocolRequestHandlerTest {

    private static StreamRequestMessage request() {
        return new StreamRequestMessage(UpnpRequest.Method.GET, URI.create("/upnp/test"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static UpnpProtocolHandler handlerAnswering(StreamResponseMessage answer) throws ProtocolCreationException {
        UpnpProtocolHandler handler = mock(UpnpProtocolHandler.class);
        ReceivingSync sync = mock(ReceivingSync.class);
        when(sync.getOutputMessage()).thenReturn(answer);
        when(handler.createReceivingSync(org.mockito.ArgumentMatchers.any())).thenReturn(sync);
        return handler;
    }

    @Test
    public void noHandlerAnswers503NotAnException() {
        YaaccUpnpServerProtocolRequestHandler handler = new YaaccUpnpServerProtocolRequestHandler(() -> null);

        StreamResponseMessage response = handler.process(request());

        assertEquals(503, response.getOperation().getStatusCode());
    }

    @Test
    public void responseChangesWhenTheHandlerAppearsWithoutRestart() throws Exception {
        AtomicReference<UpnpProtocolHandler> live = new AtomicReference<>();
        StreamResponseMessage ok = new StreamResponseMessage(UpnpResponse.Status.OK);
        YaaccUpnpServerProtocolRequestHandler handler = new YaaccUpnpServerProtocolRequestHandler(live::get);

        assertEquals(503, handler.process(request()).getOperation().getStatusCode());

        live.set(handlerAnswering(ok));
        assertSame(ok, handler.process(request()));

        // the network drops again: the handler is gone, the next request is answered cleanly
        live.set(null);
        assertEquals(503, handler.process(request()).getOperation().getStatusCode());
    }

    @Test
    public void aReplacedHandlerIsUsedForTheNextRequest() throws Exception {
        StreamResponseMessage first = new StreamResponseMessage(UpnpResponse.Status.OK);
        StreamResponseMessage second = new StreamResponseMessage(UpnpResponse.Status.NOT_FOUND);
        AtomicReference<UpnpProtocolHandler> live = new AtomicReference<>(handlerAnswering(first));
        YaaccUpnpServerProtocolRequestHandler handler = new YaaccUpnpServerProtocolRequestHandler(live::get);

        assertSame(first, handler.process(request()));
        live.set(handlerAnswering(second));
        assertSame(second, handler.process(request()));
    }

    @Test
    public void aSupplierThatThrowsAnswers503() {
        YaaccUpnpServerProtocolRequestHandler handler = new YaaccUpnpServerProtocolRequestHandler(() -> {
            throw new IllegalStateException("registry gone");
        });

        StreamResponseMessage response = handler.process(request());

        assertEquals(503, response.getOperation().getStatusCode());
    }

    @Test
    public void aNullSupplierAnswers503() {
        YaaccUpnpServerProtocolRequestHandler handler = new YaaccUpnpServerProtocolRequestHandler(null);

        assertEquals(503, handler.process(request()).getOperation().getStatusCode());
    }

    @Test
    public void noProtocolForTheRequestAnswers501NotAnException() throws Exception {
        UpnpProtocolHandler live = mock(UpnpProtocolHandler.class);
        when(live.createReceivingSync(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        YaaccUpnpServerProtocolRequestHandler handler = new YaaccUpnpServerProtocolRequestHandler(() -> live);

        assertEquals(501, handler.process(request()).getOperation().getStatusCode());
    }

    @Test
    public void protocolCreationFailureAnswers501() throws Exception {
        UpnpProtocolHandler live = mock(UpnpProtocolHandler.class);
        when(live.createReceivingSync(org.mockito.ArgumentMatchers.any())).thenThrow(new ProtocolCreationException("no"));
        YaaccUpnpServerProtocolRequestHandler handler = new YaaccUpnpServerProtocolRequestHandler(() -> live);

        assertEquals(501, handler.process(request()).getOperation().getStatusCode());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void protocolWithoutOutputIsNullForThe404Path() throws Exception {
        UpnpProtocolHandler live = handlerAnswering(null);
        YaaccUpnpServerProtocolRequestHandler handler = new YaaccUpnpServerProtocolRequestHandler(() -> live);

        assertNull(handler.process(request()));
    }
}
