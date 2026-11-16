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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.exoplayer.ExoPlayer;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The one change to the local music player for the Snapcast player: ExoPlayer is built by a protected factory method.
 * The default must stay what it was, {@code new ExoPlayer.Builder(getContext()).build()}, and be the only place that
 * builds an ExoPlayer, so that nothing changes for a user without Snapcast groups. ExoPlayer can not be built on the
 * JVM, so the shape is checked (reflection and source) next to the unchanged tests of the play mode.
 */
public class LocalMediaSessionPlayerFactoryTest {

    private static String source() throws Exception {
        return new String(Files.readAllBytes(new File("src/main/java/de/yaacc/player/LocalMediaSessionPlayer.java").toPath()),
                StandardCharsets.UTF_8);
    }

    @Test
    public void theFactoryIsProtectedTakesNothingAndReturnsExoPlayer() throws Exception {
        Method factory = LocalMediaSessionPlayer.class.getDeclaredMethod("createExoPlayer");

        assertTrue(Modifier.isProtected(factory.getModifiers()));
        assertEquals(ExoPlayer.class, factory.getReturnType());
        assertEquals(0, factory.getParameterTypes().length);
    }

    @Test
    public void theSnapcastPlayerOverridesItAndNothingElseBuildsAnExoPlayer() throws Exception {
        Method override = SnapcastPlayer.class.getDeclaredMethod("createExoPlayer");

        assertEquals(ExoPlayer.class, override.getReturnType());
        assertTrue(Modifier.isProtected(override.getModifiers()));
    }

    @Test
    public void theDefaultBuildsExactlyWhatWasBuiltBefore() throws Exception {
        String source = source();

        assertTrue(source.contains("    protected ExoPlayer createExoPlayer() {\n        return new ExoPlayer.Builder(getContext()).build();\n    }"));
        assertEquals("one place builds an ExoPlayer", 1, source.split("new ExoPlayer\\.Builder\\(", -1).length - 1);
        assertEquals("and the initialisation asks the factory once", 1,
                source.split("final ExoPlayer player = createExoPlayer\\(\\);", -1).length - 1);
    }

    @Test
    public void theHooksTheSnapcastPlayerOverridesAreTheOnesThatExist() throws Exception {
        // pause, resume, stop, item start and destroy are the existing overridable methods: no new hook was needed
        for (String name : new String[]{"doPause", "doResume", "stopItem", "startItem", "onDestroy"}) {
            boolean found = false;
            for (Method method : LocalMediaSessionPlayer.class.getDeclaredMethods()) {
                found |= method.getName().equals(name) && !Modifier.isPrivate(method.getModifiers())
                        && !Modifier.isFinal(method.getModifiers());
            }
            assertTrue(name, found);
        }
    }
}
