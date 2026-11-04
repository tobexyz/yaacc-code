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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;

public class YxcEventParserTest {
    private final YxcEventParser parser = new YxcEventParser();

    @Test
    public void zoneStatusChange() {
        YxcEvent event = parser.parse(YxcFixtures.read("event_zone_status.json"));

        assertTrue(event.isParsable());
        assertEquals(new LinkedHashSet<>(Arrays.asList("main")), event.getBlockNames());
        YxcEvent.ZoneBlock main = event.getZones().get("main");
        assertEquals("on", main.getPower());
        assertEquals(Integer.valueOf(31), main.getVolume());
        assertEquals(Boolean.FALSE, main.getMute());
        assertEquals("net_radio", main.getInput());
        assertTrue(main.isStatusUpdated());
        assertFalse(main.isPlayInfoUpdated());
        assertNull(event.getNetUsb());
        assertFalse(event.isDistInfoUpdated());
    }

    @Test
    public void playInfoUpdated() {
        YxcEvent event = parser.parse(YxcFixtures.read("event_play_info_updated.json"));

        assertTrue(event.hasBlock("netusb"));
        assertTrue(event.getNetUsb().isPlayInfoUpdated());
        assertFalse(event.getNetUsb().isPresetInfoUpdated());
        assertEquals(Integer.valueOf(42), event.getNetUsb().getPlayTime());
        assertTrue(event.getZones().isEmpty());
    }

    @Test
    public void distUpdated() {
        YxcEvent event = parser.parse(YxcFixtures.read("event_dist_updated.json"));

        assertTrue(event.hasBlock("dist"));
        assertTrue(event.isDistInfoUpdated());
    }

    @Test
    public void severalBlocksInOneDatagram() {
        YxcEvent event = parser.parse(YxcFixtures.read("event_several_blocks.json"));

        assertTrue(event.isParsable());
        assertEquals(new LinkedHashSet<>(Arrays.asList("main", "zone2", "netusb", "tuner", "dist", "clock", "system")), event.getBlockNames());
        assertTrue(event.getZones().get("main").isPlayInfoUpdated());
        assertEquals(Integer.valueOf(20), event.getZones().get("main").getVolume());
        assertEquals("standby", event.getZones().get("zone2").getPower());
        assertEquals("mc_link", event.getZones().get("zone2").getInput());
        assertNull("a value the block does not carry", event.getZones().get("zone2").getVolume());
        assertTrue(event.getNetUsb().isPresetInfoUpdated());
        assertFalse(event.isTunerPlayInfoUpdated());
        assertTrue(event.hasBlock("tuner"));
        assertTrue(event.isDistInfoUpdated());
        assertTrue(event.isClockSettingsUpdated());
        assertTrue(event.isSystemFuncStatusUpdated());
        assertFalse("unknown keys are not kept", event.hasBlock("invented_future_block"));
    }

    @Test
    public void onlyUnknownKeysIsParsableButEmpty() {
        YxcEvent event = parser.parse("{\"device_id\":\"x\",\"something\":{\"a\":1}}");

        assertTrue(event.isParsable());
        assertTrue(event.getBlockNames().isEmpty());
    }

    @Test
    public void aBlockThatIsNotAnObjectIsIgnored() {
        YxcEvent event = parser.parse("{\"main\":5,\"dist\":\"x\",\"netusb\":[1]}");

        assertTrue(event.isParsable());
        assertTrue(event.getBlockNames().isEmpty());
    }

    @Test
    public void garbageIsUnparsableAndNeverThrows() {
        String[] garbage = {null, "", "   ", "not json", "{", "{\"main\":", "[1,2]", "\"text\"", "42", "null", "{'a'", "}{"};
        for (String text : garbage) {
            YxcEvent event = parser.parse(text);
            assertNotNull(event);
            assertFalse("should be unparsable: " + text, event.isParsable());
            assertTrue(event.getBlockNames().isEmpty());
            assertTrue(event.getZones().isEmpty());
            assertNull(event.getNetUsb());
        }
    }

