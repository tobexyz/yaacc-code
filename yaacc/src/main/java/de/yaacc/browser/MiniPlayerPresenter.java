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
package de.yaacc.browser;

import de.yaacc.share.DisplayName;

/**
 * The Android-free decisions behind the mini-player rows: what the play/pause button does, and
 * how the strings that come from LAN devices (track title, receiver and player
 * names) are cleaned before they are set on a {@code TextView}.
 * <p>
 * Deliberately has no {@code android.*} imports so it can be unit-tested on the plain JVM.
 * The cleaning is not an HTML or format-string defence (the views only ever use
 * {@code setText}); it removes what could make a one-line label misleading: control characters,
 * line breaks and the bidirectional controls that can reorder the text around them.
 */
public final class MiniPlayerPresenter {

    /** What the combined play/pause button does when pressed. */
    public enum TransportAction {
        PLAY, PAUSE
    }

    /** Longest text the bar will hold; the view ellipsizes far earlier. */
    static final int MAX_TEXT_LENGTH = DisplayName.MAX_LENGTH;

    private MiniPlayerPresenter() {
    }

    /** The play/pause button pauses a playing session and starts any other one. */
    public static TransportAction toggleAction(boolean playing) {
        return playing ? TransportAction.PAUSE : TransportAction.PLAY;
    }

    /**
     * The track title, or {@code fallback} (the player's name) when the title is missing or
     * blank. Both are cleaned; never null.
     */
    public static String title(String title, String fallback) {
        String cleaned = cleanText(title);
        return cleaned.isEmpty() ? cleanText(fallback) : cleaned;
    }

    /**
     * The receiver's name as resolved from the registry, or the player's own name when the device
     * is not known (any more). Cleaned; never null.
     */
    public static String receiverLabel(String resolvedReceiverName, String playerName) {
        String cleaned = cleanText(resolvedReceiverName);
        return cleaned.isEmpty() ? cleanText(playerName) : cleaned;
    }

    /**
     * Makes {@code untrusted} safe to show on one line (see {@link DisplayName#clean(String)} for
     * the rules). Null becomes the empty string.
     */
    static String cleanText(String untrusted) {
        return DisplayName.clean(untrusted);
    }
}
