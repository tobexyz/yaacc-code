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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A control is sent only after a fresh read says the source offers it. */
public class YxcSourceActionsTest {

    private static RecordingTransport device(String input, String playInfoFixture, String tunerFixture) {
        RecordingTransport transport = new RecordingTransport()
                .answer("main/getStatus", YxcSourceControlsTest.status(input, "on"));
        if (playInfoFixture != null) {
            transport.answer("netusb/getPlayInfo", YxcFixtures.read("captured/" + playInfoFixture + ".json"));
        }
        if (tunerFixture != null) {
            transport.answer("tuner/getPlayInfo", YxcFixtures.read("captured/" + tunerFixture + ".json"));
        }
        return transport;
    }

    private static List<String> sent(RecordingTransport transport) {
        List<String> result = new ArrayList<>();
        for (RecordingTransport.Request request : transport.requests) {
            if (!request.path.startsWith("main/getStatus") && !request.path.endsWith("getPlayInfo")) {
                result.add(request.path);
            }
        }
        return result;
    }

    @Test
    public void playAndStopOfANetRadioAreSent() {
        RecordingTransport transport = device("net_radio", "c_playInfo_net_radio", null);
        assertEquals(YxcSourceActions.Result.DONE,
                YxcSourceActions.perform(new YxcClient(transport), YxcSourceControls.Control.STOP));
        assertEquals(YxcSourceActions.Result.DONE,
                YxcSourceActions.perform(new YxcClient(transport), YxcSourceControls.Control.PLAY));
        assertEquals(java.util.Arrays.asList("netusb/setPlayback?playback=stop", "netusb/setPlayback?playback=play"), sent(transport));
    }

    @Test
    public void pauseNextAndRepeatOfANetRadioAreNotSentBecauseTheDeviceWouldAcceptAndIgnoreThem() {
        RecordingTransport transport = device("net_radio", "c_playInfo_net_radio", null);
        for (YxcSourceControls.Control control : new YxcSourceControls.Control[]{YxcSourceControls.Control.PAUSE,
                YxcSourceControls.Control.NEXT, YxcSourceControls.Control.PREVIOUS, YxcSourceControls.Control.REPEAT,
                YxcSourceControls.Control.SHUFFLE}) {
            assertEquals(YxcSourceActions.Result.NOT_AVAILABLE, YxcSourceActions.perform(new YxcClient(transport), control));
        }
        assertTrue(sent(transport).isEmpty());
    }

    @Test
    public void repeatStepsToTheNextOfferedMode() {
        RecordingTransport transport = device("spotify", "c_playInfo", null);
        assertEquals(YxcSourceActions.Result.DONE,
                YxcSourceActions.perform(new YxcClient(transport), YxcSourceControls.Control.REPEAT));
        assertEquals(java.util.Collections.singletonList("netusb/setRepeat?mode=off"), sent(transport));
    }

    @Test
    public void seekAndDabServiceAreSentOnlyOnTheirBand() {
        RecordingTransport fm = device("tuner", null, "c_tunerPlayInfo_fm");
        assertEquals(YxcSourceActions.Result.DONE, YxcSourceActions.perform(new YxcClient(fm), YxcSourceControls.Control.SEEK_UP));
        assertEquals(YxcSourceActions.Result.NOT_AVAILABLE,
                YxcSourceActions.perform(new YxcClient(fm), YxcSourceControls.Control.SERVICE_NEXT));
        assertEquals(java.util.Collections.singletonList("tuner/setFreq?band=fm&tuning=auto_up&num=0"), sent(fm));
        RecordingTransport dab = device("tuner", null, "c_tunerPlayInfo_dab");
        assertEquals(YxcSourceActions.Result.DONE,
                YxcSourceActions.perform(new YxcClient(dab), YxcSourceControls.Control.SERVICE_PREVIOUS));
        assertEquals(YxcSourceActions.Result.NOT_AVAILABLE, YxcSourceActions.perform(new YxcClient(dab), YxcSourceControls.Control.SEEK_DOWN));
        assertEquals(java.util.Collections.singletonList("tuner/setDabService?dir=previous"), sent(dab));
    }

    @Test
    public void theDecisionIsMadeOnTheFreshAnswerNotOnAnOldState() {
        // the device was on the tuner a moment ago, now it plays net radio: a seek is refused
        RecordingTransport transport = device("net_radio", "c_playInfo_net_radio", "c_tunerPlayInfo_fm");
        assertEquals(YxcSourceActions.Result.NOT_AVAILABLE,
                YxcSourceActions.perform(new YxcClient(transport), YxcSourceControls.Control.SEEK_UP));
        assertTrue(sent(transport).isEmpty());
    }

