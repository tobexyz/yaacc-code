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
package de.yaacc.yxc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static de.yaacc.yxc.YxcSourceControls.Control.BAND_DAB;
import static de.yaacc.yxc.YxcSourceControls.Control.CANCEL_SCAN;
import static de.yaacc.yxc.YxcSourceControls.Control.STEP_DOWN;
import static de.yaacc.yxc.YxcSourceControls.Control.STEP_UP;
import static de.yaacc.yxc.YxcSourceControls.Control.BAND_FM;
import static de.yaacc.yxc.YxcSourceControls.Control.NEXT;
import static de.yaacc.yxc.YxcSourceControls.Control.PAUSE;
import static de.yaacc.yxc.YxcSourceControls.Control.PLAY;
import static de.yaacc.yxc.YxcSourceControls.Control.PREVIOUS;
import static de.yaacc.yxc.YxcSourceControls.Control.REPEAT;
import static de.yaacc.yxc.YxcSourceControls.Control.SEEK_DOWN;
import static de.yaacc.yxc.YxcSourceControls.Control.SEEK_UP;
import static de.yaacc.yxc.YxcSourceControls.Control.SERVICE_NEXT;
import static de.yaacc.yxc.YxcSourceControls.Control.SERVICE_PREVIOUS;
import static de.yaacc.yxc.YxcSourceControls.Control.SHUFFLE;
import static de.yaacc.yxc.YxcSourceControls.Control.STOP;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/** Which controls a source offers, from the real play infos (docs/tech.md, section 12). */
public class YxcSourceControlsTest {

    static String status(String input, String power) {
        return "{\"response_code\":0,\"power\":\"" + power + "\",\"volume\":28,\"mute\":false,\"max_volume\":60,"
                + "\"input\":\"" + input + "\",\"input_text\":\"x\"}";
    }

    static YxcDeviceState net(String input, String power, String playInfoFixture) throws Exception {
        YxcClient client = new YxcClient(new RecordingTransport()
                .answer("main/getStatus", status(input, power))
                .answer("netusb/getPlayInfo", YxcFixtures.read("captured/" + playInfoFixture + ".json")));
        return YxcDeviceState.unknown().withZone(client.getZoneStatus("main")).withPlayInfo(client.getPlayInfo());
    }

    static YxcDeviceState tuner(String tunerFixture) throws Exception {
        YxcClient client = new YxcClient(new RecordingTransport()
                .answer("main/getStatus", status("tuner", "on"))
                .answer("tuner/getPlayInfo", YxcFixtures.read("captured/" + tunerFixture + ".json")));
        return YxcDeviceState.unknown().withZone(client.getZoneStatus("main")).withTunerInfo(client.getTunerPlayInfo());
    }

    private static Set<YxcSourceControls.Control> set(YxcSourceControls.Control... controls) {
        return controls.length == 0 ? EnumSet.noneOf(YxcSourceControls.Control.class) : EnumSet.copyOf(Arrays.asList(controls));
    }

    @Test
    public void aNetRadioOffersOnlyPlayAndStop() throws Exception {
        // attribute 16785411: bits 0 and 1; pause, next and previous are accepted by the device and do nothing
        assertEquals(set(PLAY, STOP), YxcSourceControls.available(net("net_radio", "on", "c_playInfo_net_radio")));
    }

    @Test
    public void aStreamingServiceOffersPauseSkipAndRepeatButNoShuffle() throws Exception {
        // c_playInfo: attribute 16777503 (bits 0-4, 8), repeat off/one/all, no shuffle options
        assertEquals(set(PLAY, STOP, PAUSE, PREVIOUS, NEXT, REPEAT),
                YxcSourceControls.available(net("spotify", "on", "c_playInfo")));
    }

    @Test
    public void aServerSourceOffersRepeatAndShuffleToo() throws Exception {
        assertTrue(YxcSourceControls.available(net("server", "on", "a_playInfo")).containsAll(set(REPEAT, SHUFFLE, NEXT)));
    }

    @Test
    public void nothingWhenOffLinkedOrTheInputIsAnotherSource() throws Exception {
        assertEquals(set(), YxcSourceControls.available(net("net_radio", "standby", "c_playInfo_net_radio")));
        assertEquals(set(), YxcSourceControls.available(net("mc_link", "on", "c_playInfo_net_radio")));
        // the play info of the network source is old while hdmi is selected
        assertEquals(set(), YxcSourceControls.available(net("hdmi1", "on", "c_playInfo_net_radio")));
        assertEquals(set(), YxcSourceControls.available(YxcDeviceState.unknown()));
        assertEquals(set(), YxcSourceControls.available(null));
    }

    @Test
    public void theFmTunerOffersSeekTheDabTunerTheServices() throws Exception {
        assertEquals(set(STEP_DOWN, STEP_UP, SEEK_DOWN, SEEK_UP), YxcSourceControls.available(tuner("c_tunerPlayInfo_fm")));
        assertEquals(set(SERVICE_PREVIOUS, SERVICE_NEXT), YxcSourceControls.available(tuner("c_tunerPlayInfo_dab")));
    }

    @Test
    public void theTunerInfoOfAnotherInputOffersNothing() throws Exception {
        YxcClient client = new YxcClient(new RecordingTransport()
                .answer("main/getStatus", status("net_radio", "on"))
                .answer("tuner/getPlayInfo", YxcFixtures.read("captured/c_tunerPlayInfo_fm.json")));
        YxcDeviceState state = YxcDeviceState.unknown().withZone(client.getZoneStatus("main"))
                .withTunerInfo(client.getTunerPlayInfo());
        assertEquals(set(), YxcSourceControls.available(state));
    }

