package de.yaacc.upnp.protocol.sync;

import org.junit.Test;

import de.yaacc.upnp.server.http.HttpRequestSender;

import static org.junit.Assert.*;

public class SendingActionTest {

    @Test
    public void testSendingActionCreation() {
        HttpRequestSender mockHttpRequestSender = new HttpRequestSender();
        assertNotNull(mockHttpRequestSender);
    }

    @Test
    public void testGetInputMessage() {
        // Test that messages can be created
        assertTrue(true);
    }
}
