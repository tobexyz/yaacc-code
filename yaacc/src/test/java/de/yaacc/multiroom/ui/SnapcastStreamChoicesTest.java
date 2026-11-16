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
package de.yaacc.multiroom.ui;

import static de.yaacc.multiroom.ui.UiFixtures.client;
import static de.yaacc.multiroom.ui.UiFixtures.group;
import static de.yaacc.multiroom.ui.UiFixtures.status;
import static de.yaacc.multiroom.ui.UiFixtures.stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;

/**
 * The streams of a server for the picker of a group: cleaned names, the current one marked, YAACC's own feed stream
 * marked, and a reason for a stream that can not be chosen. Plain JVM.
 */
public class SnapcastStreamChoicesTest {

    private static final String MARKER = "ab12cd34";
    private static final SnapcastGroup GROUP = group("g1", "Living", "Default", client("a", "Kitchen", true, 50, false));

    private static SnapcastServerStatus server(String... idsAndStatuses) {
        de.yaacc.multiroom.control.SnapcastStream[] streams = new de.yaacc.multiroom.control.SnapcastStream[idsAndStatuses.length / 2];
        for (int i = 0; i < streams.length; i++) {
            streams[i] = stream(idsAndStatuses[2 * i], idsAndStatuses[2 * i + 1]);
        }
        return status(GROUP, streams);
    }

    @Test
    public void everyStreamOfTheServerIsAChoiceInTheOrderOfTheServer() {
        List<SnapcastStreamChoices.Choice> choices = SnapcastStreamChoices.of(
                server("Default", "playing", "Radio", "idle", "Line in", "idle"), "g1", MARKER);

        assertEquals(3, choices.size());
        assertEquals("Default", choices.get(0).getLabel());
        assertEquals("Radio", choices.get(1).getLabel());
        assertEquals("Line in", choices.get(2).getLabel());
        assertEquals("Radio", choices.get(1).getStreamId());
    }

    @Test
    public void theCurrentStreamIsMarkedAndNeedsNoChange() {
        List<SnapcastStreamChoices.Choice> choices = SnapcastStreamChoices.of(
                server("Default", "playing", "Radio", "idle"), "g1", MARKER);

        assertTrue(choices.get(0).isCurrent());
        assertFalse(choices.get(0).isSelectable());
        assertEquals(SnapcastStreamChoices.Reason.CURRENT, choices.get(0).getReason());
        assertFalse(choices.get(1).isCurrent());
        assertTrue(choices.get(1).isSelectable());
        assertEquals(SnapcastStreamChoices.Reason.NONE, choices.get(1).getReason());
    }

    @Test
    public void aDisabledStreamCannotBeChosen() {
        List<SnapcastStreamChoices.Choice> choices = SnapcastStreamChoices.of(
                server("Default", "playing", "Radio", "disabled"), "g1", MARKER);

        assertFalse(choices.get(1).isSelectable());
        assertEquals(SnapcastStreamChoices.Reason.UNAVAILABLE, choices.get(1).getReason());
    }

    @Test
    public void anIdleAndAnUnknownStreamCanBeChosen() {
        List<SnapcastStreamChoices.Choice> choices = SnapcastStreamChoices.of(
                server("Default", "playing", "Radio", "idle", "New", "unknown"), "g1", MARKER);

        assertTrue(choices.get(1).isSelectable());
        assertTrue(choices.get(2).isSelectable());
    }

    @Test
    public void theFeedStreamOfThisPhoneIsMarked() {
        List<SnapcastStreamChoices.Choice> choices = SnapcastStreamChoices.of(
                server("Default", "playing", "YAACC Pixel " + MARKER, "idle", "YAACC Other 99999999", "idle"), "g1", MARKER);

        assertFalse(choices.get(0).isOwnFeed());
        assertTrue(choices.get(1).isOwnFeed());
        assertFalse("another phone's feed is not ours", choices.get(2).isOwnFeed());
    }

    @Test
    public void noMarkerMeansNoStreamIsOurs() {
        List<SnapcastStreamChoices.Choice> choices = SnapcastStreamChoices.of(
                server("YAACC Pixel " + MARKER, "idle"), "g1", null);

        assertFalse(choices.get(0).isOwnFeed());
    }

    @Test
    public void aStreamThatMerelyEndsLikeOursButIsNotAYaaccStreamIsNotMarked() {
        assertFalse(SnapcastStreamChoices.isOwnFeed("Pixel " + MARKER, MARKER));
        assertFalse(SnapcastStreamChoices.isOwnFeed("YAACC " + MARKER + "x", MARKER));
        assertFalse(SnapcastStreamChoices.isOwnFeed("YAACC Pixel" + MARKER, MARKER));
        assertTrue(SnapcastStreamChoices.isOwnFeed("YAACC Pixel " + MARKER, MARKER));
        assertFalse(SnapcastStreamChoices.isOwnFeed(null, MARKER));
        assertFalse(SnapcastStreamChoices.isOwnFeed("YAACC Pixel " + MARKER, ""));
    }

    @Test
    public void namesAreCleanedButTheIdForTheCallIsKept() {
        List<SnapcastStreamChoices.Choice> choices = SnapcastStreamChoices.of(
                server("Default", "playing", "Radio\u202E\u200B", "idle"), "g1", MARKER);

        assertEquals("Radio??", choices.get(1).getLabel());
        assertEquals("the call needs the server's own id", "Radio\u202E\u200B", choices.get(1).getStreamId());
    }

    @Test
    public void twoStreamsThatLookAlikeAreToldApart() {
        List<SnapcastStreamChoices.Choice> choices = SnapcastStreamChoices.of(
                server("Radio", "idle", "Radio\u200B", "idle"), "g1", MARKER);

        assertEquals("Radio", choices.get(0).getLabel());
        assertEquals("Radio?", choices.get(1).getLabel());
    }

    @Test
    public void streamsWithTheSameCleanedNameAreNumbered() {
        List<SnapcastStreamChoices.Choice> choices = SnapcastStreamChoices.of(
                server("Radio\u200B", "idle", "Radio\u200C", "idle"), "g1", MARKER);

        assertEquals("Radio?", choices.get(0).getLabel());
        assertEquals("Radio? (2)", choices.get(1).getLabel());
    }

    @Test
    public void aGroupWithoutAStreamHasNoCurrentOne() {
        SnapcastGroup bare = group("g2", "Bare", "", client("b", "Hall", true, 1, false));
        SnapcastServerStatus status = status(bare, stream("Default", "idle"));

        List<SnapcastStreamChoices.Choice> choices = SnapcastStreamChoices.of(status, "g2", MARKER);

        assertFalse(choices.get(0).isCurrent());
        assertTrue(choices.get(0).isSelectable());
    }

    @Test
    public void anUnknownGroupOrNoStatusOffersNothing() {
        assertTrue(SnapcastStreamChoices.of(server("Default", "idle"), "nope", MARKER).isEmpty());
        assertTrue(SnapcastStreamChoices.of(null, "g1", MARKER).isEmpty());
    }

    @Test
    public void aServerWithoutStreamsOffersNothing() {
        assertTrue(SnapcastStreamChoices.of(status(GROUP), "g1", MARKER).isEmpty());
    }
}
