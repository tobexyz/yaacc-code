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
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The settings of a zone, built from the captured features and statuses of three real devices (a, b, c). */
public class YxcSettingsTest {

    private static YxcZoneFeatures features(String device) throws Exception {
        return new YxcClient(new RecordingTransport().answer("system/getFeatures",
                YxcFixtures.read("captured/" + device + "_features.json"))).getFeatures().getZone("main");
    }

    private static YxcZoneStatus status(String file) throws Exception {
        return new YxcClient(new RecordingTransport().answer("main/getStatus",
                YxcFixtures.read("captured/" + file + ".json"))).getZoneStatus("main");
    }

    private static YxcNameText names() throws Exception {
        return new YxcClient(new RecordingTransport().answer("system/getNameText", YxcFixtures.read("nameText.json"))).getNameText();
    }

    private static List<String> values(YxcSettings.Item item) {
        List<String> result = new ArrayList<>();
        for (YxcSettings.Option option : item.getOptions()) {
            result.add(option.getValue());
        }
        return result;
    }

    @Test
    public void aDeviceWithoutSoundProgramsGetsNoSoundProgramItem() throws Exception {
        List<YxcSettings.Item> items = YxcSettings.build(features("a"), status("a_status_unlinked"), YxcNameText.empty());

        assertNotNull(YxcSettings.find(items, YxcSettings.Id.POWER));
        assertNotNull(YxcSettings.find(items, YxcSettings.Id.INPUT));
        assertNull("device a lists no sound programs", YxcSettings.find(items, YxcSettings.Id.SOUND_PROGRAM));
    }

    @Test
    public void thePowerSwitchShowsThePowerNow() throws Exception {
        YxcSettings.Item standby = YxcSettings.find(
                YxcSettings.build(features("a"), status("a_status_unlinked"), YxcNameText.empty()), YxcSettings.Id.POWER);
        YxcSettings.Item on = YxcSettings.find(
                YxcSettings.build(features("c"), status("c_status_linked_client"), YxcNameText.empty()), YxcSettings.Id.POWER);

        assertEquals(YxcSettings.Kind.SWITCH, standby.getKind());
        assertFalse(standby.isOn());
        assertTrue(on.isOn());
    }

    @Test
    public void theInputsAreWhatTheDeviceListsWithoutTheGroupInput() throws Exception {
        YxcSettings.Item input = YxcSettings.find(
                YxcSettings.build(features("c"), status("c_status_unlinked"), YxcNameText.empty()), YxcSettings.Id.INPUT);

        assertEquals(YxcSettings.Kind.CHOICE, input.getKind());
        assertTrue(values(input).containsAll(java.util.Arrays.asList("tuner", "cd", "aux", "usb", "bluetooth", "net_radio")));
        assertFalse("the group input is set by linking", values(input).contains("mc_link"));
        assertTrue("the order of the device", values(input).indexOf("napster") < values(input).indexOf("aux"));
    }

    @Test
    public void inputsWithoutADeviceNameAreMadeReadable() throws Exception {
        YxcSettings.Item input = YxcSettings.find(
                YxcSettings.build(features("c"), status("c_status_unlinked"), YxcNameText.empty()), YxcSettings.Id.INPUT);

        for (YxcSettings.Option option : input.getOptions()) {
            if (option.getValue().equals("net_radio")) {
                assertEquals("Net radio", option.getLabel());
            }
        }
        assertEquals("Tuner", input.getCurrentLabel());
    }

    @Test
    public void theNamesOfTheDeviceWinAndAreCleaned() throws Exception {
        YxcSettings.Item input = YxcSettings.find(
                YxcSettings.build(features("c"), status("c_status_unlinked"), names()), YxcSettings.Id.INPUT);
        String tuner = null;
        String netRadio = null;
        for (YxcSettings.Option option : input.getOptions()) {
            if (option.getValue().equals("tuner")) {
                tuner = option.getLabel();
            } else if (option.getValue().equals("net_radio")) {
                netRadio = option.getLabel();
            }
        }

        assertEquals("FM Radio", tuner);
        assertEquals("the bidi mark is gone", "Net Radio", netRadio);
    }

