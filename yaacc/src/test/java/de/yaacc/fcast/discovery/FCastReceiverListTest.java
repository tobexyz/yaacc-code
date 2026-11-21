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
package de.yaacc.fcast.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Task 5.4: removing a receiver also takes it out of the selection (a selected receiver that is gone would stay as an offline card), and the rows of the list. */
public class FCastReceiverListTest {

    private final FCastDirectory directory = new FCastDirectory(() -> 0L, 30_000);

    private static FCastEndpoint endpoint(String text) {
        return FCastEndpoint.parse(text);
    }

    // ---- removing

    private static final class Fakes implements FCastReceiverAdmin.Manual, FCastReceiverAdmin.SelectionStore, FCastReceiverAdmin.Remembered {
        final Set<String> manual = new HashSet<>();
        Set<String> selection = new HashSet<>();
        final List<Collection<String>> remembered = new ArrayList<>();
        boolean unreadable;

        @Override
        public boolean removeManual(FCastEndpoint endpoint) {
            return manual.remove(endpoint.toString());
        }

        @Override
        public Set<String> read() {
            if (unreadable) {
                throw new IllegalStateException("damaged");
            }
            return new HashSet<>(selection);
        }

        @Override
        public void write(Set<String> receiverIds) {
            selection = new HashSet<>(receiverIds);
        }

        @Override
        public void selectionChanged(Collection<String> receiverIds) {
            remembered.add(new HashSet<>(receiverIds));
        }
    }

    @Test
    public void removingASelectedReceiverTakesItOutOfTheSelectionAndOnlyIt() {
        Fakes fakes = new Fakes();
        fakes.manual.add("192.168.0.9:46899");
        fakes.selection.addAll(Arrays.asList("fcast:192.168.0.9:46899", "fcast:192.168.0.10:46899", "local", "uuid:abc"));
        FCastReceiverAdmin admin = new FCastReceiverAdmin(fakes, fakes, fakes);

        FCastReceiverAdmin.Result result = admin.remove(endpoint("192.168.0.9"));

        assertTrue(result.wasManual());
        assertEquals(1, result.getSelectionRemoved());
        assertEquals(new HashSet<>(Arrays.asList("fcast:192.168.0.10:46899", "local", "uuid:abc")), fakes.selection);
        assertEquals("the remembered list follows the selection", 1, fakes.remembered.size());
        assertEquals(fakes.selection, fakes.remembered.get(0));
    }

    @Test
    public void removingAReceiverThatIsNotSelectedLeavesTheSelectionAlone() {
        Fakes fakes = new Fakes();
        fakes.manual.add("192.168.0.9:46899");
        fakes.selection.add("local");
        FCastReceiverAdmin admin = new FCastReceiverAdmin(fakes, fakes, fakes);

        FCastReceiverAdmin.Result result = admin.remove(endpoint("192.168.0.9"));

        assertTrue(result.wasManual());
        assertEquals(0, result.getSelectionRemoved());
        assertEquals(Collections.singleton("local"), fakes.selection);
    }

    @Test
    public void aReceiverThatWasNotInTheManualListChangesNothingInTheList() {
        Fakes fakes = new Fakes();
        FCastReceiverAdmin admin = new FCastReceiverAdmin(fakes, fakes, fakes);

        assertFalse(admin.remove(endpoint("192.168.0.9")).wasManual());
        assertFalse(admin.remove(null).wasManual());
    }

    @Test
    public void anUnreadableSelectionDoesNotStopTheRemoval() {
        Fakes fakes = new Fakes();
        fakes.manual.add("192.168.0.9:46899");
        fakes.unreadable = true;
        FCastReceiverAdmin admin = new FCastReceiverAdmin(fakes, fakes, fakes);

        FCastReceiverAdmin.Result result = admin.remove(endpoint("192.168.0.9"));

        assertTrue(result.wasManual());
        assertEquals(0, result.getSelectionRemoved());
        assertTrue("the manual list is done", fakes.manual.isEmpty());
    }

    // ---- the rows

    @Test
    public void theRowsShowEveryKindAndOnlyManualOnesCanBeRemoved() throws Exception {
        directory.setRemembered(Collections.singletonList(new FCastDirectory.Known(endpoint("192.168.0.30"), "Remembered one")));
        directory.setManual(Collections.singletonList(new FCastDirectory.Known(endpoint("192.168.0.20"), "Typed one")));
        directory.serviceFound("_fcast._tcp|Found", "Found", InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 0, 10}), 46899, null);
        directory.serviceFound("_fcast._tcp|Both", "Both", InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 0, 20}), 46899, null);

        List<FCastReceiverRows.Row> rows = FCastReceiverRows.of(directory.snapshot());

        assertEquals(3, rows.size());
        FCastReceiverRows.Row found = byEndpoint(rows, "192.168.0.10:46899");
        assertEquals(FCastReceiverRows.Kind.DISCOVERED, found.getKind());
        assertFalse(found.isRemovable());
        FCastReceiverRows.Row both = byEndpoint(rows, "192.168.0.20:46899");
        assertEquals(FCastReceiverRows.Kind.MANUAL, both.getKind());
        assertTrue(both.isAnnounced());
        assertTrue(both.isRemovable());
        FCastReceiverRows.Row remembered = byEndpoint(rows, "192.168.0.30:46899");
        assertEquals(FCastReceiverRows.Kind.REMEMBERED, remembered.getKind());
        assertFalse(remembered.isRemovable());
        assertEquals("Remembered one", remembered.getName());
    }

    @Test
    public void theSignatureChangesWhenAnythingVisibleChanges() throws Exception {
        directory.setManual(Collections.singletonList(new FCastDirectory.Known(endpoint("192.168.0.20"), "Typed one")));
        String first = FCastReceiverRows.signature(FCastReceiverRows.of(directory.snapshot()));
        assertEquals(first, FCastReceiverRows.signature(FCastReceiverRows.of(directory.snapshot())));

        directory.serviceFound("_fcast._tcp|Typed", "Typed one", InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 0, 20}), 46899, null);

        assertFalse("announced now", first.equals(FCastReceiverRows.signature(FCastReceiverRows.of(directory.snapshot()))));
    }

    private static FCastReceiverRows.Row byEndpoint(List<FCastReceiverRows.Row> rows, String endpoint) {
        for (FCastReceiverRows.Row row : rows) {
            if (row.getEndpointText().equals(endpoint)) {
                return row;
            }
        }
        throw new AssertionError("no row for " + endpoint);
    }
}
