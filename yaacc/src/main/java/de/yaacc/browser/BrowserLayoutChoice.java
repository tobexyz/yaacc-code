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
 * Maps the stored "Layout" preference value to the browser layout to launch:
 * the classic tab activity or the new tab-less one. Anything that is not
 * exactly {@link #STORED_NEW} (null, empty, unknown, differently cased)
 * resolves to classic, so every device including Android TV defaults to
 * classic and an explicit "new" is honored on any device.
 * <p>
 * Deliberately has no {@code android.*} imports so it can be unit-tested on
 * the plain JVM, like {@link DeviceSessionGrouper}.
 */
public final class BrowserLayoutChoice {

    /** Stored preference value for the classic tab layout. */
    public static final String STORED_CLASSIC = "classic";
    /** Stored preference value for the new layout. */
    public static final String STORED_NEW = "new";
    /** Stored value used when the preference was never set. */
    public static final String DEFAULT_STORED_VALUE = STORED_CLASSIC;

    public enum Layout { CLASSIC, NEW }

    private BrowserLayoutChoice() {
    }

    /**
     * @param storedValue the stored preference value, may be null
     * @return {@link Layout#NEW} for exactly {@link #STORED_NEW}, otherwise {@link Layout#CLASSIC}
     */
    public static Layout resolve(String storedValue) {
        return STORED_NEW.equals(storedValue) ? Layout.NEW : Layout.CLASSIC;
    }
}
