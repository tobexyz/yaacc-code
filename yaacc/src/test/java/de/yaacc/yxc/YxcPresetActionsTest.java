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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Presets are read for what the device lists and recalled only after a fresh look at the list. */
public class YxcPresetActionsTest {

    private static YxcSourceCapabilities caps() throws Exception {
        return new YxcClient(new RecordingTransport().answer("system/getFeatures",
                YxcFixtures.read("captured/c_features.json"))).getFeatures().getSourceCapabilities();
    }

    private static RecordingTransport device(String input) {
        return new RecordingTransport()
                .answer("main/getStatus", YxcSourceControlsTest.status(input, "on"))
                .answer("netusb/getPresetInfo", YxcFixtures.read("captured/c_presetInfo_net.json"))
                .answer("netusb/getRecentInfo", YxcFixtures.read("captured/c_recentInfo.json"))
                .answer("tuner/getPresetInfo?band=fm", YxcFixtures.read("captured/c_tunerPresetInfo_fm.json"))
                .answer("tuner/getPresetInfo?band=dab", YxcFixtures.read("captured/c_tunerPresetInfo_dab.json"));
    }

    private static List<String> sent(RecordingTransport transport) {
        List<String> result = new ArrayList<>();
        for (RecordingTransport.Request request : transport.requests) {
            if (request.path.contains("recall") || request.path.contains("setInput")) {
                result.add(request.path);
            }
        }
        return result;
    }

    @Test
    public void everyListTheDeviceHasIsLoaded() throws Exception {
        YxcPresetActions.Lists lists = YxcPresetActions.load(new YxcClient(device("net_radio")), caps());
        assertEquals(2, lists.net().size());
        assertEquals(2, lists.recent().size());
        assertEquals(1, lists.fm().size());
        assertEquals(5, lists.dab().size());
        assertFalse(lists.isEmpty());
        assertFalse(lists.isFailed());
    }

    @Test
    public void nothingIsAskedWithoutCapabilities() {
        RecordingTransport transport = device("net_radio");
        YxcPresetActions.Lists lists = YxcPresetActions.load(new YxcClient(transport), null);
        assertTrue(lists.isEmpty());
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void aDeviceThatDoesNotAnswerIsAFailureNotAnEmptyList() throws Exception {
        RecordingTransport transport = device("net_radio");
        transport.failWith(new IOException("down"));
        YxcPresetActions.Lists lists = YxcPresetActions.load(new YxcClient(transport), caps());
        assertTrue(lists.isEmpty());
        assertTrue(lists.isFailed());
    }

    @Test
    public void aRecentEntryIsRecalledByItsPositionInTheDevicesList() throws Exception {
        RecordingTransport transport = device("tuner");
        YxcPresets.Entry entry = new YxcClient(transport).getRecentItems().get(1);
        assertEquals(YxcPresetActions.Result.DONE, YxcPresetActions.recall(new YxcClient(transport), entry));
        assertEquals(Collections.singletonList("netusb/recallRecentItem?zone=main&num=2"), sent(transport));
    }

    @Test
    public void aNetPresetIsRecalledByItsNumber() throws Exception {
        RecordingTransport transport = device("net_radio");
        YxcPresets.Entry entry = new YxcClient(transport).getNetPresets().get(1);
        assertEquals(YxcPresetActions.Result.DONE, YxcPresetActions.recall(new YxcClient(transport), entry));
        assertEquals(Collections.singletonList("netusb/recallPreset?zone=main&num=3"), sent(transport));
    }

    @Test
    public void aTunerPresetSwitchesTheZoneToTheTunerFirstWhenItIsOnAnotherInput() throws Exception {
        RecordingTransport transport = device("net_radio");
        YxcPresets.Entry entry = new YxcClient(transport).getTunerPresets("dab").get(2);
        assertEquals(YxcPresetActions.Result.DONE, YxcPresetActions.recall(new YxcClient(transport), entry));
        assertEquals(Arrays.asList("main/setInput?input=tuner&mode=", "tuner/recallPreset?zone=main&band=dab&num=3"), sent(transport));
    }

    @Test
    public void aTunerPresetOnTheTunerInputNeedsNoInputChange() throws Exception {
        RecordingTransport transport = device("tuner");
        YxcPresets.Entry entry = new YxcClient(transport).getTunerPresets("fm").get(0);
        assertEquals(YxcPresetActions.Result.DONE, YxcPresetActions.recall(new YxcClient(transport), entry));
        assertEquals(Collections.singletonList("tuner/recallPreset?zone=main&band=fm&num=1"), sent(transport));
    }

    @Test
    public void anEntryThatChangedOnTheDeviceMeanwhileIsNotRecalled() throws Exception {
        RecordingTransport transport = device("net_radio");
        YxcPresets.Entry shown = new YxcClient(transport).getNetPresets().get(0);
        // the user changed the presets with the manufacturer's app
        transport.answer("netusb/getPresetInfo", YxcFixtures.read("captured/c_presetInfo_net_empty.json"));
        assertEquals(YxcPresetActions.Result.NOT_AVAILABLE, YxcPresetActions.recall(new YxcClient(transport), shown));
        assertTrue(sent(transport).isEmpty());
    }

    @Test
    public void aDeviceThatFailsOnTheRecallIsAFailure() throws Exception {
        RecordingTransport transport = device("net_radio");
        YxcPresets.Entry entry = new YxcClient(transport).getNetPresets().get(0);
        transport.failWith(new IOException("down"));
        assertEquals(YxcPresetActions.Result.FAILED, YxcPresetActions.recall(new YxcClient(transport), entry));
    }
}
