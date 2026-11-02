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
package de.yaacc.upnp.server.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.lastchange.EventedValue;
import org.fourthline.cling.support.model.PlayMode;
import org.fourthline.cling.support.model.TransportState;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class ObservableLastChangeTest {

    private static final UnsignedIntegerFourBytes ID = new UnsignedIntegerFourBytes(0);

    private static ObservableLastChange lastChange(AtomicInteger calls) {
        ObservableLastChange lastChange = new ObservableLastChange(new AVTransportLastChangeParser());
        lastChange.setChangeListener(calls::incrementAndGet);
        return lastChange;
    }

    @Test
    public void theLastChangeTellsItsListenerAboutEveryValueButNotAboutNothing() {
        AtomicInteger calls = new AtomicInteger();
        ObservableLastChange lastChange = lastChange(calls);

        lastChange.setEventedValue(ID, new AVTransportVariable.CurrentPlayMode(PlayMode.NORMAL));
        lastChange.setEventedValue(0, new AVTransportVariable.CurrentPlayMode(PlayMode.SHUFFLE));
        lastChange.setEventedValue(ID, (EventedValue) null);
        lastChange.setEventedValue(ID);

        assertEquals(2, calls.get());
        assertEquals(PlayMode.SHUFFLE, lastChange.getEventedValue(ID, AVTransportVariable.CurrentPlayMode.class).getValue());
    }

    @Test
    public void aListenerThatThrowsDoesNotBreakTheChange() {
        ObservableLastChange lastChange = new ObservableLastChange(new AVTransportLastChangeParser());
        lastChange.setChangeListener(() -> {
            throw new IllegalStateException("boom");
        });

        lastChange.setEventedValue(ID, new AVTransportVariable.CurrentPlayMode(PlayMode.RANDOM));

        assertEquals(PlayMode.RANDOM, lastChange.getEventedValue(ID, AVTransportVariable.CurrentPlayMode.class).getValue());
    }

    @Test
    public void aValueThatEqualsTheOnePutLastIsNoChange() {
        AtomicInteger calls = new AtomicInteger();
        ObservableLastChange lastChange = lastChange(calls);
        lastChange.setEventedValue(ID, new AVTransportVariable.TransportState(TransportState.STOPPED));
        lastChange.reset(); // the event was sent

        lastChange.setEventedValue(ID, new AVTransportVariable.TransportState(TransportState.STOPPED));
        lastChange.setEventedValue(ID, new AVTransportVariable.TransportState(TransportState.STOPPED),
                new AVTransportVariable.CurrentPlayMode(PlayMode.NORMAL));

        assertEquals("only the play mode is new", 2, calls.get());
        assertNull(lastChange.getEventedValue(ID, AVTransportVariable.TransportState.class));
        assertEquals(PlayMode.NORMAL, lastChange.getEventedValue(ID, AVTransportVariable.CurrentPlayMode.class).getValue());
    }

    @Test
    public void aValueThatChangedBackIsAChange() {
        AtomicInteger calls = new AtomicInteger();
        ObservableLastChange lastChange = lastChange(calls);

        lastChange.setEventedValue(ID, new AVTransportVariable.TransportState(TransportState.PLAYING));
        lastChange.setEventedValue(ID, new AVTransportVariable.TransportState(TransportState.PAUSED_PLAYBACK));
        lastChange.reset();
        lastChange.setEventedValue(ID, new AVTransportVariable.TransportState(TransportState.PLAYING));

        assertEquals(3, calls.get());
        assertTrue(lastChange.toString(), lastChange.toString().contains("PLAYING"));
    }

    @Test
    public void theSameValueForAnotherInstanceIsAChange() {
        AtomicInteger calls = new AtomicInteger();
        ObservableLastChange lastChange = lastChange(calls);

        lastChange.setEventedValue(ID, new AVTransportVariable.TransportState(TransportState.PLAYING));
        lastChange.setEventedValue(1, new AVTransportVariable.TransportState(TransportState.PLAYING));

        assertEquals(2, calls.get());
    }

    @Test
    public void afterForgettingWhatWasPutTheSameValueIsAChangeAgain() {
        AtomicInteger calls = new AtomicInteger();
        ObservableLastChange lastChange = lastChange(calls);
        lastChange.setEventedValue(ID, new AVTransportVariable.TransportState(TransportState.PLAYING));
        lastChange.reset(); // dropped, never sent

        lastChange.forgetPutValues();
        lastChange.setEventedValue(ID, new AVTransportVariable.TransportState(TransportState.PLAYING));

        assertEquals(2, calls.get());
    }
}