    @Test
    public void bytesThatAreNotUtf8AreUnparsable() {
        byte[] bytes = {'{', '"', 'a', '"', ':', (byte) 0xC3, (byte) 0x28, '}'};

        assertFalse(parser.parse(bytes, 0, bytes.length).isParsable());
    }

    @Test
    public void bytesAreDecodedFromTheGivenRange() {
        byte[] padded = ("xx" + YxcFixtures.read("event_dist_updated.json") + "yy").getBytes(StandardCharsets.UTF_8);

        YxcEvent event = parser.parse(padded, 2, padded.length - 4);

        assertTrue(event.isParsable());
        assertTrue(event.isDistInfoUpdated());
    }

    @Test
    public void badRangesAreUnparsable() {
        byte[] bytes = new byte[10];
        assertFalse(parser.parse(null, 0, 0).isParsable());
        assertFalse(parser.parse(bytes, -1, 4).isParsable());
        assertFalse(parser.parse(bytes, 8, 4).isParsable());
        assertFalse(parser.parse(bytes, 0, -1).isParsable());
    }

    @Test
    public void aDatagramMadeOfBracketsDoesNotOverflowTheStack() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 12000; i++) {
            deep.append('[');
        }

        assertFalse(parser.parse(deep.toString()).isParsable());
        assertFalse(parser.parse("{\"main\":" + deep + "}").isParsable());
    }

    @Test
    public void bracketsInsideStringsDoNotCountAsNesting() {
        StringBuilder text = new StringBuilder("{\"main\":{\"input\":\"");
        for (int i = 0; i < 100; i++) {
            text.append("[{");
        }
        text.append("\"}}");

        YxcEvent event = parser.parse(text.toString());

        assertTrue(event.isParsable());
        assertEquals(100 * 2, event.getZones().get("main").getInput().length());
    }

    @Test
    public void anOversizedDatagramIsUnparsable() {
        StringBuilder big = new StringBuilder("{\"main\":{\"input\":\"");
        for (int i = 0; i <= YxcEventParser.MAX_LENGTH; i++) {
            big.append('a');
        }
        big.append("\"}}");

        assertFalse(parser.parse(big.toString()).isParsable());
    }

    @Test
    public void aRealPlayTimeEventOfADevice() {
        YxcEvent event = new YxcEventParser().parse(YxcFixtures.read("captured/a_event_netusb_play_time.json"));

        assertTrue(event.isParsable());
        assertEquals(Integer.valueOf(140), event.getNetUsb().getPlayTime());
        assertFalse("the play time alone is no reason to read the play info again", event.getNetUsb().isPlayInfoUpdated());
        assertEquals("00000000000A", event.getDeviceId());
        assertEquals(java.util.Collections.singleton("netusb"), event.getBlockNames());
    }

    @Test
    public void theDeviceIdOfAnEventMatchesTheOneOfTheDeviceInfoIgnoringCase() throws Exception {
        YxcEvent event = new YxcEventParser().parse(YxcFixtures.read("captured/a_event_netusb_play_time.json"));
        YxcDeviceInfo info = new YxcClient(new RecordingTransport()
                .answer("system/getDeviceInfo", YxcFixtures.read("captured/a_deviceInfo.json"))).getDeviceInfo();

        assertTrue(info.getDeviceId().equalsIgnoreCase(event.getDeviceId()));
    }

    @Test
    public void anEventWithoutADeviceIdHasNone() {
        assertEquals(null, new YxcEventParser().parse("{\"main\":{\"volume\":3}}").getDeviceId());
        assertEquals(null, YxcEvent.unparsable().getDeviceId());
    }

    private static YxcEvent real(String fixture) {
        return new YxcEventParser().parse(YxcFixtures.read("captured/" + fixture));
    }

    @Test
    public void aRealVolumeEventOfTheSlider() {
        YxcEvent event = real("a_event_volume.json");

        assertTrue(event.isParsable());
        assertEquals(Integer.valueOf(29), event.getZones().get("main").getVolume());
        assertEquals(null, event.getZones().get("main").getMute());
        assertFalse("a volume event does not say that the status changed as a whole", event.getZones().get("main").isStatusUpdated());
        assertEquals("00000000000A", event.getDeviceId());
    }

    @Test
    public void aRealMuteEvent() {
        YxcEvent event = real("a_event_mute_on.json");

        assertEquals(Boolean.TRUE, event.getZones().get("main").getMute());
        assertEquals(null, event.getZones().get("main").getVolume());
    }

    @Test
    public void aRealEventWithVolumeAndMuteTogether() {
        YxcEvent event = real("a_event_volume_and_mute.json");

        assertEquals(Integer.valueOf(35), event.getZones().get("main").getVolume());
        assertEquals(Boolean.FALSE, event.getZones().get("main").getMute());
    }

    @Test
    public void aRealEventWithTwoBlocksInOneDatagram() {
        YxcEvent event = real("a_event_main_and_netusb.json");

        assertEquals(Boolean.TRUE, event.getZones().get("main").getMute());
        assertEquals(Integer.valueOf(149), event.getNetUsb().getPlayTime());
        assertEquals(new java.util.LinkedHashSet<>(java.util.Arrays.asList("main", "netusb")), event.getBlockNames());
    }

    @Test
    public void aRealGroupChangeEventSaysOnlyThatTheGroupInfoChanged() {
        YxcEvent event = real("a_event_dist_info_updated.json");

        assertTrue(event.isDistInfoUpdated());
        assertTrue(event.getZones().isEmpty());
        assertEquals(java.util.Collections.singleton("dist"), event.getBlockNames());
    }

    @Test
    public void aRealTrackChangeEventHasTheFlagsOfThePlayInfoAndTheQueue() {
        YxcEvent event = real("a_event_track_change.json");

        assertTrue(event.getNetUsb().isPlayInfoUpdated());
        assertTrue(event.getNetUsb().isPlayQueueUpdated());
        assertEquals(Integer.valueOf(0), event.getNetUsb().getPlayTime());
        assertTrue(event.getZones().get("main").isSignalInfoUpdated());
    }

    @Test
    public void aRealPlayInfoOnlyEventAndASignalInfoOnlyEvent() {
        YxcEvent play = real("a_event_play_info_updated.json");
        YxcEvent signal = real("a_event_signal_info_updated.json");

        assertTrue(play.getNetUsb().isPlayInfoUpdated());
        assertFalse(play.getNetUsb().isPlayQueueUpdated());
        assertTrue(signal.getZones().get("main").isSignalInfoUpdated());
        assertFalse(signal.getZones().get("main").isPlayInfoUpdated());
    }

    @Test
    public void aRealPowerOnEventCarriesOnlyThePower() {
        YxcEvent event = real("a_event_power_on.json");

        assertEquals("on", event.getZones().get("main").getPower());
        assertNull(event.getZones().get("main").getVolume());
        assertNull(event.getNetUsb());
    }

    @Test
    public void aRealEventCanCarryThePlayTimeAndAGroupChangeTogether() {
        YxcEvent event = real("a_event_play_time_and_dist_info_updated.json");

        assertEquals(Integer.valueOf(106), event.getNetUsb().getPlayTime());
        assertTrue(event.isDistInfoUpdated());
    }

    @Test
    public void aRealWakeUpEventHasTheAccountFlagBesideThePlayFlags() {
        YxcEvent event = real("a_event_wake_up.json");

        assertTrue(event.getNetUsb().isAccountUpdated());
        assertTrue(event.getNetUsb().isPlayInfoUpdated());
        assertTrue(event.getNetUsb().isPlayQueueUpdated());
        assertFalse(real("a_event_track_change.json").getNetUsb().isAccountUpdated());
    }

    @Test
    public void aRealPowerOffEventSaysStandby() {
        YxcEvent event = real("a_event_power_standby.json");

        assertEquals("standby", event.getZones().get("main").getPower());
        assertNull(event.getNetUsb());
    }

    @Test
    public void aRealPauseAndPlayEventHasOnlyTheSignalAndPlayInfoFlags() {
        YxcEvent event = real("a_event_pause_play.json");

        assertTrue(event.getZones().get("main").isSignalInfoUpdated());
        assertTrue(event.getNetUsb().isPlayInfoUpdated());
        assertNull(event.getNetUsb().getPlayTime());
        assertFalse(event.getNetUsb().isPlayQueueUpdated());
    }
}
