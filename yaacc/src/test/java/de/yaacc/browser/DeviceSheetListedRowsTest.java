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

import java.util.Collections;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.share.PickContent;

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
    // ---- 11.2: the picker does not offer a receiver that plays music only for pictures and videos

    private static SnapcastGroupDevice snapcastGroup() {
        SnapcastClient member = new SnapcastClient("m", "member", "", true, new SnapcastVolume(50, false), 0);
        SnapcastGroup group = new SnapcastGroup("g", "Group", false, "Default", Collections.singletonList(member));
        return SnapcastGroupDevice.of(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), group, false,
                new SnapcastGroupDevice.Labels("Snapcast group", "offline"));
    }

    @Test
    public void aSnapcastGroupIsOfferedForMusicAndWhenTheContentIsNotKnown() {
        assertTrue(DeviceSheetAdapter.offersReceiver(snapcastGroup(), PickContent.ofKind("audio")));
        assertTrue(DeviceSheetAdapter.offersReceiver(snapcastGroup(), PickContent.UNKNOWN));
        assertTrue("no content given: as before", DeviceSheetAdapter.offersReceiver(snapcastGroup(), null));
    }

    @Test
    public void aSnapcastGroupIsNotOfferedForImagesAndVideos() {
        assertFalse(DeviceSheetAdapter.offersReceiver(snapcastGroup(), PickContent.ofKind("image")));
        assertFalse(DeviceSheetAdapter.offersReceiver(snapcastGroup(), PickContent.ofKind("video")));
    }

    @Test
    public void everyOtherReceiverIsOfferedForEveryContent() {
        org.fourthline.cling.model.meta.Device<?, ?, ?> other = org.mockito.Mockito.mock(org.fourthline.cling.model.meta.Device.class);

        assertTrue(DeviceSheetAdapter.offersReceiver(other, PickContent.ofKind("image")));
        assertTrue(DeviceSheetAdapter.offersReceiver(other, PickContent.ofKind("video")));
        assertTrue(DeviceSheetAdapter.offersReceiver(other, PickContent.ofKind("audio")));
        assertTrue(DeviceSheetAdapter.offersReceiver(null, PickContent.ofKind("image")));
    }
}
