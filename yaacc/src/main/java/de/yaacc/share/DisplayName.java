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
package de.yaacc.share;

/**
 * Makes names that come from another app safe to show on one line. Plain Java so it can be unit
 * tested on the JVM. The name of a shared file is shown to the user before anything is played
 * (the receiver picker names what is about to be shared), so it must not be able to fake or hide
 * parts of itself.
 * <p>
 * This is the one cleaner for LAN- and app-supplied texts; {@code MiniPlayerPresenter} delegates
 * here, so the rules are the same everywhere: whitespace runs (including line breaks and tabs)
 * become one space, control characters, format characters (Unicode category Cf: bidirectional controls, zero-width
 * characters, soft hyphen, tag characters, ...) and the blank Hangul letters are dropped (the
 * zero-width joiner is kept so emoji sequences survive, and the non-joiner for scripts that need it), the result is trimmed and cut
 * at {@link #MAX_LENGTH} chars without splitting a surrogate pair. Show the result with
 * {@code setText}; it is not markup-escaped.
 */
public final class DisplayName {

    /** The longest cleaned name, in chars. */
    public static final int MAX_LENGTH = 200;

    /** Used only when both the name and the fallback clean to nothing, so the result is never empty. */
    private static final String LAST_RESORT = "?";

    private DisplayName() {
    }

    /**
     * @param untrusted the text from another app or device, may be null
     * @return the cleaned text, possibly empty; never null
     */
    public static String clean(String untrusted) {
        if (untrusted == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(untrusted.length(), MAX_LENGTH));
        boolean pendingSpace = false;
        int index = 0;
        while (index < untrusted.length()) {
            int codePoint = untrusted.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (Character.getType(codePoint) == Character.CONTROL || isInvisible(codePoint)) {
                continue;
            }
            int needed = Character.charCount(codePoint) + (pendingSpace ? 1 : 0);
            if (out.length() + needed > MAX_LENGTH) {
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

    /**
     * The name to show for a shared file: the provider's name, cleaned, or, if nothing visible is
     * left, the cleaned {@code fallback} (a fixed, localized text such as "a shared file").
     *
     * @param providerName the display name the sending app reported, may be null
     * @param fallback     the text for "no usable name", may be null
     * @return a cleaned, non-empty name (a single {@code ?} only if the fallback is unusable too)
     */
    public static String forShare(String providerName, String fallback) {
        String cleaned = clean(providerName);
        if (!cleaned.isEmpty()) {
            return cleaned;
        }
        String cleanedFallback = clean(fallback);
        return cleanedFallback.isEmpty() ? LAST_RESORT : cleanedFallback;
    }

    /**
     * Characters that draw nothing but can make two names look the same, hide a part of a name or
     * leave a "blank" name: every Unicode format character (category Cf: bidi controls, zero-width
     * space, word joiner, soft hyphen, invisible math operators, tag characters, ...), except the
     * zero-width joiner (emoji sequences) and the zero-width non-joiner (Persian and other scripts
     * need it), plus the blank Hangul letters.
     */
    private static boolean isInvisible(int codePoint) {
        if (codePoint == 0x200D || codePoint == 0x200C) {
            return false;
        }
        return Character.getType(codePoint) == Character.FORMAT
                // letters that draw nothing
                || codePoint == 0x115F || codePoint == 0x1160 || codePoint == 0x3164 || codePoint == 0xFFA0;
    }
}
