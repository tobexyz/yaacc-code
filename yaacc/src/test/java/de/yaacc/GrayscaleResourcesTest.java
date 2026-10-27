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
package de.yaacc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The whole app is gray scale: the theme's colors are grays (light and dark), the theme takes them for
 * every accent attribute, and no resource file hard-codes a color that is not a gray. The launcher
 * icon is the only colored asset. Plain JVM: the resource files are read from the module directory
 * Gradle runs unit tests in.
 */
public class GrayscaleResourcesTest {

    private static final Pattern HEX = Pattern.compile("#([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{3,4})\\b");

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** True for #RGB, #ARGB, #RRGGBB and #AARRGGBB where red, green and blue are equal. */
    static boolean isGray(String hex) {
        String h = hex;
        if (h.length() == 3 || h.length() == 4) {
            StringBuilder expanded = new StringBuilder();
            for (char c : h.toCharArray()) {
                expanded.append(c).append(c);
            }
            h = expanded.toString();
        }
        String rgb = h.substring(h.length() - 6);
        return rgb.substring(0, 2).equalsIgnoreCase(rgb.substring(2, 4))
                && rgb.substring(2, 4).equalsIgnoreCase(rgb.substring(4, 6));
    }

    @Test
    public void theGrayCheckRecognisesGraysAndColors() {
        assertTrue(isGray("616161"));
        assertTrue(isGray("40BDBDBD"));
        assertTrue(isGray("FFF"));
        assertTrue(isGray("00000000"));
        assertEquals(false, isGray("008577"));
        assertEquals(false, isGray("6200EE"));
        assertEquals(false, isGray("FF03DAC6"));
    }

    @Test
    public void theThemeColorsAreGraysInLightAndDark() throws Exception {
        for (String file : new String[]{"src/main/res/values/colors.xml", "src/main/res/values-night/colors.xml"}) {
            Matcher color = Pattern.compile("<color name=\"([^\"]+)\">#([0-9A-Fa-f]+)</color>").matcher(read(file));
            int count = 0;
            while (color.find()) {
                count++;
                assertTrue(file + " " + color.group(1) + " is not a gray", isGray(color.group(2)));
            }
            assertTrue(file, count >= 8);
        }
    }

    @Test
    public void bothVariantsDefineTheSameNames() throws Exception {
        assertEquals(names("src/main/res/values/colors.xml"), names("src/main/res/values-night/colors.xml"));
    }

    private static List<String> names(String file) throws Exception {
        List<String> names = new ArrayList<>();
        Matcher color = Pattern.compile("<color name=\"([^\"]+)\"").matcher(read(file));
        while (color.find()) {
            names.add(color.group(1));
        }
        return names;
    }

    @Test
    public void theAppThemeTakesTheGraysForEveryAccentAttribute() throws Exception {
        String styles = read("src/main/res/values/styles.xml");
        Matcher theme = Pattern.compile("<style name=\"AppTheme\"[^>]*>(.*?)</style>", Pattern.DOTALL).matcher(styles);
        assertTrue(theme.find());
        String body = theme.group(1);
        for (String attr : new String[]{"colorPrimary", "colorPrimaryVariant", "colorOnPrimary", "colorSecondary",
                "colorSecondaryVariant", "colorOnSecondary", "colorAccent", "colorControlActivated",
                "android:colorPrimaryDark"}) {
            assertTrue(attr, Pattern.compile("<item name=\"" + Pattern.quote(attr) + "\">@color/gray_[a-z_]+</item>")
                    .matcher(body).find());
        }
    }

    @Test
    public void theTitleBarIsDarkAndTheSettingsHeadlinesAreEmphasised() throws Exception {
        String styles = read("src/main/res/values/styles.xml");
        assertTrue(styles.contains("<item name=\"actionBarStyle\">@style/Widget.App.ActionBar</item>"));
        assertTrue(styles.contains("<item name=\"actionBarTheme\">@style/ThemeOverlay.AppCompat.Dark.ActionBar</item>"));
        assertTrue(Pattern.compile("<style name=\"Widget.App.ActionBar\"[^>]*>\\s*<item name=\"background\">@color/gray_bar</item>")
                .matcher(styles).find());
        assertTrue(styles.contains("<item name=\"android:colorPrimaryDark\">@color/gray_bar_dark</item>"));
        assertTrue(styles.contains("<item name=\"preferenceTheme\">@style/PreferenceThemeOverlay.App</item>"));
        assertTrue(styles.contains("<item name=\"preferenceCategoryTitleTextColor\">?android:attr/textColorPrimary</item>"));
        assertTrue(Pattern.compile("<style name=\"TextAppearance.App.PreferenceCategory\"[^>]*>\\s*<item name=\"android:textStyle\">bold</item>")
                .matcher(styles).find());
    }

    @Test
    public void theTitleBarIsDarkInBothThemes() throws Exception {
        assertTrue(read("src/main/res/values/colors.xml").contains("name=\"gray_bar\">#424242<"));
        assertTrue(read("src/main/res/values-night/colors.xml").contains("name=\"gray_bar\">#121212<"));
        assertTrue(read("src/main/res/values-night/colors.xml").contains("name=\"gray_bar_dark\">#000000<"));
    }

    @Test
    public void noResourceFileHardCodesAColor() throws Exception {
        List<String> offenders = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(new File("src/main/res").toPath())) {
            files = walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".xml"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            // the launcher icon is the one colored asset
            if (name.startsWith("ic_launcher")) {
                continue;
            }
            Matcher hex = HEX.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            while (hex.find()) {
                if (!isGray(hex.group(1))) {
                    offenders.add(file + " #" + hex.group(1));
                }
            }
        }
        assertTrue("colored resources: " + offenders, offenders.isEmpty());
    }
}
