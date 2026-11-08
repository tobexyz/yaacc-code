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

/**
 * The scrubbed answers of three real devices (a, b, c; see {@code src/test/resources/yxc/captured/README.md}) read with
 * the model classes. The three differ on purpose: a tuner device of an older generation, a device with a surround block,
 * a device that was a group client. The assertions state what the files contain, not what the notes expected.
 */
public class YxcCapturedDevicesTest {

    private static YxcClient device(String prefix, String statusFile) {
        RecordingTransport transport = new RecordingTransport()
                .answer("system/getDeviceInfo", YxcFixtures.read("captured/" + prefix + "_deviceInfo.json"))
                .answer("system/getFeatures", YxcFixtures.read("captured/" + prefix + "_features.json"))
                .answer("system/getFuncStatus", YxcFixtures.read("captured/" + prefix + "_funcStatus.json"))
                .answer("netusb/getPlayInfo", YxcFixtures.read("captured/" + prefix + "_playInfo.json"));
        if (statusFile != null) {
            transport.answer("main/getStatus", YxcFixtures.read("captured/" + statusFile + ".json"));
        }
        return new YxcClient(transport);
    }

    private static void assertVolumeRangeOfTheCapturedDevices(YxcZoneFeatures main) {
        YxcRange volume = main.getRange("volume");
        assertEquals(0, volume.getMin());
        assertEquals(60, volume.getMax());
        assertEquals(1, volume.getStep());
        YxcRange equalizer = main.getRange("equalizer");
        assertEquals(-10, equalizer.getMin());
        assertEquals(10, equalizer.getMax());
    }

    // ---- device a: tuner, older generation, client_max 9, was the server of a group

    @Test
    public void deviceAInfo() throws Exception {
        YxcDeviceInfo info = device("a", null).getDeviceInfo();

        assertEquals("TEST-MODEL-A", info.getModelName());
        assertEquals("00000000000A", info.getDeviceId());
        assertEquals("0000000A", info.getSystemId());
        assertEquals("2.16", info.getSystemVersion());
        assertEquals(2.08, info.getApiVersion(), 0.0001);
        assertTrue(info.isApiVersionAtLeast(1.17));
    }

    @Test
    public void deviceAFeatures() throws Exception {
        YxcFeatures features = device("a", null).getFeatures();

        assertEquals(9, features.getSystemFunctions().size());
        assertTrue(features.hasSystemFunction("dimmer"));
        assertEquals(12, features.getSystemInputs().size());
        YxcSystemInput napster = features.getSystemInput("napster");
        assertTrue(napster.isDistributionEnabled());
        assertTrue(napster.isAccountEnabled());
        assertFalse(napster.isRenameEnabled());
        assertEquals("netusb", napster.getPlayInfoType());
        YxcSystemInput tuner = features.getSystemInput("tuner");
        assertEquals("tuner", tuner.getPlayInfoType());
        assertTrue(tuner.isRenameEnabled());
        assertFalse(tuner.isAccountEnabled());
        YxcSystemInput link = features.getSystemInput("mc_link");
        assertFalse("the group input can not be handed on", link.isDistributionEnabled());
        assertTrue(link.isRenameEnabled());
        assertFalse(features.getSystemInput("airplay").isDistributionEnabled());
        assertEquals(-1, features.getSystemRange("dimmer").getMin());
        assertEquals(2, features.getSystemRange("dimmer").getMax());
        assertTrue(features.hasNetUsb());

        assertEquals(1, features.getZones().size());
        YxcZoneFeatures main = features.getZone("main");
        assertEquals(10, main.getFunctions().size());
        assertTrue(main.hasFunction("link_audio_quality"));
        assertFalse(main.hasFunction("sound_program"));
        assertVolumeRangeOfTheCapturedDevices(main);
        assertTrue("no sound programs on this device", main.getSoundProgramIds().isEmpty());
        assertEquals(Arrays.asList("manual"), main.getEqualizerModes());
        assertEquals(Arrays.asList("speed", "standard", "stability"), main.getLinkControlOptions());
        assertEquals(Arrays.asList("audio_sync_on", "audio_sync_off"), main.getLinkAudioDelayOptions());
        assertEquals(Arrays.asList("compressed", "uncompressed"), main.getLinkAudioQualityOptions());
        assertEquals(12, main.getInputIds().size());
        assertTrue(main.getInputIds().contains("tuner"));
        assertTrue("the zone offers the group input", main.canJoinGroup());
    }