    @Test
    public void aDeviceThatDoesNotAnswerIsAFailureAndNothingElseHappens() {
        RecordingTransport transport = device("net_radio", "c_playInfo_net_radio", null);
        transport.failWith(new IOException("down"));
        assertEquals(YxcSourceActions.Result.FAILED,
                YxcSourceActions.perform(new YxcClient(transport), YxcSourceControls.Control.STOP));
    }

    @Test(expected = IllegalArgumentException.class)
    public void theClientRefusesAWordThatIsNoPlaybackCommand() throws Exception {
        new YxcClient(new RecordingTransport()).setPlayback("rm -rf");
    }

    @Test(expected = IllegalArgumentException.class)
    public void theClientRefusesARepeatModeThatIsNoMode() throws Exception {
        new YxcClient(new RecordingTransport()).setRepeat("sometimes");
    }

    @Test
    public void theBandIsSwitchedOnlyWhenTheDeviceHasBothAndTheOtherBandIsSelected() throws Exception {
        YxcSourceCapabilities both = new YxcClient(new RecordingTransport().answer("system/getFeatures",
                YxcFixtures.read("captured/c_features.json"))).getFeatures().getSourceCapabilities();
        RecordingTransport fm = device("tuner", null, "c_tunerPlayInfo_fm");
        assertEquals(YxcSourceActions.Result.DONE,
                YxcSourceActions.perform(new YxcClient(fm), YxcSourceControls.Control.BAND_DAB, both));
        assertEquals(YxcSourceActions.Result.NOT_AVAILABLE,
                YxcSourceActions.perform(new YxcClient(fm), YxcSourceControls.Control.BAND_FM, both));
        assertEquals(java.util.Collections.singletonList("tuner/setBand?band=dab"), sent(fm));
        assertEquals("without the capabilities nothing is sent", YxcSourceActions.Result.NOT_AVAILABLE,
                YxcSourceActions.perform(new YxcClient(fm), YxcSourceControls.Control.BAND_DAB));
    }

    @Test(expected = IllegalArgumentException.class)
    public void theClientRefusesABandThatIsNoBand() throws Exception {
        new YxcClient(new RecordingTransport()).setTunerBand("am; drop");
    }

    @Test
    public void aShortTapStepsTheFrequencyAndALongTapScans() {
        RecordingTransport fm = device("tuner", null, "c_tunerPlayInfo_fm");
        assertEquals(YxcSourceActions.Result.DONE, YxcSourceActions.perform(new YxcClient(fm), YxcSourceControls.Control.STEP_UP));
        assertEquals(YxcSourceActions.Result.DONE, YxcSourceActions.perform(new YxcClient(fm), YxcSourceControls.Control.STEP_DOWN));
        assertEquals(YxcSourceActions.Result.DONE, YxcSourceActions.perform(new YxcClient(fm), YxcSourceControls.Control.SEEK_DOWN));
        assertEquals(java.util.Arrays.asList("tuner/setFreq?band=fm&tuning=up&num=0",
                "tuner/setFreq?band=fm&tuning=down&num=0", "tuner/setFreq?band=fm&tuning=auto_down&num=0"), sent(fm));
    }

    @Test
    public void aScanIsCancelledOnlyWhileTheTunerScans() {
        RecordingTransport idle = device("tuner", null, "c_tunerPlayInfo_fm");
        assertEquals(YxcSourceActions.Result.NOT_AVAILABLE,
                YxcSourceActions.perform(new YxcClient(idle), YxcSourceControls.Control.CANCEL_SCAN));
        RecordingTransport scanning = device("tuner", null, "c_tunerPlayInfo_fm_scanning");
        assertEquals(YxcSourceActions.Result.DONE,
                YxcSourceActions.perform(new YxcClient(scanning), YxcSourceControls.Control.CANCEL_SCAN));
        assertEquals(java.util.Collections.singletonList("tuner/setFreq?band=fm&tuning=cancel&num=0"), sent(scanning));
    }

    @Test
    public void stepsAreNotOfferedOnDab() {
        RecordingTransport dab = device("tuner", null, "c_tunerPlayInfo_dab");
        assertEquals(YxcSourceActions.Result.NOT_AVAILABLE, YxcSourceActions.perform(new YxcClient(dab), YxcSourceControls.Control.STEP_UP));
        assertTrue(sent(dab).isEmpty());
    }
}
