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

import java.util.Arrays;
import java.util.Collections;

/** The value objects against the fixtures: what the device reports is what the object says, nothing more. */
public class YxcModelParsingTest {

    private static YxcClient clientAnswering(String path, String fixture) {
        return new YxcClient(new RecordingTransport().answer(path, YxcFixtures.read(fixture)));
    }

    @Test
    public void deviceInfo() throws Exception {
        YxcDeviceInfo info = clientAnswering("system/getDeviceInfo", "device_info.json").getDeviceInfo();

        assertEquals("TESTDEVICE01", info.getDeviceId());
        assertEquals("TEST0001", info.getSystemId());
        assertEquals("TestModel-A", info.getModelName());
        assertEquals("2.01", info.getSystemVersion());
        assertEquals(1.19, info.getApiVersion(), 0.0001);
        assertTrue(info.isApiVersionAtLeast(1.17));
        assertFalse(info.isApiVersionAtLeast(1.2));
        assertFalse("the ids stay out of log lines", info.toString().contains("TESTDEVICE01"));
    }

    @Test
    public void deviceInfoWithoutAnyKeyIsEmptyNotAnError() throws Exception {
        YxcDeviceInfo info = new YxcClient(new RecordingTransport().answerEverythingWith("{\"response_code\":0}")).getDeviceInfo();

        assertNull(info.getSystemId());
        assertNull(info.getApiVersion());
        assertFalse(info.isApiVersionAtLeast(0));
    }

    @Test
    public void featuresListTheZonesWithTheirInputsAndRanges() throws Exception {
        YxcFeatures features = clientAnswering("system/getFeatures", "features_two_zones.json").getFeatures();

        assertEquals(2, features.getZones().size());
        YxcZoneFeatures main = features.getZone("main");
        assertEquals(Arrays.asList("mc_link", "net_radio", "optical", "line"), main.getInputIds());
        assertEquals(Arrays.asList("straight", "movie", "music"), main.getSoundProgramIds());
        assertTrue(main.hasFunction("sound_program"));
        assertFalse(main.hasFunction("subwoofer_volume"));
        assertTrue(main.canJoinGroup());
        YxcRange volume = main.getRange("volume");
        assertEquals(0, volume.getMin());
        assertEquals(60, volume.getMax());
        assertEquals(1, volume.getStep());
        assertEquals(Arrays.asList("manual", "bypass"), main.getOptions("equalizer_mode_list"));
        assertEquals(Arrays.asList("balanced", "lip_sync"), main.getOptions("link_audio_delay_list"));
        assertEquals("a list the device does not report is empty", Collections.emptyList(), main.getOptions("surr_decoder_type_list"));
        assertFalse("function lists are not option lists", main.getOptionLists().containsKey("func_list"));
        assertFalse(main.getOptionLists().containsKey("input_list"));
    }

    @Test
    public void featuresSecondZoneHasItsOwnScale() throws Exception {
        YxcFeatures features = clientAnswering("system/getFeatures", "features_two_zones.json").getFeatures();

        YxcZoneFeatures zone2 = features.getZone("zone2");
        YxcRange volume = zone2.getRange("volume");
        assertEquals(1, volume.getMin());
        assertEquals(61, volume.getMax());
        assertEquals(2, volume.getStep());
        assertTrue("counted from the minimum", volume.accepts(3));
        assertFalse(volume.accepts(2));
        assertFalse(volume.accepts(63));
        assertFalse(volume.accepts(-1));
        assertNull(zone2.getRange("equalizer"));
        assertNull(features.getZone("zone3"));
    }

    @Test
    public void featuresDeviceLevel() throws Exception {
        YxcFeatures features = clientAnswering("system/getFeatures", "features_two_zones.json").getFeatures();

        assertTrue(features.hasSystemFunction("dimmer"));
        assertTrue("an unknown function name is just carried along", features.hasSystemFunction("invented_future_function"));
        assertEquals(-1, features.getSystemRange("dimmer").getMin());
        assertEquals(4, features.getSystemInputs().size());
        YxcSystemInput netRadio = features.getSystemInput("net_radio");
        assertTrue(netRadio.isDistributionEnabled());
        assertEquals("netusb", netRadio.getPlayInfoType());
        assertFalse(features.getSystemInput("mc_link").isDistributionEnabled());
        assertNull(features.getSystemInput("nothing"));
        assertTrue(features.hasNetUsb());
        assertEquals(Collections.singletonList("main"), features.getServerZones());
        assertNull("the invented sample has a block with the zone list only", features.getDistribution().getClientMax());
        assertFalse(features.getDistribution().hasMcSurround());
    }

    @Test
    public void featuresOfAMinimalDevice() throws Exception {
        YxcFeatures features = new YxcClient(new RecordingTransport().answerEverythingWith(
                "{\"response_code\":0,\"zone\":[{\"id\":\"main\"},{\"no_id\":1}],\"netusb\":{}}")).getFeatures();

        assertEquals("an entry without an id is skipped", 1, features.getZones().size());
        YxcZoneFeatures main = features.getZone("main");
        assertTrue(main.getFunctions().isEmpty());
        assertFalse(main.canJoinGroup());
        assertTrue(features.getSystemFunctions().isEmpty());
        assertFalse("a network section without functions does not count", features.hasNetUsb());
        assertTrue(features.getServerZones().isEmpty());
        assertNull("no block, no limit: the caller decides what to assume", features.getDistribution());
    }