    @Test
    public void theSoundProgramsAndTheCurrentOneComeFromTheDevice() throws Exception {
        YxcSettings.Item program = YxcSettings.find(
                YxcSettings.build(features("c"), status("c_status_linked_client"), names()), YxcSettings.Id.SOUND_PROGRAM);

        assertEquals(java.util.Arrays.asList("standard", "bass_booster"), values(program));
        assertEquals("bass_booster", program.getCurrent());
        assertEquals("Bass+", program.getCurrentLabel());
    }

    @Test
    public void nothingIsOfferedWithoutFeaturesOrStatus() throws Exception {
        assertTrue(YxcSettings.build(null, status("a_status_unlinked"), null).isEmpty());
        assertTrue(YxcSettings.build(features("a"), null, null).isEmpty());
    }

    @Test
    public void aFunctionTheZoneDoesNotListGivesNoItem() throws Exception {
        String json = YxcFixtures.read("captured/a_features.json").replace("\"power\"", "\"not_power\"");
        YxcZoneFeatures without = new YxcClient(new RecordingTransport().answer("system/getFeatures", json))
                .getFeatures().getZone("main");

        assertNull(YxcSettings.find(YxcSettings.build(without, status("a_status_unlinked"), null), YxcSettings.Id.POWER));
    }

    // ---- the writer

    private static RecordingTransport write(String device, String statusFile, RecordingTransport transport,
                                            Change action) throws Exception {
        List<YxcSettings.Item> items = YxcSettings.build(features(device), status(statusFile), YxcNameText.empty());
        action.run(new YxcSettingsWriter(new YxcClient(transport), "main"), items);
        return transport;
    }

    private interface Change {
        void run(YxcSettingsWriter writer, List<YxcSettings.Item> items) throws Exception;
    }

    @Test
    public void aChoiceOfTheDeviceIsSent() throws Exception {
        RecordingTransport transport = write("c", "c_status_unlinked", new RecordingTransport(), (writer, items) -> {
            writer.setPower(items, true);
            writer.setInput(items, "cd");
            writer.setSoundProgram(items, "bass_booster");
        });

        assertEquals("main/setPower?power=on", transport.requests.get(0).path);
        assertEquals("main/setInput?input=cd&mode=", transport.requests.get(1).path);
        assertEquals("main/setSoundProgram?program=bass_booster", transport.requests.get(2).path);
    }

    @Test
    public void aValueTheDeviceDoesNotOfferIsRefusedBeforeAnythingIsSent() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        List<YxcSettings.Item> items = YxcSettings.build(features("c"), status("c_status_unlinked"), YxcNameText.empty());
        YxcSettingsWriter writer = new YxcSettingsWriter(new YxcClient(transport), "main");

