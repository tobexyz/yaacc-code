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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.yaacc.browser.DeviceSessionGrouper.Row;

/**
 * Which rows the receiver list shows: the device sheet lists the receivers with their player sessions below; the picker lists the
 * receivers only; the Receivers section of the top-level page lists the receivers only, because its Players section shows the players.
 */
public class DeviceSheetListedRowsTest {

    @Test
    public void everyListShowsTheReceivers() {
        assertTrue(DeviceSheetAdapter.listsRow(Row.Kind.RECEIVER, false, true));
        assertTrue(DeviceSheetAdapter.listsRow(Row.Kind.RECEIVER, false, false));
        assertTrue(DeviceSheetAdapter.listsRow(Row.Kind.RECEIVER, true, false));
    }

    @Test
    public void theSheetShowsThePlayerSessionsUnderTheReceivers() {
        assertTrue(DeviceSheetAdapter.listsRow(Row.Kind.PLAYER_SESSION, false, true));
    }

    @Test
    public void thePickerAndThePageShowNoPlayerSessions() {
        assertFalse("picker", DeviceSheetAdapter.listsRow(Row.Kind.PLAYER_SESSION, true, true));
        assertFalse("page", DeviceSheetAdapter.listsRow(Row.Kind.PLAYER_SESSION, false, false));
    }
}
