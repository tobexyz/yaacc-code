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
package de.yaacc.fcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import de.yaacc.fcast.discovery.FCastEndpoint;
import de.yaacc.fcast.protocol.FCastSession;

/**
 * Task 5.3 and D11: the sessions that the screens open on demand. One session per receiver, a short grace after the last link, a cap
 * of eight receivers, and a hand-over to a player that closes the screens' session at once. Plain JVM: the sessions are mocks, the clock
 * is a short real grace time.
 */
public class FCastReceiverLinksTest {

    private static final long GRACE = 150;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "test-links");
        thread.setDaemon(true);
        return thread;
    });
    private final List<FCastSession> made = new ArrayList<>();
    private final FCastReceiverLinks links = new FCastReceiverLinks((endpoint, listener, ex) -> {
        FCastSession session = mock(FCastSession.class);
        made.add(session);
        return session;
    }, executor, GRACE);

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    private static FCastEndpoint endpoint(int last) {
        return FCastEndpoint.parse("192.168.0." + last);
    }

    @Test
    public void openMakesOneSessionPerReceiverAndSharesIt() {
        FCastReceiverLinks.Link first = links.open(endpoint(1));
        FCastReceiverLinks.Link second = links.open(endpoint(1));
        FCastReceiverLinks.Link other = links.open(endpoint(2));

        assertEquals(2, made.size());
        assertSame(first.getSession(), second.getSession());
        assertTrue(links.isOpen(endpoint(1)));
        assertSame(first.getSession(), links.peek(endpoint(1)));
        verify(first.getSession(), times(2)).acquire();
        assertNotNull(other);
    }

    @Test
    public void theSessionClosesAfterTheGraceOnceTheLastLinkIsClosed() throws Exception {
        FCastReceiverLinks.Link first = links.open(endpoint(1));
        FCastReceiverLinks.Link second = links.open(endpoint(1));
        FCastSession session = first.getSession();

        first.close();
        Thread.sleep(GRACE * 3);
        assertTrue("another link is still open", links.isOpen(endpoint(1)));
        verify(session, never()).close();
        second.close();
        second.close(); // idempotent
        Thread.sleep(GRACE * 3);

        assertFalse(links.isOpen(endpoint(1)));
        verify(session).close();
        verify(session, times(2)).release();
    }

    @Test
    public void aNewLinkWithinTheGraceKeepsTheSession() throws Exception {
        FCastReceiverLinks.Link first = links.open(endpoint(1));
        FCastSession session = first.getSession();
        first.close();

        FCastReceiverLinks.Link again = links.open(endpoint(1));
        Thread.sleep(GRACE * 3);

        assertSame(session, again.getSession());
        assertTrue(links.isOpen(endpoint(1)));
        verify(session, never()).close();
        assertEquals(1, made.size());
    }

    @Test
    public void atMostEightReceiversAreOpenAtOnce() {
        for (int i = 1; i <= FCastReceiverLinks.MAX_SESSIONS; i++) {
            assertNotNull(links.open(endpoint(i)));
        }

        assertNull(links.open(endpoint(99)));
        assertNotNull("a receiver that is open can still be used", links.open(endpoint(1)));
    }

    @Test
    public void aHandOverClosesTheSessionAtOnceAndAFreshOneIsMadeLater() {
        FCastReceiverLinks.Link link = links.open(endpoint(1));
        FCastSession session = link.getSession();

        links.handOverToPlayer(endpoint(1));

        verify(session).close();
        assertFalse(links.isOpen(endpoint(1)));
        link.close(); // a late close of the old link does nothing
        verify(session, never()).release();
        assertNotNull(links.open(endpoint(1)));
        assertEquals(2, made.size());
    }

    @Test
    public void closeAllClosesEverything() {
        FCastReceiverLinks.Link a = links.open(endpoint(1));
        FCastReceiverLinks.Link b = links.open(endpoint(2));

        links.closeAll();

        verify(a.getSession()).close();
        verify(b.getSession()).close();
        assertFalse(links.isOpen(endpoint(1)));
        assertFalse(links.isOpen(endpoint(2)));
    }
}
