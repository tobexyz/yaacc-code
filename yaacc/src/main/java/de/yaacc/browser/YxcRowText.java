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
package de.yaacc.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.yaacc.yxc.YxcDeviceState;
import de.yaacc.yxc.YxcDistributionInfo;

/**
 * The line a receiver row shows for a device with group support: power, input and group. Pure; the words come in
 * through {@link Words}, so the composition is tested without Android.
 */
final class YxcRowText {

    /** The localized words; the binder backs them with string resources. */
    interface Words {
        String on();

        String standby();

        String notReachable();

        String linkedWith(String group);

        String linked();

        String groupOf(String group);

        String groupLeader();
    }

    private static final String SEPARATOR = " · ";

    private YxcRowText() {
    }

    /** @return the line, or {@code null} when there is nothing to show (no state yet) */
    static String describe(YxcDeviceState state, Words words) {
        if (state == null || (!state.isReachable() && state.getRole() == YxcDistributionInfo.Role.UNKNOWN)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (!state.isReachable()) {
            parts.add(words.notReachable());
        } else if (state.getPower() != null) {
            parts.add(state.isPowerOn() ? words.on() : words.standby());
            if (state.isPowerOn() && !state.isLinkInput()) {
                String input = inputName(state);
                if (input != null) {
                    parts.add(input);
                }
            }
        }
        String group = groupPart(state, words);
        if (group != null) {
            parts.add(group);
        }
        return parts.isEmpty() ? null : String.join(SEPARATOR, parts);
    }

    private static String groupPart(YxcDeviceState state, Words words) {
        String name = clean(state.getGroupName());
        switch (state.getRole()) {
            case CLIENT:
                return name == null ? words.linked() : words.linkedWith(name);
            case SERVER:
                return name == null ? words.groupLeader() : words.groupOf(name);
            default:
                return null;
        }
    }

    /** The name the device gave the input, else its id made readable ({@code net_radio} to {@code Net radio}). */
    static String inputName(YxcDeviceState state) {
        String text = clean(state.getInputText());
        if (text != null) {
            return text;
        }
        String id = clean(state.getInput());
        if (id == null) {
            return null;
        }
        String readable = id.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(readable.charAt(0)) + readable.substring(1);
    }

    /** The longest name shown; a device can send much more. */
    static final int MAX_NAME_CHARS = 48;

    /**
     * A name from the device made fit for one line of text: control and format characters (line breaks, bidi
     * overrides) are dropped, the length is capped. {@code null} when nothing is left.
     */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() <= MAX_NAME_CHARS; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
                continue;
            }
            out.appendCodePoint(cp);
        }
        String text = out.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > MAX_NAME_CHARS ? text.substring(0, MAX_NAME_CHARS) + "\u2026" : text;
    }
}