        for (String bad : new String[]{"hdmi1", "mc_link", "", "../x", "cd&volume=99"}) {
            try {
                writer.setInput(items, bad);
                fail("accepted input " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        try {
            writer.setSoundProgram(items, "surround_party");
            fail("accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void aSoundProgramIsRefusedOnADeviceThatListsNone() throws Exception {
        List<YxcSettings.Item> items = YxcSettings.build(features("a"), status("a_status_unlinked"), YxcNameText.empty());
        YxcSettingsWriter writer = new YxcSettingsWriter(new YxcClient(new RecordingTransport()), "main");

        try {
            writer.setSoundProgram(items, "standard");
            fail("accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ---- step 2: sleep timer and equalizer

    private static YxcZoneFeatures featuresWith(String device, String from, String to) throws Exception {
        String json = YxcFixtures.read("captured/" + device + "_features.json");
        assertTrue("the fixture has " + from, json.contains(from));
        return new YxcClient(new RecordingTransport().answer("system/getFeatures", json.replace(from, to))).getFeatures().getZone("main");
    }

    @Test
    public void theSleepTimerOffersTheFixedValuesAndShowsTheCurrentOne() throws Exception {
        YxcSettings.Item sleep = YxcSettings.find(
                YxcSettings.build(features("a"), status("a_status_unlinked"), YxcNameText.empty()), YxcSettings.Id.SLEEP);

        assertEquals(java.util.Arrays.asList("0", "30", "60", "90", "120"), values(sleep));
        assertEquals("0", sleep.getCurrent());
    }

    @Test
    public void theEqualizerBandsComeFromTheRangeOfTheDevice() throws Exception {
        List<YxcSettings.Item> items = YxcSettings.build(features("a"), status("a_status_unlinked"), YxcNameText.empty());

        for (YxcSettings.Id id : new YxcSettings.Id[]{YxcSettings.Id.EQUALIZER_LOW, YxcSettings.Id.EQUALIZER_MID, YxcSettings.Id.EQUALIZER_HIGH}) {
            YxcSettings.Item band = YxcSettings.find(items, id);
            assertEquals(YxcSettings.Kind.NUMBER, band.getKind());
            assertEquals(-10, band.getMin());
            assertEquals(10, band.getMax());
            assertEquals(1, band.getStep());
            assertEquals(0, band.getNumber());
            assertTrue(band.isEnabled());
        }
        assertNull("only the manual mode: nothing to choose", YxcSettings.find(items, YxcSettings.Id.EQUALIZER_MODE));
    }

    @Test
    public void aModeRowIsOfferedOnlyWhereThereIsSomethingToChoose() throws Exception {
        List<YxcSettings.Item> items = YxcSettings.build(
                featuresWith("a", "\"manual\"", "\"manual\", \"flat\""), status("a_status_unlinked"), YxcNameText.empty());

        assertEquals(java.util.Arrays.asList("manual", "flat"), values(YxcSettings.find(items, YxcSettings.Id.EQUALIZER_MODE)));
    }

    @Test
    public void theBandsAreDisabledWhileTheModeIsNotManual() throws Exception {
        String json = YxcFixtures.read("captured/a_status_unlinked.json").replace("\"mode\": \"manual\"", "\"mode\": \"flat\"");
        YxcZoneStatus flat = new YxcClient(new RecordingTransport().answer("main/getStatus", json)).getZoneStatus("main");
        List<YxcSettings.Item> items = YxcSettings.build(features("a"), flat, YxcNameText.empty());

        assertFalse(YxcSettings.find(items, YxcSettings.Id.EQUALIZER_LOW).isEnabled());
        try {
            new YxcSettingsWriter(new YxcClient(new RecordingTransport()), "main").setEqualizerBand(items, YxcSettings.Id.EQUALIZER_LOW, 2);
            fail("accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void aBandValueMustBeInsideTheRangeAndAStepFromItsMinimum() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        List<YxcSettings.Item> items = YxcSettings.build(
                featuresWith("a", "\"min\": -10,\n          \"max\": 10,\n          \"step\": 1\n        }\n      ]",
                        "\"min\": -9,\n          \"max\": 11,\n          \"step\": 2\n        }\n      ]"),
                status("a_status_unlinked"), YxcNameText.empty());
        YxcSettingsWriter writer = new YxcSettingsWriter(new YxcClient(transport), "main");

        writer.setEqualizerBand(items, YxcSettings.Id.EQUALIZER_MID, -9);   // the minimum
        writer.setEqualizerBand(items, YxcSettings.Id.EQUALIZER_MID, 3);    // (3 - -9) % 2 == 0
        int refused = 0;
        for (int bad : new int[]{-10, 2, 12, 100, -11}) { // wrong step or outside
            try {
                writer.setEqualizerBand(items, YxcSettings.Id.EQUALIZER_MID, bad);
            } catch (IllegalArgumentException expected) {
                refused++;
            }
        }

        assertEquals(5, refused);
        assertEquals(2, transport.requests.size());
        assertEquals("main/setEqualizer?mid=3", transport.requests.get(1).path);
    }

    @Test
    public void theSleepTimerAcceptsOnlyItsFixedValues() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        List<YxcSettings.Item> items = YxcSettings.build(features("a"), status("a_status_unlinked"), YxcNameText.empty());
        YxcSettingsWriter writer = new YxcSettingsWriter(new YxcClient(transport), "main");

        writer.setSleep(items, 90);
        try {
            writer.setSleep(items, 45);
            fail("accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        assertEquals(1, transport.requests.size());
        assertEquals("main/setSleep?sleep=90", transport.requests.get(0).path);
    }

    // ---- step 3: link settings

    @Test
    public void theLinkSettingsAreOnlyThoseTheDeviceLists() throws Exception {
        List<YxcSettings.Item> a = YxcSettings.build(features("a"), status("a_status_unlinked"), YxcNameText.empty());
        List<YxcSettings.Item> b = YxcSettings.build(features("b"), status("b_status_unlinked"), YxcNameText.empty());

        assertEquals(java.util.Arrays.asList("speed", "standard", "stability"), values(YxcSettings.find(a, YxcSettings.Id.LINK_CONTROL)));
        assertEquals("stability", YxcSettings.find(a, YxcSettings.Id.LINK_CONTROL).getCurrent());
        assertEquals("audio_sync_on", YxcSettings.find(a, YxcSettings.Id.LINK_AUDIO_DELAY).getCurrent());
        assertEquals("Audio sync on", YxcSettings.find(a, YxcSettings.Id.LINK_AUDIO_DELAY).getCurrentLabel());
        assertNotNull(YxcSettings.find(a, YxcSettings.Id.LINK_AUDIO_QUALITY));
        assertNull("device b has no audio quality", YxcSettings.find(b, YxcSettings.Id.LINK_AUDIO_QUALITY));
        assertNotNull(YxcSettings.find(b, YxcSettings.Id.LINK_AUDIO_DELAY));
    }

    @Test
    public void theLinkSettingsAreSentAndCheckedAgainstTheOptions() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        List<YxcSettings.Item> items = YxcSettings.build(features("a"), status("a_status_unlinked"), YxcNameText.empty());
        YxcSettingsWriter writer = new YxcSettingsWriter(new YxcClient(transport), "main");

        writer.setLinkControl(items, "speed");
        writer.setLinkAudioDelay(items, "audio_sync_off");
        writer.setLinkAudioQuality(items, "uncompressed");
        int refused = 0;
        try { writer.setLinkControl(items, "turbo"); } catch (IllegalArgumentException e) { refused++; }
        try { writer.setLinkAudioDelay(items, "lip_sync"); } catch (IllegalArgumentException e) { refused++; }
        try { writer.setLinkAudioQuality(items, "x&y=1"); } catch (IllegalArgumentException e) { refused++; }

        assertEquals(3, refused);
        assertEquals("main/setLinkControl?control=speed", transport.requests.get(0).path);
        assertEquals("main/setLinkAudioDelay?delay=audio_sync_off", transport.requests.get(1).path);
        assertEquals("main/setLinkAudioQuality?mode=uncompressed", transport.requests.get(2).path);
        assertEquals(3, transport.requests.size());
    }

    @Test
    public void aLinkQualityIsRefusedOnADeviceThatHasNone() throws Exception {
        List<YxcSettings.Item> items = YxcSettings.build(features("b"), status("b_status_unlinked"), YxcNameText.empty());

        try {
            new YxcSettingsWriter(new YxcClient(new RecordingTransport()), "main").setLinkAudioQuality(items, "compressed");
            fail("accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ---- edge cases of the model

    @Test
    public void anEqualizerListedWithoutARangeOffersNoBands() throws Exception {
        String json = YxcFixtures.read("captured/a_features.json").replace("\"id\": \"equalizer\",\n          \"min\"", "\"id\": \"other\",\n          \"min\"");
        YxcZoneFeatures noRange = new YxcClient(new RecordingTransport().answer("system/getFeatures", json)).getFeatures().getZone("main");

        List<YxcSettings.Item> items = YxcSettings.build(noRange, status("a_status_unlinked"), YxcNameText.empty());

        assertNull(YxcSettings.find(items, YxcSettings.Id.EQUALIZER_LOW));
        assertNotNull("the rest is still offered", YxcSettings.find(items, YxcSettings.Id.POWER));
    }

    @Test
    public void aStatusWithoutEqualizerSleepOrLinkValuesStillBuildsTheChoices() throws Exception {
        YxcZoneStatus bare = new YxcClient(new RecordingTransport().answer("main/getStatus",
                "{\"response_code\":0,\"power\":\"on\",\"input\":\"tuner\"}")).getZoneStatus("main");

        List<YxcSettings.Item> items = YxcSettings.build(features("a"), bare, YxcNameText.empty());

        assertNull("no band values: no sliders", YxcSettings.find(items, YxcSettings.Id.EQUALIZER_LOW));
        assertNull(YxcSettings.find(items, YxcSettings.Id.SLEEP).getCurrent());
        assertNull(YxcSettings.find(items, YxcSettings.Id.LINK_CONTROL).getCurrent());
        assertNull(YxcSettings.find(items, YxcSettings.Id.LINK_CONTROL).getCurrentLabel());
    }

    @Test
    public void aFunctionMissingFromTheListGivesNeitherSleepNorEqualizer() throws Exception {
        YxcZoneFeatures without = featuresWith("a", "\"sleep\"", "\"not_sleep\"");
        YxcZoneFeatures noEq = featuresWith("a", "        \"equalizer\",\n", "");

        assertNull(YxcSettings.find(YxcSettings.build(without, status("a_status_unlinked"), null), YxcSettings.Id.SLEEP));
        assertNull(YxcSettings.find(YxcSettings.build(noEq, status("a_status_unlinked"), null), YxcSettings.Id.EQUALIZER_LOW));
    }

    @Test
    public void aSoundProgramFunctionWithAnEmptyListGivesNoItem() throws Exception {
        YxcZoneFeatures empty = featuresWith("c", "\"sound_program_list\": [\n        \"standard\",\n        \"bass_booster\"\n      ]", "\"sound_program_list\": []");

        assertNull(YxcSettings.find(YxcSettings.build(empty, status("c_status_unlinked"), null), YxcSettings.Id.SOUND_PROGRAM));
    }

    @Test
    public void aCurrentValueThatIsNotAnOptionIsShownReadable() throws Exception {
        YxcSettings.Item input = YxcSettings.find(
                YxcSettings.build(features("c"), status("c_status_linked_client"), YxcNameText.empty()), YxcSettings.Id.INPUT);

        assertEquals("mc_link", input.getCurrent());
        assertEquals("Mc link", input.getCurrentLabel());
        assertFalse("but it is not offered", values(input).contains("mc_link"));
    }

    @Test
    public void aBandValueOutsideTheRangeIsShownAtTheEdge() throws Exception {
        String json = YxcFixtures.read("captured/a_status_unlinked.json").replace("\"low\": 0", "\"low\": 99");
        YxcZoneStatus odd = new YxcClient(new RecordingTransport().answer("main/getStatus", json)).getZoneStatus("main");

        assertEquals(10, YxcSettings.find(YxcSettings.build(features("a"), odd, null), YxcSettings.Id.EQUALIZER_LOW).getNumber());
    }

    @Test
    public void thePowerAndModeChangesAreRefusedWhereTheDeviceOffersNone() throws Exception {
        String json = YxcFixtures.read("captured/a_features.json").replace("\"power\"", "\"not_power\"");
        YxcZoneFeatures noPower = new YxcClient(new RecordingTransport().answer("system/getFeatures", json)).getFeatures().getZone("main");
        List<YxcSettings.Item> items = YxcSettings.build(noPower, status("a_status_unlinked"), null);
        YxcSettingsWriter writer = new YxcSettingsWriter(new YxcClient(new RecordingTransport()), "main");
        int refused = 0;
        try { writer.setPower(items, true); } catch (IllegalArgumentException e) { refused++; }
        try { writer.setEqualizerMode(items, "manual"); } catch (IllegalArgumentException e) { refused++; } // one mode: no row

        assertEquals(2, refused);
    }

    @Test
    public void theSleepValuesAreAReadOnlyList() {
        assertEquals(java.util.Arrays.asList(0, 30, 60, 90, 120), YxcSettings.sleepMinutes());
        YxcSettings.sleepMinutes().clear();
        assertEquals(5, YxcSettings.sleepMinutes().size());
    }

    // ---- hostile lists and ranges (security review of slice 4)

    @Test
    public void aHugeInputListIsCutAndOnlyUsableDuplicateFreeIdsAreOffered() throws Exception {
        org.json.JSONObject root = new org.json.JSONObject(YxcFixtures.read("captured/a_features.json"));
        org.json.JSONArray hostileInputs = new org.json.JSONArray();
        for (String bad : new String[]{"tuner", "tuner", "a&b=c", "", "x y"}) {
            hostileInputs.put(bad);
        }
        for (int i = 0; i < 100_000; i++) {
            hostileInputs.put("in" + i);
        }
        root.getJSONArray("zone").getJSONObject(0).put("input_list", hostileInputs);
        String json = root.toString();
        YxcZoneFeatures hostile = new YxcClient(new RecordingTransport().answer("system/getFeatures", json)).getFeatures().getZone("main");

        long start = System.nanoTime();
        YxcSettings.Item input = YxcSettings.find(YxcSettings.build(hostile, status("a_status_unlinked"), YxcNameText.empty()), YxcSettings.Id.INPUT);

        assertEquals(YxcSettings.MAX_OPTIONS, input.getOptions().size());
        assertEquals("tuner", input.getOptions().get(0).getValue());
        assertEquals("the duplicate and the unusable ids are gone", "in0", input.getOptions().get(1).getValue());
        assertTrue("built quickly", (System.nanoTime() - start) / 1_000_000L < 3000);
    }

    @Test
    public void aRangeWithMillionsOfStepsOffersNoBands() throws Exception {
        YxcZoneFeatures wide = featuresWith("a", "\"min\": -10,\n          \"max\": 10,\n          \"step\": 1",
                "\"min\": -2000000000,\n          \"max\": 2000000000,\n          \"step\": 1");

        List<YxcSettings.Item> items = YxcSettings.build(wide, status("a_status_unlinked"), YxcNameText.empty());

        assertNull(YxcSettings.find(items, YxcSettings.Id.EQUALIZER_LOW));
        assertNotNull(YxcSettings.find(items, YxcSettings.Id.POWER));
    }

    @Test
    public void aBandValueNearTheEdgesOfIntDoesNotOverflowTheStepCheck() throws Exception {
        YxcZoneFeatures wide = featuresWith("a", "\"min\": -10,\n          \"max\": 10,\n          \"step\": 1",
                "\"min\": -2147483648,\n          \"max\": 2147483647,\n          \"step\": 3000000");
        // 1431 steps: more than the slider takes, so no band item; the check itself must still not overflow
        assertNull(YxcSettings.find(YxcSettings.build(wide, status("a_status_unlinked"), null), YxcSettings.Id.EQUALIZER_MID));
    }

    @Test
    public void theInputCallRefusesAnIdThatIsNotAnId() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        YxcClient client = new YxcClient(transport);
        int refused = 0;
        try { client.setInput("main", "tuner&volume=99", ""); } catch (IllegalArgumentException e) { refused++; }
        try { client.setInput("main", new String(new char[3_000_000]).replace('\0', 'a'), ""); } catch (IllegalArgumentException e) { refused++; }
        try { client.setInput("main", "tuner", new String(new char[500]).replace('\0', 'a')); } catch (IllegalArgumentException e) { refused++; }
        client.setInput("main", "tuner", "");

        assertEquals(3, refused);
        assertEquals(1, transport.requests.size());
        assertEquals("main/setInput?input=tuner&mode=", transport.requests.get(0).path);
    }

    @Test
    public void theSliderPositionsMapToValuesWithoutOverflow() throws Exception {
        YxcZoneFeatures wide = featuresWith("a", "\"min\": -10,\n          \"max\": 10,\n          \"step\": 1",
                "\"min\": -2000000000,\n          \"max\": 2000000000,\n          \"step\": 5000000");
        YxcSettings.Item band = YxcSettings.find(YxcSettings.build(wide, status("a_status_unlinked"), null), YxcSettings.Id.EQUALIZER_LOW);

        assertEquals(800, band.getSteps());
        assertEquals(-2000000000, band.valueAt(0));
        assertEquals(2000000000, band.valueAt(800));
        assertEquals(0, band.valueAt(400));
        assertEquals("a position beyond the end stays inside", 2000000000, band.valueAt(100000));
    }
}
