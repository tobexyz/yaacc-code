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

/**
 * Performs one control of the device's own source. Nothing is decided on an event or on the state the row shows: the
 * zone status and the play info are read right before, the control must be offered by that fresh answer, and only then is
 * the call sent. Pure over a {@link YxcClient}; the caller runs it off the main thread.
 */
public final class YxcSourceActions {

    public enum Result {
        /** The call was sent and answered. */
        DONE,
        /** The source does not offer this control (any more): nothing was sent. */
        NOT_AVAILABLE,
        /** The device did not answer or refused the call. */
        FAILED
    }

    private YxcSourceActions() {
    }

    public static Result perform(YxcClient client, YxcSourceControls.Control control) {
        return perform(client, control, null);
    }

    /** @param caps what the device says about its sources (the tuner bands), {@code null} when not known */
    public static Result perform(YxcClient client, YxcSourceControls.Control control, YxcSourceCapabilities caps) {
        try {
            YxcZoneStatus zone = client.getZoneStatus(YxcDeviceState.MAIN_ZONE);
            YxcDeviceState state = YxcDeviceState.unknown().withZone(zone).withCapabilities(caps);
            if (YxcNowPlaying.TUNER_INPUT.equalsIgnoreCase(zone.getInput())) {
                state = state.withTunerInfo(client.getTunerPlayInfo());
            } else {
                state = state.withPlayInfo(client.getPlayInfo());
            }
            if (!YxcSourceControls.available(state).contains(control)) {
                return Result.NOT_AVAILABLE;
            }
            send(client, state, control);
            return Result.DONE;
        } catch (YxcException | RuntimeException e) {
            return Result.FAILED;
        }
    }

    private static void send(YxcClient client, YxcDeviceState state, YxcSourceControls.Control control) throws YxcException {
        switch (control) {
            case PLAY:
                client.setPlayback("play");
                break;
            case PAUSE:
                client.setPlayback("pause");
                break;
            case STOP:
                client.setPlayback("stop");
                break;
            case PREVIOUS:
                client.setPlayback("previous");
                break;
            case NEXT:
                client.setPlayback("next");
                break;
            case REPEAT:
                client.setRepeat(YxcSourceControls.nextRepeat(state.getPlayInfo()));
                break;
            case SHUFFLE:
                client.setShuffle(YxcSourceControls.nextShuffle(state.getPlayInfo()));
                break;
            case STEP_DOWN:
                client.stepFm(false);
                break;
            case STEP_UP:
                client.stepFm(true);
                break;
            case CANCEL_SCAN:
                client.cancelFmScan();
                break;
            case SEEK_DOWN:
                client.seekFm(false);
                break;
            case SEEK_UP:
                client.seekFm(true);
                break;
            case SERVICE_PREVIOUS:
                client.stepDabService(false);
                break;
            case SERVICE_NEXT:
                client.stepDabService(true);
                break;
            case BAND_FM:
                client.setTunerBand("fm");
                break;
            case BAND_DAB:
                client.setTunerBand("dab");
                break;
            default:
                throw new IllegalArgumentException("unknown control");
        }
    }
}
