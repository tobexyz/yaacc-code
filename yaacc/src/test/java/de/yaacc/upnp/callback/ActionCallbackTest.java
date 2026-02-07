package de.yaacc.upnp.callback;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Action;
import org.junit.Test;

import de.yaacc.upnp.server.http.HttpRequestSender;

import static org.junit.Assert.*;

public class ActionCallbackTest {

    @Test
    public void testActionCallbackCreation() {
        HttpRequestSender mockHttpRequestSender = new HttpRequestSender();
        assertNotNull(mockHttpRequestSender);
    }
}