    @Test
    public void deviceADistributionFeatures() throws Exception {
        YxcDistributionFeatures distribution = device("a", null).getFeatures().getDistribution();

        assertNotNull(distribution);
        assertEquals(2.0, distribution.getVersion(), 0.0001);
        assertEquals(Collections.singletonList(2), distribution.getCompatibleClients());
        assertEquals(Integer.valueOf(9), distribution.getClientMax());
        assertEquals(Collections.singletonList("main"), distribution.getServerZones());
        assertFalse(distribution.hasMcSurround());
    }

    @Test
    public void deviceAStatusAsTheServerOfAGroup() throws Exception {
        YxcZoneStatus status = device("a", "a_status_linked_server").getZoneStatus("main");

        assertTrue(status.isPowerOn());
        assertEquals(Integer.valueOf(0), status.getSleep());
        assertEquals(Integer.valueOf(27), status.getVolume());
        assertEquals(Integer.valueOf(60), status.getMaxVolume());
        assertEquals(Boolean.FALSE, status.getMute());
        assertEquals("tuner", status.getInput());
        assertNull("this device sends no input text", status.getInputText());
        assertNull("this device has no sound programs", status.getSoundProgram());
        assertEquals(Boolean.TRUE, status.getDistributionEnabled());
        assertFalse("a server keeps its own input", status.isLinkInput());
        assertEquals("manual", status.getEqualizer().getMode());
        assertEquals(Integer.valueOf(0), status.getEqualizer().getLow());
        assertEquals(Integer.valueOf(0), status.getEqualizer().getMid());
        assertEquals(Integer.valueOf(0), status.getEqualizer().getHigh());
        assertEquals("stability", status.getLinkControl());
        assertEquals("audio_sync_on", status.getLinkAudioDelay());
        assertEquals("compressed", status.getLinkAudioQuality());
        assertEquals(Integer.valueOf(0), status.getDisableFlags());
    }

    @Test
    public void deviceAStatusInStandby() throws Exception {
        YxcZoneStatus status = device("a", "a_status_unlinked").getZoneStatus("main");

        assertFalse(status.isPowerOn());
        assertEquals("standby", status.getPower());
    }

    @Test
    public void deviceAFuncStatusAndPlayInfo() throws Exception {
        YxcClient client = device("a", null);

        YxcFuncStatus func = client.getFuncStatus();
        assertEquals(Integer.valueOf(-1), func.getDimmer());
        assertEquals(Boolean.TRUE, func.getAutoPowerStandby());
        assertNull(func.getSpeakerA());

        YxcPlayInfo play = client.getPlayInfo();
        assertEquals("server", play.getInput());
        assertEquals("stop", play.getPlayback());
        assertEquals("off", play.getRepeat());
        assertEquals("on", play.getShuffle());
        assertEquals(Arrays.asList("off", "one", "all"), play.getRepeatAvailable());
        assertEquals(Arrays.asList("off", "on"), play.getShuffleAvailable());
        assertEquals(Long.valueOf(83886591L), play.getAttribute());
    }

    @Test
    public void deviceAGroupInfoAsAServer() throws Exception {
        YxcDistributionInfo info = new YxcClient(new RecordingTransport()
                .answer("dist/getDistributionInfo", YxcFixtures.read("captured/a_distInfo_server.json"))).getDistributionInfo();

        assertEquals(YxcDistributionInfo.Role.SERVER, info.getRole());
        assertEquals("working", info.getStatus());
        assertEquals("main", info.getServerZone());
        assertFalse(info.isNullGroup());
    }

    // ---- device b: surround block, sound programs, no audio quality setting, client_max 19

    @Test
    public void deviceBInfo() throws Exception {
        YxcDeviceInfo info = device("b", null).getDeviceInfo();

        assertEquals("TEST-MODEL-B", info.getModelName());
        assertEquals("00000000000B", info.getDeviceId());
        assertEquals("0000000B", info.getSystemId());
        assertEquals("2.12", info.getSystemVersion());
        assertEquals(2.17, info.getApiVersion(), 0.0001);
        assertFalse("the ids stay out of log lines", info.toString().contains("0000000B"));
    }

