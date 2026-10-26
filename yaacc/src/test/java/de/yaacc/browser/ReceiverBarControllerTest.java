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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.DeviceDetails;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;

/**
 * The receiver bar's step from players to names ({@link ReceiverBarController#receiverNames}) and
 * the controller's use of it: the bar shows the receivers that have a player in the client's list
 * (one name per receiver, the player's own name when its device is unknown), else the
 * "no receiver playing" text, and the label built from the names. Mocked client and view.
 */
public class ReceiverBarControllerTest {

    private static final String NONE = "No receiver playing";

    private static Player player(String deviceId, String name) {
        Player player = mock(Player.class);
        when(player.getDeviceId()).thenReturn(deviceId);
        when(player.getName()).thenReturn(name);
        return player;
    }

    // ---- the pure step

    @Test
    public void twoPlayersOnOneReceiverGiveTwoEntriesTheLabelMergesLater() {
        List<String> names = ReceiverBarController.receiverNames(
                Arrays.asList(player("tv", "Session 1"), player("tv", "Session 2")),
                id -> "tv".equals(id) ? "Living room TV" : null);

        assertEquals(Arrays.asList("Living room TV", "Living room TV"), names);
        assertEquals("Living room TV", ReceiverBarController.label(names, NONE));
    }

    @Test
    public void aPlayerWhoseDeviceIsUnknownFallsBackToThePlayersOwnName() {
        List<String> names = ReceiverBarController.receiverNames(
                Collections.singletonList(player("gone", "Kitchen speaker")), id -> null);

        assertEquals(Collections.singletonList("Kitchen speaker"), names);
    }

    @Test
    public void theNamesAreCleanedBecauseTheyComeFromTheLan() {
        List<String> names = ReceiverBarController.receiverNames(
                Collections.singletonList(player("tv", "x")), id -> "TV\n‮fake");

        assertEquals(Collections.singletonList("TV fake"), names);
    }

    @Test
    public void aNullPlayerIsSkippedAndNoPlayersGiveNoNames() {
        assertEquals(Collections.singletonList("A"), ReceiverBarController.receiverNames(
                Arrays.asList(null, player("a", "A")), id -> null));
        assertTrue(ReceiverBarController.receiverNames(Collections.<Player>emptyList(), id -> "x").isEmpty());
    }

    // ---- the label (the cleaned, de-duplicated, sorted names; was ReceiverBarLabelTest)

    @Test
    public void noPlayingReceiversShowsTheFallbackText() {
        assertEquals(NONE, ReceiverBarController.label(Collections.emptyList(), NONE));
        assertEquals(NONE, ReceiverBarController.label(null, NONE));
    }

    @Test
    public void singleReceiverShowsItsName() {
        assertEquals("Living room TV",
                ReceiverBarController.label(Collections.singletonList("Living room TV"), NONE));
    }

    @Test
    public void severalReceiversAreSortedCaseInsensitivelyAndJoined() {
        assertEquals("kitchen; Living room TV; this device",
                ReceiverBarController.label(Arrays.asList("this device", "Living room TV", "kitchen"), NONE));
    }

    @Test
    public void orderDoesNotDependOnInputOrder() {
        assertEquals(
                ReceiverBarController.label(Arrays.asList("b", "A", "a", "B"), NONE),
                ReceiverBarController.label(Arrays.asList("B", "a", "A", "b"), NONE));
    }

    @Test
    public void blankAndNullNamesAreSkippedAndNamesTrimmed() {
        assertEquals("TV", ReceiverBarController.label(Arrays.asList(null, "  ", " TV "), NONE));
        assertEquals(NONE, ReceiverBarController.label(Arrays.asList(null, ""), NONE));
    }

    @Test
    public void severalPlayersOnOneReceiverShowTheReceiverOnce() {
        assertEquals("TV", ReceiverBarController.label(Arrays.asList("TV", "TV", " TV "), NONE));
        assertEquals("Speaker; TV",
                ReceiverBarController.label(Arrays.asList("TV", "Speaker", "TV"), NONE));
    }

