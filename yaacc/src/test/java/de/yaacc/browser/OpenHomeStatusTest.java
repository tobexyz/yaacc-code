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
import static org.junit.Assert.assertNull;

import org.junit.Test;

import de.yaacc.openhome.OhTransportState;

/** The status line of an OpenHome device in the receiver row. */
public class OpenHomeStatusTest {

    @Test
    public void theTransportStatesAreMappedToTheOnesOfTheRow() {
        assertEquals(RendererStatus.State.PLAYING, new RendererStatus(null, OpenHomeStatus.upnpState(OhTransportState.PLAYING), null).getState());
        assertEquals(RendererStatus.State.PLAYING, new RendererStatus(null, OpenHomeStatus.upnpState(OhTransportState.BUFFERING), null).getState());
        assertEquals(RendererStatus.State.PAUSED, new RendererStatus(null, OpenHomeStatus.upnpState(OhTransportState.PAUSED), null).getState());
        assertEquals(RendererStatus.State.STOPPED, new RendererStatus(null, OpenHomeStatus.upnpState(OhTransportState.STOPPED), null).getState());
        assertEquals(RendererStatus.State.NO_MEDIA, new RendererStatus(null, OpenHomeStatus.upnpState(null), null).getState());
    }

    private static String didl(String title) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"1\" parentID=\"0\" restricted=\"1\">"
                + "<dc:title>" + title + "</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class></item></DIDL-Lite>";
    }

    @Test
    public void theTitleOfTheTrackIsShown() {
        assertEquals("Blue Train", OpenHomeStatus.trackTitle(didl("Blue Train"), "http://h/a.mp3"));
    }

    @Test
    public void withoutATitleTheNameInTheAddressIsShown() {
        assertEquals("a.mp3", OpenHomeStatus.trackTitle("", "http://h/music/a.mp3?token=1"));
        assertEquals("a.mp3", OpenHomeStatus.trackTitle("not xml at all", "http://h/music/a.mp3"));
        assertEquals("a.mp3", OpenHomeStatus.trackTitle(didl(" "), "http://h/music/a.mp3"));
    }

    @Test
    public void nothingPlayingGivesNoTitle() {
        assertNull(OpenHomeStatus.trackTitle("", ""));
        assertNull(OpenHomeStatus.trackTitle(null, null));
        assertNull(OpenHomeStatus.trackTitle("", "http://h/"));
    }

    @Test
    public void controlCharactersOfTheDeviceAreRemoved() {
        String title = OpenHomeStatus.trackTitle(didl("Line\ntwo"), "");
        org.junit.Assert.assertTrue(title, title.startsWith("Line") && title.endsWith("two") && !title.contains("\n"));
    }
}
