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
import java.util.List;

/**
 * Text of the Content tab's receiver bar: the selected receivers' names.
 * <p>
 * Plain Java, no {@code android.*} imports, so it is unit-tested on the JVM.
 * Names come from untrusted LAN devices; the result is only ever set as plain
 * {@code TextView} text, never parsed as HTML or used as a format string.
 */
final class ReceiverBarLabel {
    static final String SEPARATOR = "; ";

    private ReceiverBarLabel() {
    }

    /**
     * The non-blank names, trimmed, sorted case-insensitively and joined with
     * {@link #SEPARATOR}, so the label doesn't change with the receiver set's
     * HashSet order. Returns {@code noneSelectedText} when no name is left.
     */
    static String build(Collection<String> receiverNames, String noneSelectedText) {
        List<String> names = new ArrayList<>();
        if (receiverNames != null) {
            for (String name : receiverNames) {
                if (name != null && !name.trim().isEmpty()) {
                    names.add(name.trim());
                }
            }
        }
        if (names.isEmpty()) {
            return noneSelectedText;
        }
        names.sort(Comparator.comparing((String name) -> name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Comparator.naturalOrder()));
        return String.join(SEPARATOR, names);
    }
}
