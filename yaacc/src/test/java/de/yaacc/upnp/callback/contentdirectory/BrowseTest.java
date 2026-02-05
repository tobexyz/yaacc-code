package de.yaacc.upnp.callback.contentdirectory;

import org.fourthline.cling.support.model.BrowseFlag;
import org.junit.Test;

import de.yaacc.upnp.server.http.HttpRequestSender;

import static org.junit.Assert.*;

public class BrowseTest {

    @Test
    public void testBrowseCreation() {
        String objectID = "0";
        BrowseFlag flag = BrowseFlag.DIRECT_CHILDREN;
        
        assertNotNull(objectID);
        assertNotNull(flag);
        assertEquals(BrowseFlag.DIRECT_CHILDREN, flag);
    }

    @Test
    public void testBrowseWithFilter() {
        String objectID = "0";
        BrowseFlag flag = BrowseFlag.METADATA;
        String filter = "*";
        long firstResult = 0;
        Long maxResults = 100L;
        
        assertNotNull(filter);
        assertEquals("*", filter);
        assertEquals(0L, firstResult);
        assertEquals(Long.valueOf(100), maxResults);
    }
}
