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
package de.yaacc.sendspin;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HubPlaybackBridgeTest {

    @Test
    public void aUpnpDurationIsReadInMilliseconds() {
        assertEquals(215_000L, HubPlaybackBridge.parseMillis("0:03:35"));
        assertEquals(215_500L, HubPlaybackBridge.parseMillis("0:03:35.500"));
        assertEquals(3_723_000L, HubPlaybackBridge.parseMillis("1:02:03"));
        assertEquals(95_000L, HubPlaybackBridge.parseMillis("1:35"));
        assertEquals(0L, HubPlaybackBridge.parseMillis("NOT_IMPLEMENTED"));
        assertEquals(0L, HubPlaybackBridge.parseMillis(null));
        assertEquals(0L, HubPlaybackBridge.parseMillis(""));
    }
}
