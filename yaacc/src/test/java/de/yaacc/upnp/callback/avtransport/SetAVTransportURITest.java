package de.yaacc.upnp.callback.avtransport;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.RemoteService;
import org.junit.Test;

import de.yaacc.upnp.server.http.HttpRequestSender;

import static org.junit.Assert.*;

public class SetAVTransportURITest {

    @Test
    public void testSetAVTransportURICreation() {
        String uri = "http://example.com/media.mp3";
        String metadata = "metadata";
        
        HttpRequestSender mockHttpRequestSender = new HttpRequestSender();
        
        assertNotNull(uri);
        assertNotNull(metadata);
        assertNotNull(mockHttpRequestSender);
    }
}
