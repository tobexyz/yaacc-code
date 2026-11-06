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

import org.junit.Test;

import java.util.List;

import de.yaacc.openhome.OhSources;

/** The list a user sees when switching the source of an OpenHome device: hidden sources are left out, names are cleaned. */
public class OpenHomeSourceDialogTest {

    private static List<OpenHomeSourceDialog.Choice> choices(String xml) {
        return OpenHomeSourceDialog.choices(OhSources.parse(xml));
    }

    @Test
    public void hiddenSourcesAreLeftOutAndTheIndexIsThePositionInTheDevicesList() {
        List<OpenHomeSourceDialog.Choice> choices = choices("<SourceList>"
                + "<Source><Name>Playlist</Name><Type>Playlist</Type><Visible>true</Visible></Source>"
                + "<Source><Name>Hidden</Name><Type>Radio</Type><Visible>false</Visible></Source>"
                + "<Source><Name>Radio</Name><Type>Radio</Type><Visible>true</Visible></Source></SourceList>");

        assertEquals(2, choices.size());
        assertEquals("Playlist", choices.get(0).label);
        assertEquals(0, choices.get(0).index);
        assertEquals("Radio", choices.get(1).label);
        assertEquals(2, choices.get(1).index);
    }

    @Test
    public void aSourceWithoutNameIsShownByItsTypeOrItsNumber() {
        List<OpenHomeSourceDialog.Choice> choices = choices("<SourceList>"
                + "<Source><Name></Name><Type>Radio</Type><Visible>true</Visible></Source>"
                + "<Source><Name></Name><Type></Type><Visible>true</Visible></Source></SourceList>");

        assertEquals("Radio", choices.get(0).label);
        assertEquals("#2", choices.get(1).label);
    }

    @Test
    public void aLongNameIsShortened() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            name.append('x');
        }

        List<OpenHomeSourceDialog.Choice> choices = choices("<SourceList><Source><Name>" + name
                + "</Name><Type>Radio</Type><Visible>true</Visible></Source></SourceList>");

        assertEquals(60, choices.get(0).label.length());
    }

    @Test
    public void textThatIsNotASourceListGivesNoChoices() {
        assertEquals(0, choices("not xml").size());
        assertEquals(0, choices("<Other/>").size());
    }
}
