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
package de.yaacc.share;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import de.yaacc.share.ProxyAvailability.Decision;

/**
 * Table test for {@link ProxyAvailability} (task 12.21, owner's decision 2026-11-17): the media proxy serves a shared
 * link only when the proxy checkbox AND the master switch "UPnP Server" are on, otherwise no server serves the link.
 * A link is never routed through the proxy by the checkbox alone. Plain JVM.
 */
public class ProxyAvailabilityTest {

    @Test
    public void proxyTickedAndMasterOnUsesTheProxy() {
        assertEquals(Decision.USE_PROXY, ProxyAvailability.decide(true, true));
    }

    @Test
    public void proxyTickedButMasterOffRefusesTheLinkBecauseNothingServesIt() {
        assertEquals(Decision.REFUSE_NO_SERVER, ProxyAvailability.decide(false, true));
    }

    @Test
    public void proxyNotTickedPlaysTheLinkDirectlyWhateverTheMasterSwitchSays() {
        assertEquals(Decision.DIRECT, ProxyAvailability.decide(true, false));
        assertEquals(Decision.DIRECT, ProxyAvailability.decide(false, false));
    }
}
