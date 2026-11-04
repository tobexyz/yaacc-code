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

import java.util.List;

/** The entries a device can switch to, from the real list shapes (docs/tech.md, section 12; invented names). */
public class YxcPresetsTest {

    private static YxcClient client(String path, String fixture) {
        return new YxcClient(new RecordingTransport().answer(path, YxcFixtures.read("captured/" + fixture + ".json")));
    }

    @Test
    public void anEmptyNetPresetListHasNoEntries() throws Exception {
        assertTrue(client("netusb/getPresetInfo", "c_presetInfo_net_empty").getNetPresets().isEmpty());
    }

    @Test
    public void netPresetsAreTheNonEmptySlotsWithTheirPositionAsNumber() throws Exception {
        List<YxcPresets.Entry> list = client("netusb/getPresetInfo", "c_presetInfo_net").getNetPresets();
        assertEquals(2, list.size());
        assertEquals(1, list.get(0).getNumber());
        assertEquals("Test Radio One", list.get(0).getLabel());
        assertEquals(3, list.get(1).getNumber());
        assertEquals(YxcPresets.Kind.NET_PRESET, list.get(1).getKind());
    }

    @Test
    public void recentlyPlayedKeepsStationsAndStreamingEntriesButNotTracksOfServers() throws Exception {
        List<YxcPresets.Entry> list = client("netusb/getRecentInfo", "c_recentInfo").getRecentItems();
        assertEquals(2, list.size());
        assertEquals("Test Radio Two (City/Language)", list.get(0).getLabel());
        assertEquals("the position in the device's list, not in the filtered one", 2, list.get(1).getNumber());
        assertEquals(YxcPresets.Kind.RECENT, list.get(1).getKind());
    }

    @Test
    public void fmPresetsShowTheNameWithTheFrequencyTrimmed() throws Exception {
        List<YxcPresets.Entry> list = client("tuner/getPresetInfo?band=fm", "c_tunerPresetInfo_fm").getTunerPresets("fm");
        assertEquals(1, list.size());
        assertEquals("TEST 1", list.get(0).getLabel());
        assertEquals("87.55 MHz", list.get(0).getDetail());
        assertEquals(YxcPresets.Kind.FM_PRESET, list.get(0).getKind());
    }

    @Test
    public void dabPresetsAreTheServicesWithoutThePaddingBlanks() throws Exception {
        List<YxcPresets.Entry> list = client("tuner/getPresetInfo?band=dab", "c_tunerPresetInfo_dab").getTunerPresets("dab");
        assertEquals(5, list.size());
        assertEquals("TESTRADIO ONE", list.get(0).getLabel());
        assertNull(list.get(0).getDetail());
        assertEquals(5, list.get(4).getNumber());
    }

    @Test
    public void aSlotOfTheOtherBandIsNotAnEntryOfThisOne() throws Exception {
        String json = "{\"response_code\":0,\"preset_info\":[{\"band\":\"dab\",\"number\":5,\"text\":\"X\"},"
                + "{\"band\":\"fm\",\"number\":87550,\"text\":\"Y\"}]}";
        List<YxcPresets.Entry> fm = new YxcClient(new RecordingTransport().answer("tuner/getPresetInfo?band=fm", json))
                .getTunerPresets("fm");
        assertEquals(1, fm.size());
        assertEquals(2, fm.get(0).getNumber());
    }

    @Test
    public void namesAreCleanedCappedAndAnEntryWithoutAnyNameAndFrequencyIsDropped() throws Exception {
        String huge = new String(new char[500]).replace('\0', 'x');
        String json = "{\"response_code\":0,\"preset_info\":[{\"input\":\"net_radio\",\"text\":\"A\\u0000\\n\\u202eB\"},"
                + "{\"input\":\"net_radio\",\"text\":\"" + huge + "\"},{\"input\":\"net_radio\",\"text\":\"   \"}]}";
        List<YxcPresets.Entry> list = new YxcClient(new RecordingTransport().answer("netusb/getPresetInfo", json)).getNetPresets();
        assertEquals(2, list.size());
        assertEquals("AB", list.get(0).getLabel());
        assertTrue(list.get(1).getLabel().length() <= YxcPresets.MAX_LABEL_CHARS + 1);
        assertFalse(list.get(0).getLabel().contains("\n"));
    }

    @Test
    public void onlyTheFirstHundredSlotsAreLooked() throws Exception {
        StringBuilder json = new StringBuilder("{\"response_code\":0,\"preset_info\":[");
        for (int i = 0; i < 150; i++) {
            json.append(i == 0 ? "" : ",").append("{\"input\":\"net_radio\",\"text\":\"S").append(i).append("\"}");
        }
        json.append("]}");
        List<YxcPresets.Entry> list = new YxcClient(new RecordingTransport().answer("netusb/getPresetInfo", json.toString()))
                .getNetPresets();
        assertEquals(YxcSourceCapabilities.MAX_PRESETS, list.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aPresetNumberOutsideTheRangeIsRefusedBeforeAnythingIsSent() throws Exception {
        new YxcClient(new RecordingTransport()).recallNetPreset(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aBandThatIsNoBandIsRefused() throws Exception {
        new YxcClient(new RecordingTransport()).recallTunerPreset("am", 1);
    }
}