    @Test
    public void deviceBFeatures() throws Exception {
        YxcFeatures features = device("b", null).getFeatures();

        assertEquals(16, features.getSystemFunctions().size());
        assertEquals(14, features.getSystemInputs().size());
        assertEquals("none", features.getSystemInput("aux1").getPlayInfoType());
        assertTrue(features.getSystemInput("aux1").isRenameEnabled());
        assertTrue(features.getSystemInput("amazon_music").isAccountEnabled());
        assertEquals(0, features.getSystemRange("dimmer").getMin());
        assertEquals(2, features.getSystemRange("dimmer").getMax());
        YxcZoneFeatures main = features.getZone("main");
        assertVolumeRangeOfTheCapturedDevices(main);
        assertTrue(main.hasFunction("sound_program"));
        assertEquals(Arrays.asList("standard", "bass_booster"), main.getSoundProgramIds());
        assertEquals(Arrays.asList("speed", "standard", "stability"), main.getLinkControlOptions());
        assertEquals(Arrays.asList("audio_sync_on", "balanced", "audio_sync_off"), main.getLinkAudioDelayOptions());
        assertTrue("no audio quality setting on this device", main.getLinkAudioQualityOptions().isEmpty());
        assertFalse(main.hasFunction("link_audio_quality"));
        assertTrue(main.canJoinGroup());
        assertEquals(14, main.getInputIds().size());
    }

    @Test
    public void deviceBDistributionFeaturesHaveASurroundBlock() throws Exception {
        YxcDistributionFeatures distribution = device("b", null).getFeatures().getDistribution();

        assertEquals(Integer.valueOf(19), distribution.getClientMax());
        assertTrue(distribution.hasMcSurround());
        assertEquals(Collections.singletonList("main"), distribution.getServerZones());
        assertEquals(Collections.singletonList("main"), device("b", null).getFeatures().getServerZones());
    }

    @Test
    public void deviceBStatusUnlinked() throws Exception {
        YxcZoneStatus status = device("b", "b_status_unlinked").getZoneStatus("main");

        assertFalse(status.isPowerOn());
        assertEquals(Integer.valueOf(32), status.getVolume());
        assertEquals(Integer.valueOf(60), status.getMaxVolume());
        assertEquals("net_radio", status.getInput());
        assertEquals("Net Radio", status.getInputText());
        assertEquals("bass_booster", status.getSoundProgram());
        assertEquals(Boolean.TRUE, status.getDistributionEnabled());
        assertFalse(status.isLinkInput());
        assertEquals("manual", status.getEqualizer().getMode());
        assertEquals("standard", status.getLinkControl());
        assertEquals("balanced", status.getLinkAudioDelay());
        assertNull(status.getLinkAudioQuality());
        assertEquals(Integer.valueOf(0), status.getDisableFlags());
    }

    @Test
    public void deviceBFuncStatusAndPlayInfo() throws Exception {
        YxcClient client = device("b", null);

        assertEquals(Integer.valueOf(2), client.getFuncStatus().getDimmer());
        assertEquals(Boolean.TRUE, client.getFuncStatus().getAutoPowerStandby());
        YxcPlayInfo play = client.getPlayInfo();
        assertEquals("net_radio", play.getInput());
        assertEquals("stop", play.getPlayback());
        assertEquals("off", play.getRepeat());
        assertEquals("off", play.getShuffle());
        assertTrue("a radio station offers neither", play.getRepeatAvailable().isEmpty());
        assertTrue(play.getShuffleAvailable().isEmpty());
        assertEquals(Long.valueOf(16785411L), play.getAttribute());
    }

    @Test
    public void deviceBGroupInfoNotLinked() throws Exception {
        YxcDistributionInfo info = new YxcClient(new RecordingTransport()
                .answer("dist/getDistributionInfo", YxcFixtures.read("captured/b_distInfo_none.json"))).getDistributionInfo();

        assertEquals("none", info.getRoleWord());
        assertEquals(YxcDistributionInfo.Role.NONE, info.getRole());
        assertTrue("the real answer has the all-zero group id", info.isNullGroup());
        assertTrue(info.hasMcSurround());
        assertTrue(info.getClients().isEmpty());
    }

    // ---- device c: was a group client

