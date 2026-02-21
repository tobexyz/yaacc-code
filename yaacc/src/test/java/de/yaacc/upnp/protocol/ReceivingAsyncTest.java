package de.yaacc.upnp.protocol;

import org.fourthline.cling.model.message.IncomingDatagramMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.junit.Test;

import static org.junit.Assert.*;

public class ReceivingAsyncTest {

    @Test
    public void testReceivingAsyncToString() {
        ReceivingAsync<?> receivingAsync = new ReceivingAsync<IncomingDatagramMessage<UpnpRequest>>(null) {
            @Override
            protected void execute() {
            }
        };
        
        String result = receivingAsync.toString();
        assertNotNull(result);
        // Just verify toString returns something
        assertFalse(result.isEmpty());
    }
}
