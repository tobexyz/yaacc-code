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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link PickSheetResult}: the sheet's own gate that makes "exactly one answer"
 * true. The sheet reports a confirm when the confirm button is pressed with at least one receiver
 * and a cancel from {@code onDismiss} (every way of closing it ends there); a confirm followed by
 * its own dismiss must not report a cancel, and nothing may be reported twice. Plain JVM.
 */
public class PickSheetResultTest {

    private PickSheetResult gate;

    @Before
    public void setUp() {
        gate = new PickSheetResult();
    }

    private static Set<String> ids(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    @Test
    public void confirmWithAReceiverSendsAConfirmWithThoseIds() {
        assertEquals(PickSheetResult.Action.SEND_CONFIRM, gate.confirm(ids("a", "b")));

        assertEquals(ids("a", "b"), gate.confirmedIds());
        assertTrue(gate.isDelivered());
    }

    @Test
    public void confirmWithoutAReceiverShowsTheHintAndSendsNothing() {
        assertEquals(PickSheetResult.Action.SHOW_HINT, gate.confirm(Collections.<String>emptySet()));
        assertEquals(PickSheetResult.Action.SHOW_HINT, gate.confirm(null));

        assertFalse(gate.isDelivered());
        assertTrue(gate.confirmedIds().isEmpty());
    }

    @Test
    public void afterTheHintTheUserCanStillConfirm() {
        gate.confirm(Collections.<String>emptySet());

        assertEquals(PickSheetResult.Action.SEND_CONFIRM, gate.confirm(ids("a")));
    }

    @Test
    public void theDismissThatFollowsAConfirmSendsNoCancel() {
        gate.confirm(ids("a"));

        assertEquals(PickSheetResult.Action.NONE, gate.dismissed());
    }

    @Test
    public void aSecondConfirmSendsNothing() {
        gate.confirm(ids("a"));

        assertEquals(PickSheetResult.Action.NONE, gate.confirm(ids("a")));
        assertEquals(PickSheetResult.Action.NONE, gate.confirm(ids("b")));
        assertEquals(ids("a"), gate.confirmedIds());
    }

    @Test
    public void aDismissWithoutAConfirmSendsTheCancel() {
        assertEquals(PickSheetResult.Action.SEND_CANCEL, gate.dismissed());

        assertTrue(gate.isDelivered());
    }

    @Test
    public void aDismissAfterTheHintSendsTheCancel() {
        gate.confirm(Collections.<String>emptySet());

        assertEquals(PickSheetResult.Action.SEND_CANCEL, gate.dismissed());
    }

    @Test
    public void aSecondDismissSendsNothing() {
        gate.dismissed();

        assertEquals(PickSheetResult.Action.NONE, gate.dismissed());
    }

    @Test
    public void aConfirmAfterTheCancelSendsNothing() {
        gate.dismissed();

        assertEquals(PickSheetResult.Action.NONE, gate.confirm(ids("a")));
        assertTrue(gate.confirmedIds().isEmpty());
    }

    @Test
    public void aSilencedSheetReportsNothingAtAll() {
        // a sheet restored without a pick behind it (process death, rotation)
        gate.silence();

        assertEquals(PickSheetResult.Action.NONE, gate.dismissed());
        assertEquals(PickSheetResult.Action.NONE, gate.confirm(ids("a")));
        assertTrue(gate.isDelivered());
    }

    @Test
    public void nullAndEmptyIdsAreNotCounted() {
        Set<String> given = new LinkedHashSet<>();
        given.add(null);
        given.add("");

        assertEquals(PickSheetResult.Action.SHOW_HINT, gate.confirm(given));
    }

    @Test
    public void theConfirmedIdsAreACopy() {
        Set<String> given = ids("a");
        gate.confirm(given);

        given.add("late");

        assertEquals(ids("a"), gate.confirmedIds());
    }
}
