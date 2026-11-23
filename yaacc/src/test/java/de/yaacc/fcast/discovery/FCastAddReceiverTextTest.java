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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;

/** What the add input says for each outcome, and what a failed name says it tried. */
public class FCastAddReceiverTextTest {

    private final List<String> calls = new ArrayList<>();
    private boolean running = true;
    private final FCastAddReceiver add = new FCastAddReceiver(new FCastAddReceiver.Environment() {
        @Override
        public List<FCastDirectory.Entry> receivers() {
            return new ArrayList<>();
        }

        @Override
        public boolean answers(FCastEndpoint endpoint) {
            return false;
        }

        @Override
        public java.net.InetAddress lookup(String host) throws java.io.IOException {
            throw new java.io.IOException("unknown");
        }

        @Override
        public FCastStoredReceivers.AddResult store(FCastEndpoint endpoint, String name) {
            return FCastStoredReceivers.AddResult.ADDED;
        }

        @Override
        public boolean isDiscoveryRunning() {
            return running;
        }

        @Override
        public void pause(long millis) {
        }
    });

    private static List<Integer> ids(List<FCastAddReceiverText.Line> lines) {
        List<Integer> ids = new ArrayList<>();
        for (FCastAddReceiverText.Line line : lines) {
            ids.add(line.getResId());
        }
        return ids;
    }

    @Test
    public void anAddedReceiverNeedsNoMessage() {
        assertTrue(FCastAddReceiverText.lines(add.add("192.168.0.2")).isEmpty());
    }

    @Test
    public void everyOtherOutcomeHasItsOwnLine() {
        assertEquals(java.util.Collections.singletonList(R.string.fcast_receivers_error_empty), ids(FCastAddReceiverText.lines(add.add(" "))));
        assertEquals(java.util.Collections.singletonList(R.string.fcast_receivers_error_invalid), ids(FCastAddReceiverText.lines(add.add("http://x/y"))));
        assertEquals(java.util.Collections.singletonList(R.string.fcast_receivers_error_bad_link),
                ids(FCastAddReceiverText.lines(add.add(FCastConnectionUrl.PREFIX + "!!"))));
    }

    @Test
    public void aLinkLocalAddressHasItsOwnLine() {
        assertEquals(java.util.Collections.singletonList(R.string.fcast_receivers_error_link_local), ids(FCastAddReceiverText.lines(add.add("fe80::1"))));
    }

    @Test
    public void aLinkWithoutAddressOrServiceSaysWhichIsMissing() {
        String noAddress = FCastConnectionUrl.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"addresses\":[\"8.8.8.8\"],\"services\":[{\"port\":1,\"type\":0}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String noService = FCastConnectionUrl.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"addresses\":[\"192.168.0.2\"],\"services\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(java.util.Collections.singletonList(R.string.fcast_receivers_error_link_no_address), ids(FCastAddReceiverText.lines(add.add(noAddress))));
        assertEquals(java.util.Collections.singletonList(R.string.fcast_receivers_error_link_no_service), ids(FCastAddReceiverText.lines(add.add(noService))));
    }

    @Test
    public void aLinkWhoseAddressesDoNotAnswerNamesTheNumber() {
        String link = FCastConnectionUrl.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"addresses\":[\"192.168.0.2\",\"192.168.0.3\"],\"services\":[{\"port\":46899,\"type\":0}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<FCastAddReceiverText.Line> lines = FCastAddReceiverText.lines(add.add(link));

        assertEquals(1, lines.size());
        assertEquals(R.string.fcast_receivers_error_unreachable, lines.get(0).getResId());
        assertEquals(2, lines.get(0).getArgs()[0]);
    }

    @Test
    public void aNameThatWasNotFoundSaysWhatWasTried() {
        List<Integer> ids = ids(FCastAddReceiverText.lines(add.add("nowhere")));

        assertEquals(java.util.Arrays.asList(R.string.fcast_receivers_error_not_found, R.string.fcast_receivers_tried_found,
                R.string.fcast_receivers_tried_system, R.string.fcast_receivers_tried_search), ids);
    }

    @Test
    public void withDiscoveryOffTheFailureSaysSo() {
        running = false;

        List<Integer> ids = ids(FCastAddReceiverText.lines(add.add("Living room TV")));

        assertEquals(java.util.Arrays.asList(R.string.fcast_receivers_error_not_found, R.string.fcast_receivers_tried_found,
                R.string.fcast_receivers_tried_search_off), ids);
    }
}
