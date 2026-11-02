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

    @Test
    public void anUnreachablePeerDoesNotEndTheThread() {
        // an event callback behind a proxy: a connect timeout must not leave the pool thread (it ends the app)
        SendingAsync sendingAsync = new SendingAsync() {
            @Override
            protected void execute() throws java.io.IOException {
                throw new java.io.IOException(new java.net.SocketTimeoutException("connect timed out"));
            }
        };

        sendingAsync.run();
    }

    @Test
    public void anUnexpectedExceptionDoesNotEndTheThreadEither() {
        SendingAsync sendingAsync = new SendingAsync() {
            @Override
            protected void execute() {
                throw new IllegalStateException("broken");
            }
        };

        sendingAsync.run();
    }

    @Test
    public void anInterruptionIsNotAFailure() {
        SendingAsync sendingAsync = new SendingAsync() {
            @Override
            protected void execute() throws java.io.IOException {
                throw new java.io.IOException(new InterruptedException());
            }
        };

        sendingAsync.run();
    }
}
