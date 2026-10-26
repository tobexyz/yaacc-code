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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Intent;

import org.junit.Test;

import de.yaacc.browser.BrowserLayoutChoice.Layout;
import de.yaacc.share.LaunchNonces;

/**
 * {@link ShareIntakeGate}: a browser activity hands a shared item to the share handler only when it
 * renders the effective layout. Both browsers can be started directly by another app
 * ({@code TabBrowserActivity} is exported), and the handler's behavior depends on the activity that
 * calls it (the classic one plays a link on this device without a picker), so an activity that is
 * about to be replaced by the other layout must neither act on nor keep the payload. The launcher
 * forward is unaffected because it always targets the activity of the effective layout.
 */
public class ShareIntakeGateTest {

    private static Intent textIntent() {
        Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn("android.intent.action.SEND");
        when(intent.getType()).thenReturn("text/plain");
        when(intent.getStringExtra(Intent.EXTRA_TEXT)).thenReturn("https://example.org/a.mp3");
        return intent;
    }

    private static Intent fileIntent() {
        Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn("android.intent.action.SEND");
        when(intent.getType()).thenReturn("audio/mpeg");
        return intent;
    }

    // ---- the decision

    @Test
    public void anActivityOfTheEffectiveLayoutAdmitsTheShare() {
        assertTrue(ShareIntakeGate.admits(Layout.CLASSIC, Layout.CLASSIC));
        assertTrue(ShareIntakeGate.admits(Layout.NEW, Layout.NEW));
    }

    @Test
    public void anActivityOfTheOtherLayoutDoesNotAdmitIt() {
        assertFalse(ShareIntakeGate.admits(Layout.CLASSIC, Layout.NEW));
        assertFalse(ShareIntakeGate.admits(Layout.NEW, Layout.CLASSIC));
    }

    @Test
    public void anUnknownEffectiveLayoutAdmitsNothing() {
        assertFalse(ShareIntakeGate.admits(Layout.CLASSIC, null));
        assertFalse(ShareIntakeGate.admits(Layout.NEW, null));
    }

    @Test
    public void theGateAgreesWithTheRelaunchDecision() {
        for (Layout own : Layout.values()) {
            for (Layout effective : Layout.values()) {
                // exactly the activities that would be relaunched must not take the payload
                assertEquals(!BrowserLayoutSwitch.shouldRelaunch(own, effective), ShareIntakeGate.admits(own, effective));
            }
        }
    }

    @Test
    public void theLauncherForwardTargetsTheActivityThatAdmitsTheShare() {
        // the launcher starts targetClass(effective): the one activity whose own layout is the effective one
        assertEquals(TabBrowserActivity.class, BrowserLauncher.targetClass(Layout.CLASSIC));
        assertEquals(BrowserActivity.class, BrowserLauncher.targetClass(Layout.NEW));
    }

    // ---- the action

    @Test
    public void classicInClassicKeepsTheTextShareForTheHandler() {
        Intent intent = textIntent();

        assertTrue(ShareIntakeGate.admitOrDiscard(Layout.CLASSIC, Layout.CLASSIC, intent));

        verify(intent, never()).removeExtra(Intent.EXTRA_TEXT);
        verify(intent, never()).setClipData(null);
    }

    @Test
    public void classicInClassicKeepsTheFileShareForTheHandler() {
        Intent intent = fileIntent();

        assertTrue(ShareIntakeGate.admitOrDiscard(Layout.CLASSIC, Layout.CLASSIC, intent));

        verify(intent, never()).setClipData(null);
        verify(intent, never()).removeExtra(LaunchNonces.EXTRA_NAME);
    }

    @Test
    public void classicActivityInTheNewLayoutDropsAndConsumesATextShare() {
        Intent intent = textIntent();

        assertFalse(ShareIntakeGate.admitOrDiscard(Layout.CLASSIC, Layout.NEW, intent));

        verify(intent).removeExtra(Intent.EXTRA_TEXT);
        verify(intent).setClipData(null);
    }

    @Test
    public void classicActivityInTheNewLayoutDropsAndConsumesAFileShare() {
        Intent intent = fileIntent();

        assertFalse(ShareIntakeGate.admitOrDiscard(Layout.CLASSIC, Layout.NEW, intent));

        verify(intent).setClipData(null);
        verify(intent).removeExtra(Intent.EXTRA_STREAM);
        verify(intent).removeExtra(LaunchNonces.EXTRA_NAME);
    }

    @Test
    public void theNewActivityInTheClassicLayoutDropsAndConsumesToo() {
        Intent intent = textIntent();

        assertFalse(ShareIntakeGate.admitOrDiscard(Layout.NEW, Layout.CLASSIC, intent));

        verify(intent).removeExtra(Intent.EXTRA_TEXT);
        verify(intent).setClipData(null);
    }

    @Test
    public void aDroppedShareIsNotReadBeyondWhatConsumingNeeds() {
        Intent intent = mock(Intent.class);

        ShareIntakeGate.admitOrDiscard(Layout.CLASSIC, Layout.NEW, intent);

        // nothing is looked at, only removed
        verify(intent).removeExtra(Intent.EXTRA_TEXT);
        verify(intent).setClipData(null);
        verify(intent).removeExtra(Intent.EXTRA_STREAM);
        verify(intent).removeExtra(LaunchNonces.EXTRA_NAME);
        verifyNoMoreInteractions(intent);
    }

    @Test
    public void aNullIntentIsHarmless() {
        assertTrue(ShareIntakeGate.admitOrDiscard(Layout.CLASSIC, Layout.CLASSIC, null));
        assertFalse(ShareIntakeGate.admitOrDiscard(Layout.CLASSIC, Layout.NEW, null));
    }

    @Test
    public void aFailingIntentDoesNotBreakTheDrop() {
        Intent intent = mock(Intent.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(intent).setClipData(null);

        assertFalse(ShareIntakeGate.admitOrDiscard(Layout.CLASSIC, Layout.NEW, intent));
    }
}
