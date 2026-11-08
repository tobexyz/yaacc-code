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

import java.util.Arrays;
import java.util.Collections;

import de.yaacc.upnp.UpnpClient;

/**
 * The hint of the Receivers section: the list of receivers always holds this phone (the local renderer), so "none found" means that no
 * other receiver is listed.
 */
public class ReceiversHintTest {

    @Test
    public void noReceiverAtAllShowsTheHint() {
        assertTrue(SectionHintAdapter.noRemoteReceiver(Collections.<String>emptyList()));
    }

    @Test
    public void onlyThisPhoneShowsTheHint() {
        assertTrue(SectionHintAdapter.noRemoteReceiver(Collections.singletonList(UpnpClient.LOCAL_UID)));
    }

    @Test
    public void anotherReceiverHidesTheHint() {
        assertFalse(SectionHintAdapter.noRemoteReceiver(Arrays.asList(UpnpClient.LOCAL_UID, "uuid:living-room")));
        assertFalse(SectionHintAdapter.noRemoteReceiver(Collections.singletonList("uuid:living-room")));
    }
}
