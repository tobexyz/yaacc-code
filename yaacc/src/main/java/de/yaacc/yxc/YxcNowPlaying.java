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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "now playing" line of a receiver row for a device that plays a source of its own: the station and stream title of
 * a net radio, artist and track of a streaming or server source, the frequency and radio text of the tuner, the
 * service of a DAB station. Pure; all texts come from the device and are cleaned and capped.
 * <p>
 * What a device answers for the play info of a source that is <em>not</em> selected is old (docs/tech.md, section 12),
 * so the zone input decides: the network play info counts only if its own {@code input} is the zone input, the tuner
 * info only while the zone input is the tuner.
 */
public final class YxcNowPlaying {

    static final String TUNER_INPUT = "tuner";
    static final String NET_RADIO_INPUT = "net_radio";

    private static final String SEPARATOR = " · ";

    private YxcNowPlaying() {
    }

    /** @return the line, or {@code null} when there is nothing to show (off, stopped, linked, no play info) */
    public static String describe(YxcDeviceState state) {
        if (state == null || !state.isReachable() || !state.isPowerOn() || state.isLinkInput()) {
            return null;
        }
        String input = state.getInput();
        if (input == null) {
            return null;
        }
        if (TUNER_INPUT.equalsIgnoreCase(input)) {
            return tuner(state.getTuner());
        }
        YxcPlayInfo info = state.getPlayInfo();
        if (info == null || !input.equalsIgnoreCase(info.getInput())) {
            return null;
        }
        String playback = info.getPlayback();
        if (!"play".equalsIgnoreCase(playback) && !"pause".equalsIgnoreCase(playback)) {
            return null; // a stopped source still reports its last station or track
        }
        return network(input, info);
    }

    private static String network(String input, YxcPlayInfo info) {
        String artist = text(info.getArtist());
        String track = text(info.getTrack());
        if (NET_RADIO_INPUT.equalsIgnoreCase(input)) {
            // a net radio sends its station name as the artist and the stream title as the track
            List<String> parts = new ArrayList<>();
            add(parts, artist);
            if (track != null && !track.equals(artist)) {
                parts.add(track);
            }
            return join(parts, SEPARATOR);
        }
        List<String> parts = new ArrayList<>();
        add(parts, artist);
        add(parts, track);
        return join(parts, " – ");
    }

    private static String tuner(YxcTunerInfo info) {
        if (info == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (info.isDab()) {
            add(parts, text(info.getDabServiceLabel()));
            add(parts, text(info.getDabDls()));
            return join(parts, SEPARATOR);
        }
        Integer khz = info.getFmFrequencyKhz();
        if (khz != null && khz > 0) {
            parts.add(String.format(Locale.ROOT, "FM %.2f MHz%s", khz / 1000.0, info.isScanning() ? " …" : ""));
        }
        add(parts, text(info.getRdsService()));
        add(parts, text(stripUnderscore(info.getRdsTextA())));
        return join(parts, SEPARATOR);
    }

    /** The radio text of the tuner ends with an underscore as a filler. */
    private static String stripUnderscore(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.endsWith("_") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String text(String raw) {
        return YxcNames.clean(raw, YxcNames.MAX_TEXT_CHARS);
    }

    private static void add(List<String> parts, String part) {
        if (part != null) {
            parts.add(part);
        }
    }

    private static String join(List<String> parts, String separator) {
        return parts.isEmpty() ? null : String.join(separator, parts);
    }
}
