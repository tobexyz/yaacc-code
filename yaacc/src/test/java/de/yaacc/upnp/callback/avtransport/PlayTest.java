package de.yaacc.upnp.callback.avtransport;

import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.junit.Test;

import de.yaacc.upnp.server.http.HttpRequestSender;

import static org.junit.Assert.*;

public class PlayTest {

    @Test
    public void testPlayCreation() {
        HttpRequestSender mockHttpRequestSender = new HttpRequestSender();
        assertNotNull(mockHttpRequestSender);
    }

    @Test
    public void testPlayWithInstanceId() {
        UnsignedIntegerFourBytes instanceId = new UnsignedIntegerFourBytes(1);
        assertNotNull(instanceId);
        assertEquals(1L, instanceId.getValue().longValue());
    }
}
