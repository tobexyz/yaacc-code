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
package de.yaacc.fcast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.yaacc.fcast.protocol.FCastCapabilities;
import de.yaacc.fcast.protocol.FCastTrack;

/**
 * What the sheet of a version 4 receiver offers for tracks, subtitles and capabilities (task 7.4): the choices of the audio and the
 * subtitle picker, the label of a track, the text of the formats the receiver can play, and which address a subtitle may have. Pure:
 * the words come from {@link Labels}.
 */
public final class FCastTrackChoices {
    /** The most characters of a subtitle address (the same limit as a URL of an item). */
    public static final int MAX_SUBTITLE_URL = 2048;

    /** The words of the user's language. */
    public interface Labels {
        /** "Off". */
        String off();

        /** "Track 3" for a track without title and language. */
        String track(long id);

        /** The name of a language by its ISO 639 code in the user's language, or null if unknown. */
        String language(String code);

        /** The name of a group of formats ("Video"). */
        String group(Group group);

        String yes();

        String no();

        /** "1920 x 1080". */
        String screen(int width, int height);
    }

    /** The lines of {@link #capabilityLines}. */
    public enum Group {
        PROTOCOLS, CONTAINERS, VIDEO, AUDIO, SUBTITLES, HDR, IMAGES, EXTERNAL_SUBTITLES, SCREEN
    }

    /** One line of a picker: the track's id, or null for "off". */
    public static final class Choice {
        private final String label;
        private final Long id;

        Choice(String label, Long id) {
            this.label = label;
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        /** The track to select, null for "off". */
        public Long getId() {
            return id;
        }
    }

    private FCastTrackChoices() {
    }

    /** The audio tracks, in the order of the receiver; empty if there is none (then the sheet has no audio row). */
    public static List<Choice> audio(List<FCastTrack> tracks, Labels labels) {
        List<Choice> choices = new ArrayList<>();
        for (FCastTrack track : tracks) {
            if (track.getType() == FCastTrack.Type.AUDIO) {
                choices.add(new Choice(label(track, labels), track.getId()));
            }
        }
        return choices;
    }

    /** "Off" and the subtitle tracks. */
    public static List<Choice> subtitles(List<FCastTrack> tracks, Labels labels) {
        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(labels.off(), null));
        for (FCastTrack track : tracks) {
            if (track.getType() == FCastTrack.Type.SUBTITLE) {
                choices.add(new Choice(label(track, labels), track.getId()));
            }
        }
        return choices;
    }

    /** The index of the selected track; for no selection the index of "off" (0) if there is one, else -1. */
    public static int selectedIndex(List<Choice> choices, Long selectedId) {
        for (int i = 0; i < choices.size(); i++) {
            Long id = choices.get(i).getId();
            if (id == null ? selectedId == null : id.equals(selectedId)) {
                return i;
            }
        }
        return -1;
    }

    /** The title if there is one (with the language in brackets when it adds something), else the language, else "Track n". */
    public static String label(FCastTrack track, Labels labels) {
        String language = languageName(track.getLanguage(), labels);
        String title = track.getTitle();
        if (!title.isEmpty()) {
            return language.isEmpty() || title.toLowerCase(Locale.ROOT).contains(language.toLowerCase(Locale.ROOT)) ? title : title + " (" + language + ")";
        }
        return language.isEmpty() ? labels.track(track.getId()) : language;
    }

    private static String languageName(String code, Labels labels) {
        if (code == null || code.isEmpty() || "und".equalsIgnoreCase(code) || "zxx".equalsIgnoreCase(code) || "mul".equalsIgnoreCase(code)) {
            return "";
        }
        String name = labels.language(code);
        return name == null || name.isEmpty() ? code : name;
    }

    /** The lines of "what the receiver can play", only the groups that have something. */
    public static List<String> capabilityLines(FCastCapabilities caps, Labels labels) {
        List<String> lines = new ArrayList<>();
        add(lines, labels.group(Group.PROTOCOLS), caps.getProtocols());
        add(lines, labels.group(Group.CONTAINERS), caps.getContainers());
        add(lines, labels.group(Group.VIDEO), caps.getVideoFormats());
        add(lines, labels.group(Group.AUDIO), caps.getAudioFormats());
        add(lines, labels.group(Group.SUBTITLES), caps.getSubtitleFormats());
        add(lines, labels.group(Group.HDR), caps.getHdrFormats());
        add(lines, labels.group(Group.IMAGES), caps.getImageFormats());
        lines.add(labels.group(Group.EXTERNAL_SUBTITLES) + ": " + (caps.supportsExternalSubtitles() ? labels.yes() : labels.no()));
        if (caps.getDisplayWidth() > 0 && caps.getDisplayHeight() > 0) {
            lines.add(labels.group(Group.SCREEN) + ": " + labels.screen(caps.getDisplayWidth(), caps.getDisplayHeight()));
        }
        return lines;
    }

    private static void add(List<String> lines, String name, List<String> values) {
        if (!values.isEmpty()) {
            lines.add(name + ": " + String.join(", ", values));
        }
    }

    /**
     * Is this text an address the receiver may fetch a subtitle from: {@code http} or {@code https}, a host, no spaces or control
     * characters, at most {@value #MAX_SUBTITLE_URL} characters. The receiver fetches it itself, so nothing else (no {@code file},
     * {@code content} or script scheme) is let through.
     */
    public static boolean isAcceptableSubtitleUrl(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_SUBTITLE_URL) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f)) {
                return false;
            }
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(text);
        } catch (java.net.URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) && uri.getHost() != null
                && !uri.getHost().isEmpty();
    }
}
