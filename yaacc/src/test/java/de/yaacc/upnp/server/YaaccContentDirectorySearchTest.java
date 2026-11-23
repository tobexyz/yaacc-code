/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.upnp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.fourthline.cling.model.DefaultServiceManager;
import org.fourthline.cling.model.ServiceManager;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.junit.After;
import org.junit.Test;

import de.yaacc.upnp.server.contentdirectory.YaaccContentDirectory;

/** The Search action of the server (issue #336), through the receiving path of a control point: what its description promises and how it refuses. */
public class YaaccContentDirectorySearchTest {

    private ServiceHarness<YaaccContentDirectory> harness;

    @After
    public void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    private ServiceHarness<YaaccContentDirectory> directory() {
        harness = new ServiceHarness<>(YaaccContentDirectory.class, "urn:schemas-upnp-org:service:ContentDirectory:1", "ContentDirectory",
                service -> (ServiceManager<YaaccContentDirectory>) new DefaultServiceManager<YaaccContentDirectory>(service, null) {
                    @Override
                    protected YaaccContentDirectory createServiceInstance() {
                        Context context = mock(Context.class);
                        when(context.getSharedPreferences(any(), anyInt())).thenReturn(mock(SharedPreferences.class));
                        return new YaaccContentDirectory(context, 0);
                    }
                });
        return harness;
    }

    private static StreamResponseMessage search(ServiceHarness<YaaccContentDirectory> h, String container, String criteria) {
        return h.soap("Search", "ContainerID", container, "SearchCriteria", criteria, "Filter", "*", "StartingIndex", "0",
                "RequestedCount", "10", "SortCriteria", "");
    }

    @Test
    public void theSearchCapabilitiesNameTheSearchableProperties() {
        ServiceHarness<YaaccContentDirectory> h = directory();

        StreamResponseMessage response = h.soap("GetSearchCapabilities");

        assertTrue(ServiceHarness.describe(response), ServiceHarness.succeeded(response));
        String body = response.getBodyString();
        for (String property : new String[]{"dc:title", "dc:creator", "upnp:artist", "upnp:album", "upnp:class"}) {
            assertTrue(property + " in " + body, body.contains(property));
        }
    }

    @Test
    public void aSearchInAFolderThatIsNotSearchedIsAnEmptyAnswerNotAnError() {
        ServiceHarness<YaaccContentDirectory> h = directory();

        StreamResponseMessage response = search(h, "some-saf-folder", "dc:title contains \"west\"");

        assertTrue(ServiceHarness.describe(response), ServiceHarness.succeeded(response));
        String body = response.getBodyString();
        assertTrue(body, body.contains("<NumberReturned>0</NumberReturned>"));
        assertTrue(body, body.contains("<TotalMatches>0</TotalMatches>"));
    }

    @Test
    public void criteriaThatDoNotParseAreRefusedWith708() {
        ServiceHarness<YaaccContentDirectory> h = directory();

        StreamResponseMessage response = search(h, "0", "dc:title contains (");

        assertFalse(ServiceHarness.describe(response), ServiceHarness.succeeded(response));
        assertTrue(ServiceHarness.describe(response), response.getBodyString().contains("<errorCode>708</errorCode>"));
    }

    @Test
    public void aPropertyTheServerDoesNotSearchByIsRefusedWith708() {
        ServiceHarness<YaaccContentDirectory> h = directory();

        StreamResponseMessage response = search(h, "0", "dc:date > \"2020-01-01\"");

        assertFalse(ServiceHarness.describe(response), ServiceHarness.succeeded(response));
        assertTrue(ServiceHarness.describe(response), response.getBodyString().contains("<errorCode>708</errorCode>"));
    }

    @Test
    public void aSortCriterionTheServerDoesNotKnowIsRefusedWith709() {
        ServiceHarness<YaaccContentDirectory> h = directory();

        StreamResponseMessage response = h.soap("Search", "ContainerID", "0", "SearchCriteria", "*", "Filter", "*", "StartingIndex", "0",
                "RequestedCount", "10", "SortCriteria", "+upnp:artist");

        assertFalse(ServiceHarness.describe(response), ServiceHarness.succeeded(response));
        assertTrue(ServiceHarness.describe(response), response.getBodyString().contains("<errorCode>709</errorCode>"));
    }
}
