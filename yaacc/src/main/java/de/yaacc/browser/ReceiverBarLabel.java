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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Text of the new layout's receiver bar: the names of the receivers that currently have a running
 * player, or the "No receiver playing" text. (The bar no longer shows the receiver selection; the
 * selection exists only while the receiver picker is open.)
 * <p>
 * Plain Java, no {@code android.*} imports, so it is unit-tested on the JVM.
 * Names come from untrusted LAN devices: each one is cleaned like every other LAN text
 * ({@link MiniPlayerPresenter#cleanText}: one line, no control or bidi characters, length cap);
 * the result is only ever set as plain {@code TextView} text, never parsed as HTML or used as a
 * format string. There is no "+N" collapse: the bar is limited to two lines and ellipsizes.
 */
final class ReceiverBarLabel {
    static final String SEPARATOR = "; ";

    private ReceiverBarLabel() {
    }

    /**
     * The cleaned, non-empty names without duplicates (several players on one receiver show that
     * receiver once), sorted case-insensitively and joined with {@link #SEPARATOR}, so the label
     * does not depend on the order the players come in. Returns {@code nonePlayingText} when no
     * name is left.
     */
    static String build(Collection<String> playingReceiverNames, String nonePlayingText) {
        Set<String> unique = new LinkedHashSet<>();
        if (playingReceiverNames != null) {
            for (String name : playingReceiverNames) {
                String cleaned = MiniPlayerPresenter.cleanText(name);
                if (!cleaned.isEmpty()) {
                    unique.add(cleaned);
                }
            }
        }
        if (unique.isEmpty()) {
            return nonePlayingText;
        }
        List<String> names = new ArrayList<>(unique);
        names.sort(Comparator.comparing((String name) -> name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Comparator.naturalOrder()));
        return String.join(SEPARATOR, names);
    }
}
