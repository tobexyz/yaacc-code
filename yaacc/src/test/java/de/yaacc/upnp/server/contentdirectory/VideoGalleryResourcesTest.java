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
package de.yaacc.upnp.server.contentdirectory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The texts of the video folders exist in every language. Plain JVM: the resource files are read from the module directory. */
public class VideoGalleryResourcesTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    @Test
    public void everyLocaleHasTheTextsOfTheVideoFolders() throws Exception {
        for (String dir : LOCALES) {
            File file = new File("src/main/res/" + dir + "/strings.xml");
            assertTrue("missing " + file.getAbsolutePath(), file.isFile());
            String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            for (String name : new String[]{"all_videos", "gallery_unnamed", "bucket_names", "videos"}) {
                Matcher text = Pattern.compile("<string name=\"" + name + "\">([^<]+)</string>").matcher(xml);
                assertTrue(dir + " " + name, text.find());
                assertFalse(dir + " " + name + " is empty", text.group(1).trim().isEmpty());
            }
        }
    }

    @Test
    public void theTextsOfALocaleAreNotTheEnglishOnesExceptForNamesThatAreTheSame() throws Exception {
        String english = new String(Files.readAllBytes(new File("src/main/res/values/strings.xml").toPath()), StandardCharsets.UTF_8);
        for (String dir : new String[]{"values-de", "values-es", "values-nl", "values-pt", "values-zh"}) {
            String xml = new String(Files.readAllBytes(new File("src/main/res/" + dir + "/strings.xml").toPath()), StandardCharsets.UTF_8);
            Matcher translated = Pattern.compile("<string name=\"all_videos\">([^<]+)</string>").matcher(xml);
            Matcher base = Pattern.compile("<string name=\"all_videos\">([^<]+)</string>").matcher(english);
            assertTrue(translated.find() && base.find());
            assertFalse(dir + " all_videos is not translated", translated.group(1).equals(base.group(1)));
        }
    }
}
