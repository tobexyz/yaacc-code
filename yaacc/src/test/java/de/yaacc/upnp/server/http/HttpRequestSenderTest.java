package de.yaacc.upnp.server.http;

import org.junit.Test;

import static org.junit.Assert.*;

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
}
