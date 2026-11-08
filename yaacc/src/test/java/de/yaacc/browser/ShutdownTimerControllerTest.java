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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.Yaacc;

/**
 * Unit tests for {@link ShutdownTimerController}: what the display is told and who listens.
 * The picker needs a FragmentManager and is not covered. Plain JVM with mocks.
 */
public class ShutdownTimerControllerTest {

    private static final String KEY = "shutdown";

    private Yaacc app;
    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;
    private ShutdownTimerController controller;
    private final List<String> shown = new ArrayList<>();
    private final ShutdownTimerController.Display display =
            (millis, running) -> shown.add(millis + "/" + running);

    @Before
    public void setUp() {
        app = mock(Yaacc.class);
        prefs = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class);
        when(prefs.edit()).thenReturn(editor);
        when(editor.putLong(org.mockito.ArgumentMatchers.anyString(), anyLong())).thenReturn(editor);
        when(prefs.getLong(KEY, 0L)).thenReturn(90_000L);
        controller = new ShutdownTimerController(app, prefs, KEY);
    }

    @Test
    public void attachShowsTheConfiguredDurationWhenNothingRuns() {
        controller.attach(display);

        assertEquals(List.of("90000/false"), shown);
        verify(app).addShutdownTimerListener(controller);
    }

    @Test
    public void attachShowsTheRemainingTimeWhenATimerRuns() {
        when(app.isShutdownTimerRunning()).thenReturn(true);
        when(app.getShutdownTimerRemainingMillis()).thenReturn(42_000L);

        controller.attach(display);

        assertEquals(List.of("42000/true"), shown);
        assertTrue(controller.isRunning());
    }

    @Test
    public void ticksReachTheDisplayOnlyWhileAttached() {
        controller.attach(display);
        shown.clear();

        controller.onTick(5_000L);
        controller.detach();
        controller.onTick(4_000L);

        assertEquals(List.of("5000/true"), shown);
        verify(app).removeShutdownTimerListener(controller);
    }

    @Test
    public void startUsesTheConfiguredDuration() {
        controller.attach(display);

        controller.start();

        verify(app).startShutdownTimer(90_000L);
    }

    @Test
    public void stopStopsTheTimerAndShowsTheConfiguredDurationAgain() {
        when(app.isShutdownTimerRunning()).thenReturn(true, false);
        controller.attach(display);
        shown.clear();

        controller.stop();

        verify(app).stopShutdownTimer();
        assertEquals(List.of("90000/false"), shown);
    }

    @Test
    public void newDurationIsStoredAndRestartsOnlyARunningTimer() {
        when(app.isShutdownTimerRunning()).thenReturn(false);
        controller.setConfiguredMillis(120_000L);
        verify(editor).putLong(KEY, 120_000L);
        verify(app, never()).startShutdownTimer(anyLong());

        when(app.isShutdownTimerRunning()).thenReturn(true);
        controller.setConfiguredMillis(180_000L);
        verify(app).startShutdownTimer(180_000L);
    }

    @Test
    public void detachWithoutAttachIsHarmless() {
        controller.detach();
        assertFalse(controller.isRunning());
    }
}
