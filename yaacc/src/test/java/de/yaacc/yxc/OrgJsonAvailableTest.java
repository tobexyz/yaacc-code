/*
 *
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
package de.yaacc.yxc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The app's unit tests run with {@code unitTests.returnDefaultValues = true}: the Android stubs of {@code org.json}
 * would answer null / 0 for everything and every parser test would pass or fail for the wrong reason. This test fails
 * if the real implementation ({@code testImplementation 'org.json:json'}) is not the one on the test classpath.
 */
public class OrgJsonAvailableTest {

    @Test
    public void parsesADocumentWithTheRealImplementation() throws Exception {
        JSONObject json = new JSONObject("{\"response_code\":0,\"zone\":[{\"id\":\"main\"}],\"name\":\"abc\",\"ok\":true}");

        assertNotNull("org.json returned null: the Android stub is on the test classpath", json.opt("zone"));
        assertEquals(0, json.getInt("response_code"));
        assertEquals("abc", json.getString("name"));
        assertTrue(json.getBoolean("ok"));
        JSONArray zones = json.getJSONArray("zone");
        assertEquals(1, zones.length());
        assertEquals("main", zones.getJSONObject(0).getString("id"));
        assertEquals("{\"a\":[1,\"x\"]}", new JSONObject().put("a", new JSONArray().put(1).put("x")).toString());
    }

    @Test
    public void ourFixturesAreValidJson() throws Exception {
        String[] names = {"device_info", "features_two_zones", "zone_status_main", "zone_status_standby", "func_status",
                "dist_info_none", "dist_info_server", "dist_info_client", "netusb_play_info", "ok", "error_initializing",
                "error_invalid_parameter", "error_guarded", "error_timeout", "event_zone_status",
                "event_play_info_updated", "event_dist_updated", "event_several_blocks"};
        for (String name : names) {
            assertTrue(name, new JSONObject(YxcFixtures.read(name + ".json")).length() > 0);
        }
    }
}
