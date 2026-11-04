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
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.yaacc.yxc.YxcDeviceState;
import de.yaacc.yxc.YxcDistributionInfo;
import de.yaacc.yxc.YxcNames;

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

        String groupWith(String names);

        String groupLeader();

        String noGroupOnLinkInput();
    }

    private static final String SEPARATOR = " · ";

    private YxcRowText() {
    }

    /** @return the line, or {@code null} when there is nothing to show (no state yet); the group members are not named */
    static String describe(YxcDeviceState state, Words words) {
        return describe(state, words, null, Collections.<String>emptyList());
    }

    /**
     * @param serverName  the name of the receiver that serves this client's group, {@code null} if it is not known
     * @param clientNames the names of the receivers a server serves, empty if none is known
     * @return the line, or {@code null} when there is nothing to show (no state yet)
     */
    static String describe(YxcDeviceState state, Words words, String serverName, List<String> clientNames) {
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
        String group = groupPart(state, words, serverName, clientNames);
        if (group != null) {
            parts.add(group);
        }
        return parts.isEmpty() ? null : String.join(SEPARATOR, parts);
    }

    private static String groupPart(YxcDeviceState state, Words words, String serverName, List<String> clientNames) {
        switch (state.getRole()) {
            case CLIENT: {
                if (state.isStuckClient()) {
                    return words.noGroupOnLinkInput();
                }
                String server = clean(serverName);
                return server == null ? words.linked() : words.linkedWith(server);
            }
            case SERVER: {
                List<String> names = new ArrayList<>();
                for (String name : clientNames) {
                    String cleaned = clean(name);
                    if (cleaned != null) {
                        names.add(cleaned);
                    }
                }
                return names.isEmpty() ? words.groupLeader() : words.groupWith(String.join(", ", names));
            }
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
    static final int MAX_NAME_CHARS = YxcNames.MAX_NAME_CHARS;

    /** A name from the device made fit for one line of text, {@code null} when nothing is left. */
    static String clean(String raw) {
        return YxcNames.clean(raw);
    }
}
