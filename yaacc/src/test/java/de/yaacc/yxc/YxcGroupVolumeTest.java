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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The relative group volume. */
public class YxcGroupVolumeTest {

    private static Map<String, Integer> levels(Object... pairs) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return result;
    }

    @Test
    public void everyMemberMovesByTheSameNumberOfPoints() {
        Map<String, Integer> result = YxcGroupVolume.apply(levels("server", 40, "client", 60), 10);

        assertEquals(levels("server", 50, "client", 70), result);
        assertEquals("the offset between the members stays", 20, result.get("client") - result.get("server"));
    }

    @Test
    public void everyMemberIsClampedForItself() {
        assertEquals(levels("a", 90, "b", 100, "c", 100), YxcGroupVolume.apply(levels("a", 40, "b", 60, "c", 95), 50));
        assertEquals(levels("a", 0, "b", 10, "c", 0), YxcGroupVolume.apply(levels("a", 20, "b", 30, "c", 5), -20));
    }

    @Test
    public void draggingBackRestoresEveryMembersOwnLevelAlsoAfterClamping() {
        Map<String, Integer> start = levels("a", 40, "b", 95);

        YxcGroupVolume.apply(start, 30); // b is clamped at 100

        assertEquals("computed from the start values, not from the clamped result", start, YxcGroupVolume.apply(start, 0));
    }

    @Test
    public void noMovementChangesNothing() {
        Map<String, Integer> start = levels("a", 12, "b", 77);

        assertEquals(start, YxcGroupVolume.apply(start, 0));
        assertTrue(YxcGroupVolume.apply(new LinkedHashMap<String, Integer>(), 20).isEmpty());
    }

    @Test
    public void theSliderShowsTheAverage() {
        assertEquals(50, YxcGroupVolume.average(levels("a", 40, "b", 60)));
        assertEquals(34, YxcGroupVolume.average(levels("a", 33, "b", 34, "c", 36)));
        assertEquals(-1, YxcGroupVolume.average(new LinkedHashMap<String, Integer>()));
    }

    @Test
    public void levelsAreInPercentOfEachDevicesOwnScaleAndSkipUnknownVolumes() throws Exception {
        YxcClient a = new YxcClient(new RecordingTransport()
                .answer("main/getStatus", YxcFixtures.read("captured/a_status_unlinked.json"))); // 27 of 60
        YxcClient c = new YxcClient(new RecordingTransport()
                .answer("main/getStatus", YxcFixtures.read("captured/c_status_linked_client.json"))); // 24 of 60
        List<YxcDeviceSnapshot> members = new ArrayList<>();
        members.add(new YxcDeviceSnapshot("a", "192.0.2.1", YxcDeviceState.unknown().withZone(a.getZoneStatus("main"))));
        members.add(new YxcDeviceSnapshot("c", "192.0.2.2", YxcDeviceState.unknown().withZone(c.getZoneStatus("main"))));
        members.add(new YxcDeviceSnapshot("unread", "192.0.2.3", YxcDeviceState.unknown()));

        Map<String, Integer> result = YxcGroupVolume.levels(members);

        assertEquals(levels("a", 45, "c", 40), result);
        assertEquals(43, YxcGroupVolume.average(result));
    }
}
