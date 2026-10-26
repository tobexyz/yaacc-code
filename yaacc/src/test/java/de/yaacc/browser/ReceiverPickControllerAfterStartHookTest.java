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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import de.yaacc.upnp.UpnpClient;

/**
 * {@link ReceiverPickController#afterStartHook}: what runs once a picked action has been started.
 * The new layout clears the selection; classic puts back the selection that was there when the pick
 * was CONFIRMED (read when the hook is made, not when it runs) and never clears. Mocked client.
 */
public class ReceiverPickControllerAfterStartHookTest {

    private UpnpClient client;

    @Before
    public void setUp() {
        client = mock(UpnpClient.class);
        when(client.getReceiverDeviceIds()).thenReturn(Set.of("classic-tv"));
    }

    @Test
    public void theNewLayoutClearsTheSelectionWhenTheHookRuns() {
        Runnable hook = ReceiverPickController.afterStartHook(client, true);

        verify(client, never()).clearReceiverDevices();
        hook.run();

        verify(client).clearReceiverDevices();
        verify(client, never()).setReceiverDeviceIds(any());
    }

    @Test
    public void theNewLayoutNeverRestoresAnything() {
        ReceiverPickController.afterStartHook(client, true).run();

        verify(client, never()).setReceiverDeviceIds(any());
    }

    @Test
    public void classicRestoresThePreviousSelectionWhenTheHookRuns() {
        Runnable hook = ReceiverPickController.afterStartHook(client, false);

        verify(client, never()).setReceiverDeviceIds(any());
        hook.run();

        ArgumentCaptor<Set<String>> restored = ArgumentCaptor.forClass(Set.class);
        verify(client).setReceiverDeviceIds(restored.capture());
        assertEquals(Set.of("classic-tv"), restored.getValue());
        verify(client, never()).clearReceiverDevices();
    }

    @Test
    public void classicRestoresWhatWasSelectedAtConfirmTimeNotAtHookTime() {
        Runnable hook = ReceiverPickController.afterStartHook(client, false);
        // the playback itself stored the picked receivers meanwhile
        when(client.getReceiverDeviceIds()).thenReturn(Set.of("picked-phone"));

        hook.run();

        ArgumentCaptor<Set<String>> restored = ArgumentCaptor.forClass(Set.class);
        verify(client).setReceiverDeviceIds(restored.capture());
        assertTrue(restored.getValue().contains("classic-tv"));
        assertEquals(1, restored.getValue().size());
    }

    @Test
    public void theHookDoesNothingUntilItRuns() {
        ReceiverPickController.afterStartHook(client, false);
        ReceiverPickController.afterStartHook(client, true);

        verify(client, never()).clearReceiverDevices();
        verify(client, never()).setReceiverDeviceIds(any());
    }
}
