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

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pins the buttons of a mini-player row (res/layout/mini_player_row.xml) to the ids and the
 * commands {@link MiniPlayerAdapter} binds, so a row cannot silently lose its exit button.
 * Plain JVM: the layout file is read from the module directory Gradle runs unit tests in.
 */
public class MiniPlayerRowLayoutTest {

    private static String layout() throws Exception {
        File file = new File("src/main/res/layout/mini_player_row.xml");
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void everyCommandHasAButtonInTheRow() throws Exception {
        String xml = layout();
        for (String id : new String[]{"miniPlayerPrevious", "miniPlayerPlayPause", "miniPlayerNext",
                "miniPlayerStop", "miniPlayerExit"}) {
            assertTrue("no button " + id, xml.contains("@+id/" + id + "\""));
        }
        assertEquals(5, MiniPlayerAdapter.Command.values().length);
        assertEquals(MiniPlayerAdapter.Command.EXIT, MiniPlayerAdapter.Command.valueOf("EXIT"));
    }

    @Test
    public void theExitButtonIsLabelledWithTheExitText() throws Exception {
        Matcher exit = Pattern.compile(
                "<ImageButton[^>]*@\\+id/miniPlayerExit\"[^>]*android:contentDescription=\"@string/exit\"",
                Pattern.DOTALL).matcher(layout());
        assertTrue(exit.find());
    }
}
