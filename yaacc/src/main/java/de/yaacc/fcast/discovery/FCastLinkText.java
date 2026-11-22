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
package de.yaacc.fcast.discovery;

/** The text of the confirmation dialog for an FCast connection link (task 5.6): what will be added, and the note on the protocol. Pure. */
public final class FCastLinkText {

    /** The words of the dialog; the app fills them from its string resources. */
    public interface Words {
        /** The line with the name, e.g. "Name: %s". */
        String name(String name);

        /** The text for a receiver that gave no name. */
        String unnamed();

        /** The heading of the list of addresses. */
        String addresses();

        /**
         * The note: nothing is played before the user chooses the receiver; version 3 is unencrypted.
         *
         * @param withKey true if the link carries the receiver's key (version 4, encrypted, from Android 10)
         */
        String note(boolean withKey);
    }

    private FCastLinkText() {
    }

    /** The message of the dialog: the name, the addresses one per line, then the note. */
    public static String message(FCastConnectionUrl.Parsed parsed, Words words) {
        StringBuilder text = new StringBuilder();
        text.append(words.name(parsed.getName().isEmpty() ? words.unnamed() : parsed.getName())).append("\n\n");
        text.append(words.addresses()).append('\n');
        for (FCastEndpoint endpoint : parsed.getEndpoints()) {
            text.append("  ").append(endpoint).append('\n');
        }
        boolean withKey = de.yaacc.fcast.protocol.FCastPin.parse(parsed.getHints().getFingerprint(), de.yaacc.fcast.protocol.FCastPin.Source.LINK) != null;
        text.append('\n').append(words.note(withKey));
        return text.toString();
    }
}
