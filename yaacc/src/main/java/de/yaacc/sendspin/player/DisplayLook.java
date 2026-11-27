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
package de.yaacc.sendspin.player;

import de.yaacc.sendspin.session.ControllerMessages;

/**
 * How the display screen of this phone is coloured (task 11.5). With text or a cover to show, the readable pair of the server is used
 * (its dark background and the light colour for text on it: the server guarantees their contrast). When only colours arrive, the screen
 * is a light: it shows the colour of the server as it is, the dominant colour first, without any change of hue or brightness.
 */
public final class DisplayLook {

    /** True if the screen shows a colour only (no text, no cover): the colour fills the screen and the phone's brightness is full. */
    public final boolean lightMode;
    /** The background, as an ARGB value. */
    public final int background;
    /** The colour of the text, as an ARGB value. */
    public final int foreground;

    private DisplayLook(boolean lightMode, int background, int foreground) {
        this.lightMode = lightMode;
        this.background = background;
        this.foreground = foreground;
    }

    private static int argb(int[] c) {
        return 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
    }

    /** @param color the colours the server sent, or null; @param hasContent whether text or a cover is shown */
    public static DisplayLook of(ControllerMessages.Color color, boolean hasContent) {
        int black = 0xFF000000;
        int white = 0xFFFFFFFF;
        if (hasContent) {
            int background = color != null && color.backgroundDark != null ? argb(color.backgroundDark) : black;
            int foreground = color != null && color.onDark != null ? argb(color.onDark) : white;
            return new DisplayLook(false, background, foreground);
        }
        int[] chosen = null;
        if (color != null) {
            int[][] order = {color.primary, color.backgroundDark, color.accent, color.onDark, color.backgroundLight, color.onLight};
            for (int[] c : order) {
                if (c != null) {
                    chosen = c;
                    break;
                }
            }
        }
        return new DisplayLook(true, chosen == null ? black : argb(chosen), white);
    }
}
