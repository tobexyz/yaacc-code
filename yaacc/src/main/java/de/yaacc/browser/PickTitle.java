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

/**
 * The line under "Play on..." that names what a shared item is. The name comes from another app's
 * provider, so it only ever reaches the screen through {@link MiniPlayerPresenter#cleanText}, and
 * a name that is empty after cleaning is replaced by a fixed text. Plain Java, no
 * {@code android.*}, so it is unit tested on the JVM.
 */
final class PickTitle {

    private PickTitle() {
    }

    /**
     * @param name     the display name of the shared item, untrusted, may be null
     * @param fallback the fixed text used when nothing is left after cleaning, may be null
     * @return the cleaned name, else the fallback, else an empty string; never null
     */
    static String nameOrFallback(String name, String fallback) {
        String cleaned = MiniPlayerPresenter.cleanText(name);
        if (!cleaned.isEmpty()) {
            return cleaned;
        }
        return fallback == null ? "" : fallback;
    }

    /**
     * @param forShare whether the pick belongs to a shared item (only then is there a line to show)
     * @return the text for the line under the title, or null if there is none
     */
    static String subtitle(boolean forShare, String name, String fallback) {
        return forShare ? nameOrFallback(name, fallback) : null;
    }
}
