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
package de.yaacc.upnp.server.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class HttpRequestSenderTest {

    @Test
    public void testHttpRequestSenderCreation() {
        HttpRequestSender httpRequestSender = new HttpRequestSender();
        assertNotNull(httpRequestSender);
    }

    @Test
    public void testCreateRequestMessage() {
        // Test that HTTP request sender can be instantiated
        HttpRequestSender httpRequestSender = new HttpRequestSender();
        assertNotNull(httpRequestSender);
    }

    /**
     * Happy path: an HTTP 200 response is mapped to a successful UpnpResponse
     * with the canonical "OK" status message.
     */
    @Test
    public void testCreateResponse_200_mapsToOk() throws Exception {
        StreamResponseMessage msg = new HttpRequestSender().createResponse(buildResponse(200, "<root/>"));
        assertNotNull(msg);
        assertEquals(200, msg.getOperation().getStatusCode());
        assertEquals("OK", msg.getOperation().getStatusMessage());
        assertFalse(msg.getOperation().isFailed());
    }

    /**
     * Regression for issue #232. A 401 (e.g. Sky Q DVR's authenticated UPnP
     * service) used to throw IllegalStateException out of createResponse and
     * crash the discovery worker. It must now produce a synthetic failed
     * UpnpResponse so the caller can skip the device.
     */
    @Test
    public void testCreateResponse_401_doesNotThrow() throws Exception {
        StreamResponseMessage msg = new HttpRequestSender().createResponse(buildResponse(401, "Unauthorized"));
        assertNotNull(msg);
        assertEquals(401, msg.getOperation().getStatusCode());
        assertEquals("Unauthorized", msg.getOperation().getStatusMessage());
        assertTrue("401 must report isFailed() so the discovery loop skips the device",
                msg.getOperation().isFailed());
    }

    /**
     * Regression for issue #232. A 403 produce a synthetic failed
     * UpnpResponse so the caller can skip the device.
     */
    @Test
    public void testCreateResponse_403_doesNotThrow() throws Exception {
        StreamResponseMessage msg = new HttpRequestSender().createResponse(buildResponse(403, "Forbidden"));
        assertNotNull(msg);
        assertEquals(403, msg.getOperation().getStatusCode());
        assertEquals("Forbidden", msg.getOperation().getStatusMessage());
        assertTrue("403 must report isFailed() so the discovery loop skips the device",
                msg.getOperation().isFailed());
    }

    /**
     * Regression for issue #219 / PR #221. 503 was added to the canonical
     * Status enum in that PR; verify it still maps to the canonical
     * "Service Unavailable" message rather than the synthetic fallback.
     */
    @Test
    public void testCreateResponse_503_mapsToServiceUnavailable() throws Exception {
        StreamResponseMessage msg = new HttpRequestSender().createResponse(buildResponse(503, ""));
        assertNotNull(msg);
        assertEquals(503, msg.getOperation().getStatusCode());
        assertEquals("Service Unavailable", msg.getOperation().getStatusMessage());
        assertTrue(msg.getOperation().isFailed());
    }

    /**
     * Any HTTP status not in UpnpResponse.Status (here: a deliberately
     * fictitious 418) must not throw and must produce a synthetic failed
     * response. This guards against future status codes the canonical enum
     * does not yet know about (issue #232 explicitly argues against the
     * whack-a-mole alternative of expanding the enum one code at a time).
     */
    @Test
    public void testCreateResponse_418_unmappedDoesNotThrow() throws Exception {
        StreamResponseMessage msg = new HttpRequestSender().createResponse(buildResponse(418, "I'm a teapot"));
        assertNotNull(msg);
        assertEquals(418, msg.getOperation().getStatusCode());
        assertEquals("HTTP 418", msg.getOperation().getStatusMessage());
        assertTrue(msg.getOperation().isFailed());
    }

    private static BasicClassicHttpResponse buildResponse(int statusCode, String body) {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(statusCode);
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        response.setEntity(new ByteArrayEntity(bytes, ContentType.TEXT_PLAIN));
        return response;
    }
}