    @Test
    public void ownSourceActiveIsTrueForANetworkSourceThatIsStoppedToo() throws Exception {
        assertTrue(YxcSourceControls.ownSourceActive(net("net_radio", "on", "c_playInfo_net_radio_stopped")));
        assertTrue(YxcSourceControls.ownSourceActive(tuner("c_tunerPlayInfo_fm")));
        assertFalse(YxcSourceControls.ownSourceActive(net("hdmi1", "on", "c_playInfo_net_radio")));
        assertFalse(YxcSourceControls.ownSourceActive(net("net_radio", "standby", "c_playInfo_net_radio")));
    }

    @Test
    public void whileYaaccPlaysOrHasPausedATrackTheRowStaysWithTheUpnpTransportAndNoSourceIconsShow() throws Exception {
        YxcDeviceState radio = net("net_radio", "on", "c_playInfo_net_radio");
        YxcDeviceState tuner = tuner("c_tunerPlayInfo_fm");
        // yaacc has nothing on the device: the row's buttons and the icons work on the device's own source
        assertTrue(YxcSourceControls.rowMapsToOwnSource(radio, false));
        assertTrue(YxcSourceControls.rowMapsToOwnSource(tuner, false));
        assertEquals(YxcSourceControls.available(radio), YxcSourceControls.available(radio, false));
        // yaacc plays or has paused a track (the zone input is the server input then, a network source as well)
        assertFalse(YxcSourceControls.rowMapsToOwnSource(radio, true));
        assertFalse(YxcSourceControls.rowMapsToOwnSource(tuner, true));
        assertEquals(set(), YxcSourceControls.available(radio, true));
        assertEquals(set(), YxcSourceControls.available(tuner, true));
        // a device that plays nothing of its own is never mapped
        assertFalse(YxcSourceControls.rowMapsToOwnSource(net("hdmi1", "on", "c_playInfo_net_radio"), false));
    }

    @Test
    public void repeatAndShuffleStepThroughWhatTheSourceOffers() throws Exception {
        YxcPlayInfo info = net("spotify", "on", "c_playInfo").getPlayInfo();
        // c_playInfo: repeat is "all" in the capture, offered off / one / all
        assertEquals("all is followed by off", "off", YxcSourceControls.nextRepeat(info));
        assertNull("no shuffle options", YxcSourceControls.nextShuffle(info));
        assertNull(YxcSourceControls.nextRepeat(null));
    }

    @Test
    public void theRowChangesWhenTheControlsChange() throws Exception {
        YxcDeviceState radio = net("net_radio", "on", "c_playInfo_net_radio");
        YxcDeviceState streaming = net("net_radio", "on", "c_playInfo_net_radio");
        assertTrue(radio.sameForRow(streaming));
        String richer = YxcFixtures.read("captured/c_playInfo_net_radio.json").replace("16785411", "16777503");
        YxcDeviceState other = radio.withPlayInfo(new YxcClient(new RecordingTransport().answer("netusb/getPlayInfo", richer)).getPlayInfo());
        assertFalse("pause, previous and next appeared", radio.sameForRow(other));
    }

    private static YxcSourceCapabilities caps(String featuresFixture) throws Exception {
        return new YxcClient(new RecordingTransport().answer("system/getFeatures",
                YxcFixtures.read("captured/" + featuresFixture + ".json"))).getFeatures().getSourceCapabilities();
    }

    @Test
    public void theCapabilitiesComeFromTheFeaturesOfTheDevice() throws Exception {
        YxcSourceCapabilities c = caps("c_features");
        assertTrue(c.hasFm());
        assertTrue(c.hasDab());
        assertEquals(40, c.getTunerPresets());
        assertEquals(40, c.getNetPresets());
        assertTrue(c.hasRecent());
        YxcSourceCapabilities b = caps("b_features");
        assertFalse("a device without a tuner block", b.hasFm() || b.hasDab());
        assertEquals(0, b.getTunerPresets());
        assertEquals(40, b.getNetPresets());
        assertEquals(YxcSourceCapabilities.none(), YxcSourceCapabilities.none());
    }

    @Test
    public void theBandIsOfferedOnlyForTheOtherBandThatTheDeviceHas() throws Exception {
        YxcSourceCapabilities both = caps("c_features");
        assertEquals(set(STEP_DOWN, STEP_UP, SEEK_DOWN, SEEK_UP, BAND_DAB),
                YxcSourceControls.available(tuner("c_tunerPlayInfo_fm").withCapabilities(both)));
        assertEquals(set(SERVICE_PREVIOUS, SERVICE_NEXT, BAND_FM),
                YxcSourceControls.available(tuner("c_tunerPlayInfo_dab").withCapabilities(both)));
        // the capabilities are not read yet: no band control
        assertEquals(set(STEP_DOWN, STEP_UP, SEEK_DOWN, SEEK_UP), YxcSourceControls.available(tuner("c_tunerPlayInfo_fm")));
        // a device with only one band has nothing to switch to
        assertEquals(set(STEP_DOWN, STEP_UP, SEEK_DOWN, SEEK_UP),
                YxcSourceControls.available(tuner("c_tunerPlayInfo_fm").withCapabilities(caps("b_features"))));
    }

    @Test
    public void whileTheTunerScansTheScanCanBeStopped() throws Exception {
        assertTrue(YxcSourceControls.available(tuner("c_tunerPlayInfo_fm_scanning")).contains(CANCEL_SCAN));
        assertFalse(YxcSourceControls.available(tuner("c_tunerPlayInfo_fm")).contains(CANCEL_SCAN));
    }
}
