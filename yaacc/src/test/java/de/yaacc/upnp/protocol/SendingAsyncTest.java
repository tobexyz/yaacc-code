package de.yaacc.upnp.protocol;

import org.junit.Test;

import de.yaacc.upnp.protocol.SendingAsync;

import static org.junit.Assert.*;

public class SendingAsyncTest {

    @Test
    public void testSendingAsyncToString() {
        SendingAsync sendingAsync = new SendingAsync() {
            @Override
            protected void execute() {
            }
        };
        
        String result = sendingAsync.toString();
        assertNotNull(result);
        // Just verify toString returns something
        assertFalse(result.isEmpty());
    }

    @Test
    public void testSendingAsyncRun() {
        final boolean[] executed = {false};
        
        SendingAsync sendingAsync = new SendingAsync() {
            @Override
            protected void execute() {
                executed[0] = true;
            }
        };
        
        sendingAsync.run();
        assertTrue(executed[0]);
    }
}
