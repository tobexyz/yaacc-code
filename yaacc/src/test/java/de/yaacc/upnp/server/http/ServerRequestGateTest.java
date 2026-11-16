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
package de.yaacc.upnp.server.http;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.yaacc.share.ShareRegistry;

/**
 * Table test for {@link ServerRequestGate} (task 12.21): the HTTP server of the local service is started at every process
 * creation (discovery), so with the master switch "UPnP Server" off it can still listen. Then the proxied links (the SSL offload) and the
 * content paths of the media server must refuse (403); shared files and anything else that is not a proxied link keep
 * being served: normal sharing works without the master switch. With the master switch on nothing changes: the sub-switches decide as before. Plain JVM.
 */
public class ServerRequestGateTest {

    @Test
    public void aSharedFileIsServedWhateverTheMasterSwitchSays() {
        assertTrue(ServerRequestGate.maySendShare(true, ShareRegistry.Kind.LOCAL_FILE));
        assertTrue(ServerRequestGate.maySendShare(false, ShareRegistry.Kind.LOCAL_FILE));
    }

    @Test
    public void aProxiedLinkIsServedOnlyWithTheMasterSwitchOn() {
        assertTrue(ServerRequestGate.maySendShare(true, ShareRegistry.Kind.HTTP_LINK));
        assertFalse(ServerRequestGate.maySendShare(false, ShareRegistry.Kind.HTTP_LINK));
    }

    @Test
    public void anEntryWithoutAKindIsNeverServed() {
        assertFalse(ServerRequestGate.maySendShare(true, null));
        assertFalse(ServerRequestGate.maySendShare(false, null));
    }

    @Test
    public void withTheMasterSwitchOnTheContentSwitchesDecideAsBefore() {
        assertTrue(ServerRequestGate.contentServerOpen(true, true));
        assertFalse(ServerRequestGate.contentServerOpen(true, false));
        assertTrue(ServerRequestGate.contentProxyOpen(true, true));
        assertFalse(ServerRequestGate.contentProxyOpen(true, false));
    }

    @Test
    public void withTheMasterSwitchOffNoContentPathIsOpenWhateverTheSubSwitchesSay() {
        assertFalse(ServerRequestGate.contentServerOpen(false, true));
        assertFalse(ServerRequestGate.contentServerOpen(false, false));
        assertFalse(ServerRequestGate.contentProxyOpen(false, true));
        assertFalse(ServerRequestGate.contentProxyOpen(false, false));
    }
}