    @Test
    public void namesThatDifferOnlyInCaseAreDifferentReceivers() {
        assertEquals("TV; tv", ReceiverBarController.label(Arrays.asList("tv", "TV"), NONE));
    }

    @Test
    public void namesAreCleanedLikeEveryOtherLanText() {
        // line break, tab, bidi override and zero-width characters are removed or folded
        assertEquals("Living room TV",
                ReceiverBarController.label(Collections.singletonList("Living\n\troom \u202E\u200BTV"), NONE));
    }

    @Test
    public void aNameThatCleansToNothingIsSkipped() {
        assertEquals(NONE, ReceiverBarController.label(Arrays.asList("\u202E", "\u200B\n"), NONE));
        assertEquals("TV", ReceiverBarController.label(Arrays.asList("\u202E", "TV"), NONE));
    }

    @Test
    public void namesThatAreEqualAfterCleaningAreMerged() {
        assertEquals("TV", ReceiverBarController.label(Arrays.asList("TV", "T\u200BV", "TV\n"), NONE));
    }

    @Test
    public void aVeryLongNameIsCapped() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longName.append('x');
        }
        String label = ReceiverBarController.label(Collections.singletonList(longName.toString()), NONE);
        assertEquals(MiniPlayerPresenter.MAX_TEXT_LENGTH, label.length());
    }

    @Test
    public void markupInDeviceNamesIsPassedThroughVerbatim() {
        // Rendered as plain TextView text; the label must not alter or interpret it.
        assertEquals("<b>%s</b>", ReceiverBarController.label(Collections.singletonList("<b>%s</b>"), NONE));
    }

    @Test
    public void theFallbackTextIsReturnedUnchanged() {
        assertEquals("  %s <i> ", ReceiverBarController.label(null, "  %s <i> "));
    }

    // ---- the controller

    private TextView bar;
    private UpnpClient client;
    private ReceiverBarController controller;

    @Before
    public void setUp() {
        bar = mock(TextView.class);
        Context context = mock(Context.class);
        when(context.getString(anyInt())).thenReturn(NONE);
        when(bar.getContext()).thenReturn(context);
        when(bar.getCompoundDrawablesRelative()).thenReturn(new Drawable[4]);
        client = mock(UpnpClient.class);
        controller = new ReceiverBarController(bar, client, () -> {
        });
    }

    private String shownText() {
        ArgumentCaptor<CharSequence> text = ArgumentCaptor.forClass(CharSequence.class);
        verify(bar, atLeastOnce()).setText(text.capture());
        List<CharSequence> all = text.getAllValues();
        return all.get(all.size() - 1).toString();
    }

    @Test
    public void theBarShowsTheReceiversThatHavePlayers() {
        Device<?, ?, ?> tv = mock(Device.class);
        when(tv.getDetails()).thenReturn(new DeviceDetails("Living room TV"));
        when(client.getDevice("tv")).thenReturn((Device) tv);
        List<Player> players = new ArrayList<>(Arrays.asList(player("tv", "p1"), player("tv", "p2"), player("phone", "This device")));
        when(client.getCurrentPlayers()).thenReturn(players);

        controller.bind();

        assertEquals("Living room TV; This device", shownText());
    }

    @Test
    public void theBarShowsTheNoneText() {
        when(client.getCurrentPlayers()).thenReturn(new ArrayList<Player>());

        controller.bind();

        assertEquals(NONE, shownText());
    }

    @Test
    public void aPlayerEventRefreshesTheBar() {
        List<Player> players = new ArrayList<>();
        when(client.getCurrentPlayers()).thenReturn(players);
        controller.bind();
        players.add(player("phone", "This device"));

        controller.onPlayerAdded(players.get(0));

        assertEquals("This device", shownText());
    }

    @Test
    public void afterUnbindTheBarIsNotTouchedAnyMore() {
        when(client.getCurrentPlayers()).thenReturn(new ArrayList<Player>());
        controller.bind();
        controller.unbind();
        org.mockito.Mockito.clearInvocations(bar);

        controller.update();

        verify(bar, org.mockito.Mockito.never()).setText(org.mockito.ArgumentMatchers.<CharSequence>any());
    }
}