    @Test
    public void aDistributionBlockOfTheWrongTypesReadsAsAbsentValues() throws Exception {
        YxcFeatures features = new YxcClient(new RecordingTransport().answerEverythingWith(
                "{\"response_code\":0,\"distribution\":{\"version\":\"x\",\"compatible_client\":[2,\"y\",3],"
                        + "\"client_max\":\"many\",\"server_zone_list\":\"main\",\"mc_surround\":5}}")).getFeatures();

        YxcDistributionFeatures distribution = features.getDistribution();
        assertNull(distribution.getVersion());
        assertEquals(Arrays.asList(2, 3), distribution.getCompatibleClients());
        assertNull(distribution.getClientMax());
        assertTrue(distribution.getServerZones().isEmpty());
        assertFalse("only an object counts", distribution.hasMcSurround());
    }

    @Test
    public void theLinkInputIsRecognizedFromTheStatus() throws Exception {
        assertTrue(statusWithInput("mc_link").isLinkInput());
        assertTrue(statusWithInput("MC_LINK").isLinkInput());
        assertFalse(statusWithInput("net_radio").isLinkInput());
        assertFalse(new YxcClient(new RecordingTransport().answerEverythingWith("{\"response_code\":0}"))
                .getZoneStatus("main").isLinkInput());
    }

    private static YxcZoneStatus statusWithInput(String input) throws Exception {
        return new YxcClient(new RecordingTransport().answerEverythingWith(
                "{\"response_code\":0,\"input\":\"" + input + "\"}")).getZoneStatus("main");
    }

    @Test
    public void rangeEntriesWithoutMinOrMaxAreSkipped() throws Exception {
        YxcFeatures features = new YxcClient(new RecordingTransport().answerEverythingWith(
                "{\"response_code\":0,\"zone\":[{\"id\":\"main\",\"range_step\":[{\"id\":\"volume\"},{\"id\":\"x\",\"min\":5,\"max\":1},"
                        + "{\"id\":\"y\",\"min\":0,\"max\":10}]}]}")).getFeatures();

        YxcZoneFeatures main = features.getZone("main");
        assertNull(main.getRange("volume"));
        assertNull(main.getRange("x"));
        assertEquals("a missing step is 1", 1, main.getRange("y").getStep());
    }

    @Test
    public void zoneStatusOfAPlayingZone() throws Exception {
        YxcZoneStatus status = clientAnswering("main/getStatus", "zone_status_main.json").getZoneStatus("main");

        assertTrue(status.isPowerOn());
        assertEquals("on", status.getPower());
        assertEquals(Integer.valueOf(30), status.getVolume());
        assertEquals(Boolean.FALSE, status.getMute());
        assertEquals("net_radio", status.getInput());
        assertEquals("straight", status.getSoundProgram());
    }

    @Test
    public void zoneStatusInStandbyWithoutSoundProgram() throws Exception {
        YxcZoneStatus status = clientAnswering("zone2/getStatus", "zone_status_standby.json").getZoneStatus("zone2");

        assertFalse(status.isPowerOn());
        assertEquals("standby", status.getPower());
        assertEquals(Boolean.TRUE, status.getMute());
        assertNull(status.getSoundProgram());
    }

    @Test
    public void zoneStatusWithValuesOfTheWrongType() throws Exception {
        YxcZoneStatus status = new YxcClient(new RecordingTransport().answerEverythingWith(
                "{\"response_code\":0,\"power\":\"on\",\"volume\":\"loud\",\"mute\":\"no\",\"input\":null}")).getZoneStatus("main");

        assertNull(status.getVolume());
        assertNull(status.getMute());
        assertNull(status.getInput());
    }

    @Test
    public void funcStatus() throws Exception {
        YxcFuncStatus status = clientAnswering("system/getFuncStatus", "func_status.json").getFuncStatus();

        assertEquals(Boolean.TRUE, status.getSpeakerA());
        assertEquals(Boolean.FALSE, status.getSpeakerB());
        assertEquals(Integer.valueOf(2), status.getDimmer());
    }

    @Test
    public void playInfo() throws Exception {
        YxcPlayInfo info = clientAnswering("netusb/getPlayInfo", "netusb_play_info.json").getPlayInfo();

        assertEquals("net_radio", info.getInput());
        assertEquals("play", info.getPlayback());
        assertEquals("off", info.getRepeat());
        assertEquals("Test Track", info.getTrack());
        assertEquals("Test Artist", info.getArtist());
        assertEquals("Test Album", info.getAlbum());
        assertEquals("/art/test.jpg", info.getAlbumArtUrl());
        assertEquals(Integer.valueOf(12), info.getPlayTime());
        assertEquals(Integer.valueOf(240), info.getTotalTime());
        assertNotNull(info.getShuffle());
    }
}
