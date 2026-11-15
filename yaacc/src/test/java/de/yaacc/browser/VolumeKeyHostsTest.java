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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.multiroom.ui.SnapcastVolumeKeys;

/**
 * The hardware volume keys reach the hosts of receiver rows that are on screen (the Receiver tab, the device sheet), the one that
 * started last first; a host that does not take the key leaves it to the system (the phone's volume). The up of a key whose down
 * was taken is taken too. Plain JVM.
 */
public class VolumeKeyHostsTest {

    private final List<VolumeKeyHosts.Host> registered = new ArrayList<>();
    private final List<String> log = new ArrayList<>();

    private VolumeKeyHosts.Host host(final String name, final boolean takes) {
        VolumeKeyHosts.Host host = direction -> {
            log.add(name + ":" + direction);
            return takes;
        };
        registered.add(host);
        VolumeKeyHosts.register(host);
        return host;
    }

    @After
    public void tearDown() {
        for (VolumeKeyHosts.Host host : registered) {
            VolumeKeyHosts.unregister(host);
        }
        VolumeKeyHosts.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP);
        VolumeKeyHosts.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN);
    }

    @Test
    public void withoutAHostTheSystemKeepsTheKey() {
        assertFalse(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(VolumeKeyHosts.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP));
    }

    @Test
    public void aHostThatTakesTheKeyGetsTheDirection() {
        host("tab", true);

        assertTrue(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP));
        assertTrue(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN));

        assertEquals("[tab:" + SnapcastVolumeKeys.Direction.UP + ", tab:" + SnapcastVolumeKeys.Direction.DOWN + "]", log.toString());
    }

    @Test
    public void aHostThatDoesNotTakeTheKeyLeavesItToTheSystem() {
        host("tab", false);

        assertFalse(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse("and its up is not taken either", VolumeKeyHosts.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP));
    }

    @Test
    public void theHostThatStartedLastIsAskedFirstAndTheNextOneIsAskedWhenItDoesNotTake() {
        host("tab", true);
        host("sheet", false);

        assertTrue(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN));

        assertEquals("[sheet:" + SnapcastVolumeKeys.Direction.DOWN + ", tab:" + SnapcastVolumeKeys.Direction.DOWN + "]",
                log.toString());
    }

    @Test
    public void theHostThatTakesTheKeyIsTheLastOneAsked() {
        host("tab", true);
        host("sheet", true);

        VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP);

        assertEquals("[sheet:" + SnapcastVolumeKeys.Direction.UP + "]", log.toString());
    }

    @Test
    public void aHostThatStoppedIsNotAskedAndRegisteringTwiceCountsOnce() {
        VolumeKeyHosts.Host tab = host("tab", true);
        VolumeKeyHosts.register(tab);
        VolumeKeyHosts.unregister(tab);

        assertFalse(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP));
        assertTrue(log.isEmpty());
    }

    @Test
    public void theUpOfAKeyWhoseDownWasTakenIsTakenOnce() {
        host("tab", true);

        assertTrue(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP));

        assertTrue(VolumeKeyHosts.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(VolumeKeyHosts.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse("the other key was not pressed", VolumeKeyHosts.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN));
    }

    @Test
    public void otherKeysAreNeverTaken() {
        host("tab", true);

        assertFalse(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_MUTE));
        assertFalse(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_DPAD_UP));
        assertFalse(VolumeKeyHosts.onKeyUp(KeyEvent.KEYCODE_DPAD_UP));
        assertTrue(log.isEmpty());
    }

    // ---- review S1: a stale entry must not swallow the up of a key the system had

    @Test
    public void anEntryThatNeverGotItsUpDoesNotSwallowTheUpOfALaterKeyTheSystemHad() {
        VolumeKeyHosts.Host tab = host("tab", true);
        assertTrue(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP)); // its up never comes (window lost)
        VolumeKeyHosts.unregister(tab);
        registered.clear();
        host("tab", false);

        assertFalse("the next down is the system's", VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP));

        assertFalse("and so is its up", VolumeKeyHosts.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP));
    }

    @Test
    public void aDownThatIsNotTakenForgetsAnEarlierTakenDownOfTheSameKey() {
        final boolean[] take = {true};
        VolumeKeyHosts.Host host = direction -> take[0];
        registered.add(host);
        VolumeKeyHosts.register(host);
        assertTrue(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN));
        take[0] = false;

        assertFalse(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN));

        assertFalse(VolumeKeyHosts.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN));
    }

    @Test
    public void stoppingAHostClearsTheKeysItTook() {
        VolumeKeyHosts.Host tab = host("tab", true);
        assertTrue(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP));

        VolumeKeyHosts.unregister(tab);

        assertFalse(VolumeKeyHosts.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP));
    }

    // ---- review S3: a sheet on top asks only its own host, not the page behind it

    @Test
    public void aSheetThatAsksOnlyTheTopHostLeavesTheKeyToTheSystemWhenItDoesNotTakeIt() {
        host("page", true);
        host("sheet", false);

        assertFalse(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, true));

        assertEquals("[sheet:" + SnapcastVolumeKeys.Direction.UP + "]", log.toString());
    }

    @Test
    public void theTopHostOnlyModeTakesTheKeyWhenTheTopHostDoes() {
        host("page", false);
        host("sheet", true);

        assertTrue(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, true));
        assertTrue(VolumeKeyHosts.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN));
    }

    @Test
    public void aHostThatFailsLeavesTheKeyToTheNextOneAndTheSystem() {
        VolumeKeyHosts.Host broken = direction -> {
            throw new IllegalStateException("view gone");
        };
        registered.add(broken);
        VolumeKeyHosts.register(broken);

        assertFalse(VolumeKeyHosts.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP));
    }
}
