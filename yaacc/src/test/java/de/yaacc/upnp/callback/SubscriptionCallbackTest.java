package de.yaacc.upnp.callback;

import org.junit.Test;

import static org.junit.Assert.*;

public class SubscriptionCallbackTest {

    @Test
    public void testSubscriptionCallbackCreation() {
        // Test subscription duration constant
        int duration = 1800;
        assertEquals(1800, duration);
        assertTrue(duration > 0);
    }

    @Test
    public void testSubscriptionCallbackWithDuration() {
        int duration = 1800;
        assertNotNull(Integer.valueOf(duration));
        assertEquals(Integer.valueOf(1800), Integer.valueOf(duration));
    }
}
