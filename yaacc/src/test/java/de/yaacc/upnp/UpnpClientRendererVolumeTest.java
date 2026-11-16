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
package de.yaacc.upnp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import org.fourthline.cling.model.meta.LocalDevice;
import org.junit.Before;
import org.junit.Test;

import de.yaacc.upnp.server.renderingcontrol.RendererVolume;
import de.yaacc.upnp.server.renderingcontrol.SystemVolume;

/**
 * The volume rows of the app (Fix Group 9b S7): the row of YAACC's own renderer (the cling {@code LocalDevice}) follows the
 * output of the renderer, so that in group mode its slider and mute are the group's and not a phone volume nobody hears; the
 * row of "this phone" (the dummy device that plays locally) always is the phone's volume. Plain JVM, a spy over a client
 * without a context.
 */
public class UpnpClientRendererVolumeTest {

    private UpnpClient client;
    private RendererVolume renderer;
    private LocalDevice ownRenderer;
    private UpnpClient.LocalDummyDevice thisPhone;

    @Before
    public void setUp() {
        client = spy(new UpnpClient());
        renderer = mock(RendererVolume.class);
        doReturn(renderer).when(client).rendererVolume();
        ownRenderer = mock(LocalDevice.class);
        thisPhone = mock(UpnpClient.LocalDummyDevice.class);
    }

    @Test
    public void theRowOfTheOwnRendererIsRoutedByTheOutput() {
        assertSame(renderer, client.volumeOf(ownRenderer));
    }

    @Test
    public void theRowOfThisPhoneIsAlwaysTheSystemVolume() {
        RendererVolume volume = client.volumeOf(thisPhone);

        assertNotSame(renderer, volume);
        assertTrue(volume instanceof SystemVolume);
    }

    @Test
    public void everyActionOfTheOwnRendererRowGoesToTheRoutedVolumeAndNoFurther() {
        org.mockito.Mockito.when(renderer.getPercent()).thenReturn(61);
        org.mockito.Mockito.when(renderer.isMuted()).thenReturn(true);

        assertEquals(61, client.getVolume(ownRenderer));
        assertTrue(client.getMute(ownRenderer));
        client.setVolume(ownRenderer, 30);
        client.setMute(ownRenderer, false);

        verify(renderer).setPercent(30);
        verify(renderer).setMuted(false);
        // no call of the renderer's own service over the network afterwards: the routed volume is the whole path
        verify(client, never()).getRenderingControlService(any());
    }

    @Test
    public void theRoutedVolumeIsNotTheRowOfThisPhone() {
        assertFalse(client.volumeOf(thisPhone) == client.volumeOf(ownRenderer));
    }
}
