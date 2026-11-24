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
package de.yaacc.sendspin.hub;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class GroupFormatTest {

    private static Set<Integer> rates(Integer... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    @Test
    public void theCommonRateIsOneThatAllMembersListAnd48000IsPreferred() {
        List<Set<Integer>> members = Arrays.asList(rates(44100, 48000, 96000), rates(48000, 44100), rates(44100, 48000));
        assertEquals("48000 is preferred", 48000, GroupFormat.commonRate(members));
    }

    @Test
    public void theRate44100IsTheSecondChoice() {
        assertEquals(44100, GroupFormat.commonRate(Arrays.asList(rates(44100, 96000), rates(44100, 48000))));
    }

    @Test
    public void anotherCommonRateIsTheHighestThatEverybodyHas() {
        assertEquals(96000, GroupFormat.commonRate(Arrays.asList(rates(22050, 96000, 32000), rates(96000, 32000, 22050))));
    }

    @Test
    public void noCommonRateGivesZero() {
        assertEquals(0, GroupFormat.commonRate(Arrays.asList(rates(44100), rates(48000))));
        assertEquals(0, GroupFormat.commonRate(Collections.<Set<Integer>>emptyList()));
        assertEquals(0, GroupFormat.commonRate(Arrays.asList(rates(44100), rates())));
    }

    @Test
    public void aSingleMemberKeepsItsOwnFirstChoiceWhenItHasNeitherOfTheUsualRates() {
        assertEquals(96000, GroupFormat.commonRate(Collections.singletonList(rates(96000))));
    }

    @Test
    public void ratesThatCanNotBeCutIntoChunksAreIgnored() {
        assertEquals(0, GroupFormat.commonRate(Collections.singletonList(rates(44123, 7000, 400000))));
        assertEquals(48000, GroupFormat.commonRate(Collections.singletonList(rates(44123, 48000))));
    }
}
