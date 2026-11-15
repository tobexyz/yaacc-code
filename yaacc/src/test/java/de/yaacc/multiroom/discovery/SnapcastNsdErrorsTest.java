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
package de.yaacc.multiroom.discovery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The names of the {@code NsdManager} failure codes, for the discovery report (task 11.6). */
public class SnapcastNsdErrorsTest {

    @Test
    public void theCodesOfTheDocumentationHaveTheirNames() {
        assertEquals("FAILURE_INTERNAL_ERROR (0)", SnapcastNsdErrors.describe(0));
        assertEquals("FAILURE_ALREADY_ACTIVE (3)", SnapcastNsdErrors.describe(3));
        assertEquals("FAILURE_MAX_LIMIT (4)", SnapcastNsdErrors.describe(4));
        assertEquals("FAILURE_OPERATION_NOT_RUNNING (5)", SnapcastNsdErrors.describe(5));
        assertEquals("FAILURE_BAD_PARAMETERS (6)", SnapcastNsdErrors.describe(6));
    }

    @Test
    public void anUnknownCodeIsShownAsANumber() {
        assertEquals("unknown code 1", SnapcastNsdErrors.describe(1));
        assertEquals("unknown code -7", SnapcastNsdErrors.describe(-7));
        assertEquals("unknown code 2147483647", SnapcastNsdErrors.describe(Integer.MAX_VALUE));
    }
}
