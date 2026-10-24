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

import de.yaacc.browser.DeviceSessionGrouper.PlayerRef;

/**
 * The Android-free decisions behind the mini-player bar: whether it shows, what the play/pause
 * button does, and how the strings that come from LAN devices (track title, receiver and player
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
    static final int MAX_TEXT_LENGTH = 200;

    private MiniPlayerPresenter() {
    }

    /** The bar shows exactly when there is a featured session. */
    public static boolean isVisible(PlayerRef featured) {
        return featured != null;
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
     * Makes {@code untrusted} safe to show on one line: whitespace runs (including line breaks and
     * tabs) become one space, control characters, bidirectional controls and zero-width
     * characters are dropped, the
     * result is trimmed and cut at {@link #MAX_TEXT_LENGTH} chars without splitting a surrogate
     * pair. Null becomes the empty string.
     */
    static String cleanText(String untrusted) {
        if (untrusted == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(untrusted.length(), MAX_TEXT_LENGTH));
        boolean pendingSpace = false;
        int index = 0;
        while (index < untrusted.length()) {
            int codePoint = untrusted.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (Character.getType(codePoint) == Character.CONTROL || isBidiControl(codePoint)) {
                continue;
            }
            int needed = Character.charCount(codePoint) + (pendingSpace ? 1 : 0);
            if (out.length() + needed > MAX_TEXT_LENGTH) {
                break;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(codePoint);
        }
        return out.toString();
    }

    private static boolean isBidiControl(int codePoint) {
        return codePoint == 0x200E || codePoint == 0x200F || codePoint == 0x061C
                // invisible zero-width characters that make two titles look the same
                || codePoint == 0x200B || codePoint == 0x2060 || codePoint == 0xFEFF
                || (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }
}
