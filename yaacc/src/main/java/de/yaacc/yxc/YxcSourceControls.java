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

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Which controls the device's own source offers right now (docs/tech.md, section 12). A control is offered only if the
 * source says it can do it: the bits of the play info's {@code attribute} (bit 0 play, 1 stop, 2 pause, 3 previous,
 * 4 next; a reading of captured answers: a net radio, which has only play and stop, accepts the other calls with
 * {@code response_code} 0 and does nothing), the lists {@code repeat_available} and {@code shuffle_available}, and for the
 * tuner the band. Pure.
 */
public final class YxcSourceControls {

    public enum Control {
        PLAY, PAUSE, STOP, PREVIOUS, NEXT, REPEAT, SHUFFLE, SEEK_DOWN, SEEK_UP, SERVICE_PREVIOUS, SERVICE_NEXT,
        /** Switch the tuner to the FM band (offered while it is on DAB and the device has both). */
        /** Step the FM frequency down by one step (a short tap of the seek button). */
        STEP_DOWN,
        /** Step the FM frequency up by one step (a short tap of the seek button). */
        STEP_UP,
        /** Stop an FM scan that is running. */
        CANCEL_SCAN,
        BAND_FM,
        /** Switch the tuner to the DAB band (offered while it is on FM and the device has both). */
        BAND_DAB
    }

    static final long BIT_PLAY = 1L;
    static final long BIT_STOP = 1L << 1;
    static final long BIT_PAUSE = 1L << 2;
    static final long BIT_PREVIOUS = 1L << 3;
    static final long BIT_NEXT = 1L << 4;

    private YxcSourceControls() {
    }

    /**
     * Whether the device plays a source of its own on its main zone: a network source whose play info is the zone's
     * input, or the tuner (with its play info). The link input and standby are not.
     */
    public static boolean ownSourceActive(YxcDeviceState state) {
        return !available(state).isEmpty() || isNetworkSource(state);
    }

    /**
     * Whether the row's play / pause / stop buttons work on the device's own source: the device plays one and yaacc has no
     * player on it that plays or is paused (a paused yaacc track keeps the buttons for the UPnP transport, otherwise
     * a press would leave yaacc's queue and the device out of step).
     */
    public static boolean rowMapsToOwnSource(YxcDeviceState state, boolean yaaccActive) {
        return !yaaccActive && ownSourceActive(state);
    }

    /**
     * @return the controls to offer on the row: none while yaacc plays or has paused a track on the device (the zone input
     * is then the server input, which counts as a network source, and the controls would act on the device's own queue
     * instead of yaacc's); otherwise those of {@link #available(YxcDeviceState)}
     */
    public static Set<Control> available(YxcDeviceState state, boolean yaaccActive) {
        return yaaccActive ? Collections.<Control>emptySet() : available(state);
    }

    /** @return the controls to offer, empty when the device is off, a client, or plays nothing of its own */
    public static Set<Control> available(YxcDeviceState state) {
        if (state == null || !state.isReachable() || !state.isPowerOn() || state.isLinkInput() || state.getInput() == null) {
            return Collections.emptySet();
        }
        if (YxcNowPlaying.TUNER_INPUT.equalsIgnoreCase(state.getInput())) {
            return tuner(state.getTuner(), state.getCapabilities());
        }
        if (!isNetworkSource(state)) {
            return Collections.emptySet();
        }
        YxcPlayInfo info = state.getPlayInfo();
        Set<Control> result = EnumSet.noneOf(Control.class);
        Long attribute = info.getAttribute();
        if (attribute == null) {
            // no bit field: the three that every source has
            result.add(Control.PLAY);
            result.add(Control.PAUSE);
            result.add(Control.STOP);
        } else {
            addIf(result, attribute, BIT_PLAY, Control.PLAY);
            addIf(result, attribute, BIT_STOP, Control.STOP);
            addIf(result, attribute, BIT_PAUSE, Control.PAUSE);
            addIf(result, attribute, BIT_PREVIOUS, Control.PREVIOUS);
            addIf(result, attribute, BIT_NEXT, Control.NEXT);
        }
        if (!info.getRepeatAvailable().isEmpty()) {
            result.add(Control.REPEAT);
        }
        if (!info.getShuffleAvailable().isEmpty()) {
            result.add(Control.SHUFFLE);
        }
        return result;
    }

    private static boolean isNetworkSource(YxcDeviceState state) {
        if (state == null || !state.isReachable() || !state.isPowerOn() || state.isLinkInput() || state.getInput() == null
                || YxcNowPlaying.TUNER_INPUT.equalsIgnoreCase(state.getInput())) {
            return false;
        }
        YxcPlayInfo info = state.getPlayInfo();
        return info != null && state.getInput().equalsIgnoreCase(info.getInput());
    }

    private static Set<Control> tuner(YxcTunerInfo info, YxcSourceCapabilities caps) {
        if (info == null) {
            return Collections.emptySet();
        }
        Set<Control> result = EnumSet.noneOf(Control.class);
        if (info.isDab()) {
            result.add(Control.SERVICE_PREVIOUS);
            result.add(Control.SERVICE_NEXT);
            if (caps != null && caps.hasFm()) {
                result.add(Control.BAND_FM);
            }
        } else if (info.isFm()) {
            result.add(Control.STEP_DOWN);
            result.add(Control.STEP_UP);
            result.add(Control.SEEK_DOWN);
            result.add(Control.SEEK_UP);
            if (info.isScanning()) {
                result.add(Control.CANCEL_SCAN);
            }
            if (caps != null && caps.hasDab()) {
                result.add(Control.BAND_DAB);
            }
        }
        return result;
    }

    private static void addIf(Set<Control> result, long attribute, long bit, Control control) {
        if ((attribute & bit) != 0L) {
            result.add(control);
        }
    }

    /**
     * The repeat mode after {@code current} in the list the source offers (off, one, all, as listed), {@code null} when the
     * list is empty. An unknown current mode starts at the first one.
     */
    public static String nextRepeat(YxcPlayInfo info) {
        return next(info == null ? null : info.getRepeat(), info == null ? null : info.getRepeatAvailable());
    }

    /** The shuffle mode after the current one in the list the source offers, {@code null} when the list is empty. */
    public static String nextShuffle(YxcPlayInfo info) {
        return next(info == null ? null : info.getShuffle(), info == null ? null : info.getShuffleAvailable());
    }

    private static String next(String current, List<String> offered) {
        if (offered == null || offered.isEmpty()) {
            return null;
        }
        int at = current == null ? -1 : offered.indexOf(current);
        return offered.get((at + 1) % offered.size());
    }
}
