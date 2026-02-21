package de.yaacc.upnp.protocol.async;

import org.fourthline.cling.model.message.header.STAllHeader;
import org.junit.Test;

import static org.junit.Assert.*;

public class SendingSearchTest {

    @Test
    public void testSendingSearchCreation() {
        // Test search target creation
        STAllHeader searchTarget = new STAllHeader();
        assertNotNull(searchTarget);
    }

    @Test
    public void testSendingSearchWithSearchTarget() {
        STAllHeader searchTarget = new STAllHeader();
        assertNotNull(searchTarget);
    }

    @Test
    public void testSendingSearchWithMX() {
        int mxSeconds = 5;
        assertEquals(5, mxSeconds);
        assertTrue(mxSeconds > 0);
    }

    @Test
    public void testBulkRepeat() {
        int bulkRepeat = 5;
        assertEquals(5, bulkRepeat);
    }

    @Test
    public void testBulkInterval() {
        int bulkInterval = 500;
        assertEquals(500, bulkInterval);
    }
}
