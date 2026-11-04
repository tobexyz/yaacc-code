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

import org.junit.Test;

/** The now playing line, built from the answers of a real device with invented station names (docs/tech.md, section 12). */
public class YxcNowPlayingTest {

    private static String status(String input, String power) {
        return "{\"response_code\":0,\"power\":\"" + power + "\",\"sleep\":0,\"volume\":28,\"mute\":false,\"max_volume\":60,"
                + "\"input\":\"" + input + "\",\"input_text\":\"x\",\"distribution_enable\":true}";
    }

    private static YxcDeviceState state(String input, String power, String netPlayInfo, String tunerPlayInfo) throws Exception {
        RecordingTransport transport = new RecordingTransport()
                .answer("main/getStatus", status(input, power))
                .answer("netusb/getPlayInfo", netPlayInfo == null ? "{\"response_code\":0}"
                        : YxcFixtures.read("captured/" + netPlayInfo + ".json"))
                .answer("tuner/getPlayInfo", tunerPlayInfo == null ? "{\"response_code\":0}"
                        : YxcFixtures.read("captured/" + tunerPlayInfo + ".json"));
        YxcClient client = new YxcClient(transport);
        return YxcDeviceState.unknown().withZone(client.getZoneStatus("main"))
                .withPlayInfo(client.getPlayInfo()).withTunerInfo(client.getTunerPlayInfo());
    }

    @Test
    public void aNetRadioShowsItsStationAndTheStreamTitle() throws Exception {
        assertEquals("Test Radio One (City/Language) · Test Radio One - example.org/one",
                YxcNowPlaying.describe(state("net_radio", "on", "c_playInfo_net_radio", null)));
    }

    @Test
    public void aStoppedSourceShowsNothingEvenThoughItKeepsTheLastStation() throws Exception {
        assertNull(YxcNowPlaying.describe(state("net_radio", "on", "c_playInfo_net_radio_stopped", null)));
    }

    @Test
    public void nothingForAStandbyDeviceOrAnInputOfAnotherKind() throws Exception {
        assertNull(YxcNowPlaying.describe(state("net_radio", "standby", "c_playInfo_net_radio", null)));
        // the play info of the network source is old while hdmi is selected
        assertNull(YxcNowPlaying.describe(state("hdmi1", "on", "c_playInfo_net_radio", null)));
        // and while another network source is selected than the one the play info talks about
        assertNull(YxcNowPlaying.describe(state("spotify", "on", "c_playInfo_net_radio", null)));
    }

    @Test
    public void aLinkedClientShowsNothing() throws Exception {
        assertNull(YxcNowPlaying.describe(state("mc_link", "on", "c_playInfo_net_radio", "c_tunerPlayInfo_fm")));
    }

    @Test
    public void aStreamingSourceShowsArtistAndTrack() throws Exception {
        String info = "{\"response_code\":0,\"input\":\"spotify\",\"playback\":\"play\",\"artist\":\"Test Artist\","
                + "\"track\":\"Test Track\",\"album\":\"x\",\"total_time\":200,\"play_time\":3}";
        YxcDeviceState state = YxcDeviceState.unknown()
                .withZone(new YxcClient(new RecordingTransport().answer("main/getStatus", status("spotify", "on")))
                        .getZoneStatus("main"))
                .withPlayInfo(new YxcClient(new RecordingTransport().answer("netusb/getPlayInfo", info)).getPlayInfo());
        assertEquals("Test Artist – Test Track", YxcNowPlaying.describe(state));
    }

    @Test
    public void theFmTunerShowsTheFrequencyAndTheRadioText() throws Exception {
        assertEquals("FM 87.55 MHz · TEST 1 · Test Song - Test Artist",
                YxcNowPlaying.describe(state("tuner", "on", null, "c_tunerPlayInfo_fm")));
    }

    @Test
    public void aScanningTunerShowsTheMovingFrequencyWithAnEllipsis() throws Exception {
        assertEquals("FM 87.60 MHz …", YxcNowPlaying.describe(state("tuner", "on", null, "c_tunerPlayInfo_fm_scanning")));
    }

    @Test
    public void aDabStationShowsItsServiceWithoutThePaddingBlanks() throws Exception {
        String line = YxcNowPlaying.describe(state("tuner", "on", null, "c_tunerPlayInfo_dab"));
        assertEquals("TESTRADIO TWO", line);
    }

    @Test
    public void theTunerInfoOfAnotherInputIsNotShown() throws Exception {
        assertNull(YxcNowPlaying.describe(state("net_radio", "on", null, "c_tunerPlayInfo_fm")));
    }

    @Test
    public void textsFromTheDeviceAreCleanedAndCapped() throws Exception {
        String evil = new String(new char[500]).replace('\0', 'x');
        String info = "{\"response_code\":0,\"input\":\"net_radio\",\"playback\":\"play\",\"artist\":\"Name\\u0000\\n\\u202e\","
                + "\"track\":\"" + evil + "\"}";
        YxcDeviceState state = YxcDeviceState.unknown()
                .withZone(new YxcClient(new RecordingTransport().answer("main/getStatus", status("net_radio", "on")))
                        .getZoneStatus("main"))
                .withPlayInfo(new YxcClient(new RecordingTransport().answer("netusb/getPlayInfo", info)).getPlayInfo());
        String line = YxcNowPlaying.describe(state);
        assertTrue(line.startsWith("Name · x"));
        assertTrue(line.length() <= "Name".length() + 3 + YxcNames.MAX_TEXT_CHARS + 1);
        assertFalse(line.contains("\n"));
        assertFalse(line.contains("‮"));
    }

    @Test
    public void theRowOnlyChangesWhenTheLineChanges() throws Exception {
        YxcDeviceState a = state("net_radio", "on", "c_playInfo_net_radio", null);
        String moved = YxcFixtures.read("captured/c_playInfo_net_radio.json").replace("\"play_time\": 39", "\"play_time\": 99");
        YxcClient other = new YxcClient(new RecordingTransport().answer("netusb/getPlayInfo", moved));
        YxcDeviceState b = a.withPlayInfo(other.getPlayInfo());
        assertTrue("only the play time moved", a.sameForRow(b));
        String changed = YxcFixtures.read("captured/c_playInfo_net_radio.json").replace("Test Radio One - example.org/one", "Other title");
        YxcDeviceState c = a.withPlayInfo(new YxcClient(new RecordingTransport().answer("netusb/getPlayInfo", changed)).getPlayInfo());
        assertFalse(a.sameForRow(c));
    }
}
