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
package de.yaacc.fcast.discovery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 6.1 and decision D14 (text only, respect all trademarks): the notice of the trademark policy is in the README, the
 * documentation, the About page and the About text of the app in every language; every settings key of FCast is in the settings
 * documentation; the app has no FCast logo or icon.
 */
public class FCastDocsGuardTest {

    private static final String NOTICE = "independent community implementation of the FCast protocol";
    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    private static String read(String path) throws Exception {
        File file = new File(path);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void theNoticeIsInTheReadmeTheDocumentationAndTheAboutPage() throws Exception {
        for (String file : new String[]{"../README.md", "../docs/index.md", "../docs/about.md", "../docs/settings.md"}) {
            String text = read(file);
            assertTrue(file + " lacks the notice", text.contains(NOTICE));
            assertTrue(file, text.contains("not affiliated with FUTO"));
            assertTrue(file, text.contains("FCast is a registered trademark of FUTO"));
        }
    }

    @Test
    public void theAboutTextOfTheAppNamesFutoInEveryLanguage() throws Exception {
        Pattern pattern = Pattern.compile("<string name=\"app_descrip\">(.*?)</string>", Pattern.DOTALL);
        for (String locale : LOCALES) {
            Matcher matcher = pattern.matcher(read("src/main/res/" + locale + "/strings.xml"));
            assertTrue(locale, matcher.find());
            assertTrue(locale + " lacks the notice", matcher.group(1).contains("FUTO") && matcher.group(1).contains("FCast"));
        }
    }

    @Test
    public void everyFCastSettingsKeyIsInTheSettingsDocumentation() throws Exception {
        String settings = read("../docs/settings.md");

        assertTrue(settings.contains("key: " + FCastSettings.KEY_DISCOVERY));
        assertTrue(settings.contains("key: settings_fcast_receivers"));
        assertTrue(settings.contains("key: " + FCastSettings.KEY_V4));
        assertTrue(settings.contains("| **FCast**"));
    }

    @Test
    public void theReadmeAndTheDocumentationHaveAnFCastSectionWithTheFirewallHintAndTheSecurityNote() throws Exception {
        for (String file : new String[]{"../README.md", "../docs/index.md"}) {
            String text = read(file);
            assertTrue(file, text.contains("## FCast"));
            assertTrue(file + " firewall hint", text.contains("46899") && text.contains("5353"));
            assertTrue(file + " security note", text.contains("neither encrypted nor authenticated"));
        }
    }

    @Test
    public void theAppHasNoFCastLogoOrIcon() {
        File res = new File("src/main/res");
        assertTrue(res.isDirectory());
        for (File directory : res.listFiles()) {
            if (!directory.isDirectory() || !(directory.getName().startsWith("drawable") || directory.getName().startsWith("mipmap"))) {
                continue;
            }
            for (File file : directory.listFiles()) {
                assertFalse("a third-party logo: " + file, file.getName().toLowerCase(java.util.Locale.ROOT).contains("fcast"));
            }
        }
    }
}