    @Test
    public void deviceCInfo() throws Exception {
        YxcDeviceInfo info = device("c", null).getDeviceInfo();

        assertEquals("TEST-MODEL-C", info.getModelName());
        assertEquals("00000000000C", info.getDeviceId());
        assertEquals("0000000C", info.getSystemId());
        assertEquals("1.18", info.getSystemVersion());
        assertEquals(2.17, info.getApiVersion(), 0.0001);
    }

    @Test
    public void deviceCFeatures() throws Exception {
        YxcFeatures features = device("c", null).getFeatures();

        assertEquals(15, features.getSystemFunctions().size());
        assertEquals(15, features.getSystemInputs().size());
        assertEquals("cd", features.getSystemInput("cd").getPlayInfoType());
        assertEquals("tuner", features.getSystemInput("tuner").getPlayInfoType());
        assertTrue(features.getSystemInput("usb").isRenameEnabled());
        assertEquals(-1, features.getSystemRange("dimmer").getMin());
        assertEquals(9, features.getSystemRange("dimmer").getMax());
        YxcZoneFeatures main = features.getZone("main");
        assertVolumeRangeOfTheCapturedDevices(main);
        assertEquals(Arrays.asList("standard", "bass_booster"), main.getSoundProgramIds());
        assertEquals(Arrays.asList("audio_sync_on", "balanced", "audio_sync_off"), main.getLinkAudioDelayOptions());
        assertTrue(main.getLinkAudioQualityOptions().isEmpty());
        YxcDistributionFeatures distribution = features.getDistribution();
        assertEquals(Integer.valueOf(19), distribution.getClientMax());
        assertFalse(distribution.hasMcSurround());
        assertEquals(Collections.singletonList(2), distribution.getCompatibleClients());
    }

    @Test
    public void deviceCStatusAsAGroupClient() throws Exception {
        YxcZoneStatus status = device("c", "c_status_linked_client").getZoneStatus("main");

        assertTrue(status.isPowerOn());
        assertEquals("mc_link", status.getInput());
        assertEquals("MC Link", status.getInputText());
        assertEquals(Boolean.FALSE, status.getDistributionEnabled());
        assertTrue(status.isLinkInput());
        assertEquals(Integer.valueOf(24), status.getVolume());
        assertEquals("bass_booster", status.getSoundProgram());
        assertEquals("balanced", status.getLinkAudioDelay());
    }

    @Test
    public void deviceCStatusLaterAndUnlinked() throws Exception {
        assertEquals(Integer.valueOf(38), device("c", "c_status_linked_client_later").getZoneStatus("main").getVolume());
        assertTrue(device("c", "c_status_linked_client_later").getZoneStatus("main").isLinkInput());

        YxcZoneStatus unlinked = device("c", "c_status_unlinked").getZoneStatus("main");
        assertEquals("tuner", unlinked.getInput());
        assertEquals("Tuner", unlinked.getInputText());
        assertEquals(Boolean.TRUE, unlinked.getDistributionEnabled());
        assertFalse(unlinked.isLinkInput());
    }

    @Test
    public void deviceCFuncStatusAndPlayInfo() throws Exception {
        YxcClient client = device("c", null);

        assertEquals(Integer.valueOf(-1), client.getFuncStatus().getDimmer());
        YxcPlayInfo play = client.getPlayInfo();
        assertEquals("spotify", play.getInput());
        assertEquals("all", play.getRepeat());
        assertEquals("on", play.getShuffle());
        assertEquals(Arrays.asList("off", "one", "all"), play.getRepeatAvailable());
        assertTrue(play.getShuffleAvailable().isEmpty());
        assertEquals(Long.valueOf(16777503L), play.getAttribute());
        assertEquals("a stopped device reported a negative play time", Integer.valueOf(-60000), play.getPlayTime());
        assertEquals(Integer.valueOf(0), play.getTotalTime());
    }

    @Test
    public void deviceCGroupInfoNotLinked() throws Exception {
        YxcDistributionInfo info = new YxcClient(new RecordingTransport()
                .answer("dist/getDistributionInfo", YxcFixtures.read("captured/c_distInfo_none.json"))).getDistributionInfo();

        assertEquals(YxcDistributionInfo.Role.NONE, info.getRole());
        assertTrue("the real answer has the all-zero group id", info.isNullGroup());
        assertFalse(info.hasMcSurround());
    }
}
