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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The whole app is gray scale with Material 3: the theme's color roles are grays (light and dark), the
 * theme sets every role the components read, text and its background reach WCAG AA, the dark page is
 * pure black, the light title bar has the page color, and no resource file hard-codes a color that is
 * not a gray. The launcher icon is the only colored asset. Plain JVM: the resource files are read from
 * the module directory Gradle runs unit tests in.
 */
public class GrayscaleResourcesTest {

    private static final String LIGHT = "src/main/res/values/colors.xml";
    private static final String DARK = "src/main/res/values-night/colors.xml";
    private static final String STYLES = "src/main/res/values/styles.xml";

    private static final Pattern HEX = Pattern.compile("#([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{3,4})\\b");

    /** Theme attribute of a Material 3 color role -> color resource of the scheme. */
    private static final Map<String, String> ROLES = new LinkedHashMap<>();

    static {
        String[][] roles = {
                {"colorPrimary", "gray_primary"},
                {"colorOnPrimary", "gray_on_primary"},
                {"colorPrimaryContainer", "gray_primary_container"},
                {"colorOnPrimaryContainer", "gray_on_primary_container"},
                {"colorPrimaryInverse", "gray_inverse_primary"},
                {"colorSecondary", "gray_secondary"},
                {"colorOnSecondary", "gray_on_secondary"},
                {"colorSecondaryContainer", "gray_secondary_container"},
                {"colorOnSecondaryContainer", "gray_on_secondary_container"},
                {"colorTertiary", "gray_tertiary"},
                {"colorOnTertiary", "gray_on_tertiary"},
                {"colorTertiaryContainer", "gray_tertiary_container"},
                {"colorOnTertiaryContainer", "gray_on_tertiary_container"},
                {"android:colorBackground", "gray_background"},
                {"colorOnBackground", "gray_on_background"},
                {"colorSurface", "gray_surface"},
                {"colorOnSurface", "gray_on_surface"},
                {"colorSurfaceVariant", "gray_surface_variant"},
                {"colorOnSurfaceVariant", "gray_on_surface_variant"},
                {"colorSurfaceInverse", "gray_inverse_surface"},
                {"colorOnSurfaceInverse", "gray_inverse_on_surface"},
                {"colorSurfaceDim", "gray_surface_dim"},
                {"colorSurfaceBright", "gray_surface_bright"},
                {"colorSurfaceContainerLowest", "gray_surface_container_lowest"},
                {"colorSurfaceContainerLow", "gray_surface_container_low"},
                {"colorSurfaceContainer", "gray_surface_container"},
                {"colorSurfaceContainerHigh", "gray_surface_container_high"},
                {"colorSurfaceContainerHighest", "gray_surface_container_highest"},
                {"colorOutline", "gray_outline"},
                {"colorOutlineVariant", "gray_outline_variant"},
        };
        for (String[] role : roles) {
            ROLES.put(role[0], role[1]);
        }
    }

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

    /** WCAG relative luminance of a gray given as RRGGBB or AARRGGBB (alpha ignored). */
    static double luminance(String hex) {
        double channel = Integer.parseInt(hex.substring(hex.length() - 2), 16) / 255.0;
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    /** WCAG contrast ratio of two grays. */
    static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static Map<String, String> colors(String file) throws Exception {
        Map<String, String> colors = new LinkedHashMap<>();
        Matcher color = Pattern.compile("<color name=\"([^\"]+)\">#([0-9A-Fa-f]+)</color>").matcher(read(file));
        while (color.find()) {
            colors.put(color.group(1), color.group(2));
        }
        return colors;
    }

    private static String appThemeBody() throws Exception {
        Matcher theme = Pattern.compile("<style name=\"AppTheme\"[^>]*>(.*?)</style>", Pattern.DOTALL).matcher(read(STYLES));
        assertTrue(theme.find());
        return theme.group(1);
    }

    private static String styleBody(String name) throws Exception {
        Matcher style = Pattern.compile("<style name=\"" + Pattern.quote(name) + "\"[^>]*>(.*?)</style>", Pattern.DOTALL)
                .matcher(read(STYLES));
        assertTrue(name, style.find());
        return style.group(1);
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
    public void theContrastMathMatchesTheWcagReferenceValues() {
        assertEquals(21.0, contrast("000000", "FFFFFF"), 0.01);
        assertEquals(1.0, contrast("777777", "777777"), 0.0001);
        // #767676 on white is the well-known smallest gray that passes AA for text
        assertTrue(contrast("767676", "FFFFFF") >= 4.5);
        assertTrue(contrast("777777", "FFFFFF") < 4.5);
    }

    @Test
    public void theSchemeColorsAreGraysInLightAndDark() throws Exception {
        for (String file : new String[]{LIGHT, DARK}) {
            Map<String, String> colors = colors(file);
            for (Map.Entry<String, String> color : colors.entrySet()) {
                assertTrue(file + " " + color.getKey() + " is not a gray", isGray(color.getValue()));
            }
            assertTrue(file, colors.size() >= ROLES.size());
        }
    }

    @Test
    public void bothVariantsDefineTheSameNames() throws Exception {
        assertEquals(names(LIGHT), names(DARK));
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
    public void bothVariantsDefineEveryRole() throws Exception {
        for (String file : new String[]{LIGHT, DARK}) {
            Map<String, String> colors = colors(file);
            for (String color : ROLES.values()) {
                assertTrue(file + " lacks " + color, colors.containsKey(color));
            }
        }
    }

    @Test
    public void theAppThemeIsMaterial3AndSetsEveryRoleToItsGray() throws Exception {
        String styles = read(STYLES);
        assertTrue(styles.contains("<style name=\"AppTheme\" parent=\"Theme.Material3.DayNight\">"));
        assertFalse("no Material Components themes left", styles.contains("Theme.MaterialComponents"));
        String body = appThemeBody();
        for (Map.Entry<String, String> role : ROLES.entrySet()) {
            assertTrue(role.getKey(), body.contains("<item name=\"" + role.getKey() + "\">@color/" + role.getValue() + "</item>"));
        }
        // AppCompat widgets (check boxes, switches, sliders, progress bars)
        assertTrue(body.contains("<item name=\"colorAccent\">@color/gray_primary</item>"));
        assertTrue(body.contains("<item name=\"colorControlActivated\">@color/gray_primary</item>"));
    }

    @Test
    public void theLibraryHasNoSchemeColorsOfItsOwnLeftInTheTextColors() throws Exception {
        String body = appThemeBody();
        // the library's text colors point at its tinted baseline, so the theme replaces every one of them
        for (String attr : new String[]{"textColorPrimary", "textColorPrimaryDisableOnly", "textColorAlertDialogListItem",
                "textColorSecondary", "textColorTertiary", "textColorHint", "textColorHighlight",
                "textColorPrimaryInverse", "textColorSecondaryInverse", "textColorTertiaryInverse",
                "textColorPrimaryInverseDisableOnly", "textColorHintInverse", "textColorHighlightInverse"}) {
            assertTrue(attr, Pattern.compile("<item name=\"android:" + attr + "\">(@color/gray_[a-z_]+|\\?attr/colorOnSurfaceInverse)</item>")
                    .matcher(body).find());
        }
        for (String selector : new String[]{"gray_text_primary", "gray_text_secondary"}) {
            String xml = read("src/main/res/color/" + selector + ".xml");
            Matcher ref = Pattern.compile("android:color=\"([^\"]+)\"").matcher(xml);
            int count = 0;
            while (ref.find()) {
                count++;
                assertTrue(selector + " " + ref.group(1), ref.group(1).startsWith("@color/gray_"));
            }
            assertTrue(selector, count >= 2);
        }
    }

    @Test
    public void textReachesWcagAaOnItsBackgroundInBothThemes() throws Exception {
        String[][] pairs = {
                {"gray_on_primary", "gray_primary"},
                {"gray_on_primary_container", "gray_primary_container"},
                {"gray_on_secondary", "gray_secondary"},
                {"gray_on_secondary_container", "gray_secondary_container"},
                {"gray_on_tertiary", "gray_tertiary"},
                {"gray_on_tertiary_container", "gray_tertiary_container"},
                {"gray_on_background", "gray_background"},
                {"gray_inverse_on_surface", "gray_inverse_surface"},
                {"gray_on_surface_variant", "gray_surface_variant"},
                // primary text on the page, on every container (cards, bars, sheets, dialogs)
                {"gray_on_surface", "gray_surface"},
                {"gray_on_surface", "gray_surface_dim"},
                {"gray_on_surface", "gray_surface_bright"},
                {"gray_on_surface", "gray_surface_container_lowest"},
                {"gray_on_surface", "gray_surface_container_low"},
                {"gray_on_surface", "gray_surface_container"},
                {"gray_on_surface", "gray_surface_container_high"},
                {"gray_on_surface", "gray_surface_container_highest"},
                // secondary text and default icons on the page and on the containers they sit on
                {"gray_on_surface_variant", "gray_surface"},
                {"gray_on_surface_variant", "gray_surface_container_low"},
                {"gray_on_surface_variant", "gray_surface_container"},
                {"gray_on_surface_variant", "gray_surface_container_high"},
                {"gray_on_surface_variant", "gray_surface_container_highest"},
        };
        for (String file : new String[]{LIGHT, DARK}) {
            Map<String, String> colors = colors(file);
            for (String[] pair : pairs) {
                double ratio = contrast(colors.get(pair[0]), colors.get(pair[1]));
                assertTrue(file + " " + pair[0] + " on " + pair[1] + " = " + ratio, ratio >= 4.5);
            }
        }
    }

    @Test
    public void outlinesAreVisibleAgainstThePageAndDividersAreFainter() throws Exception {
        for (String file : new String[]{LIGHT, DARK}) {
            Map<String, String> colors = colors(file);
            double outline = contrast(colors.get("gray_outline"), colors.get("gray_surface"));
            double divider = contrast(colors.get("gray_outline_variant"), colors.get("gray_surface"));
            assertTrue(file + " outline " + outline, outline >= 3.0);
            assertTrue(file + " divider " + divider + " must be fainter than the outline " + outline, divider < outline);
            assertTrue(file + " divider " + divider, divider > 1.0);
        }
    }

    @Test
    public void thePageIsPureBlackInTheDarkThemeAndTheContainersStepLighter() throws Exception {
        Map<String, String> dark = colors(DARK);
        for (String name : new String[]{"gray_background", "gray_surface", "gray_surface_dim", "gray_surface_container_lowest"}) {
            assertEquals(name, "000000", dark.get(name).toUpperCase());
        }
        double previous = -1;
        for (String name : new String[]{"gray_surface", "gray_surface_container_low", "gray_surface_container",
                "gray_surface_container_high", "gray_surface_container_highest", "gray_surface_bright"}) {
            double l = luminance(dark.get(name));
            assertTrue(name + " must be lighter than the one before", l > previous);
            previous = l;
        }
    }

    @Test
    public void theLightTitleBarHasThePageColorAndTheLightContainersStepDarker() throws Exception {
        Map<String, String> light = colors(LIGHT);
        assertEquals("F6F6F6", light.get("gray_background").toUpperCase());
        assertEquals(light.get("gray_background"), light.get("gray_surface"));
        assertEquals("FFFFFF", light.get("gray_surface_container_lowest").toUpperCase());
        double previous = 2;
        for (String name : new String[]{"gray_surface_container_low", "gray_surface_container", "gray_surface_container_high",
                "gray_surface_container_highest"}) {
            double l = luminance(light.get(name));
            assertTrue(name + " must be darker than the one before", l < previous);
            previous = l;
        }
    }

    @Test
    public void theTitleBarIsTheSurfaceColorInBothThemesWithoutTheDarkOverlay() throws Exception {
        String styles = read(STYLES);
        String body = appThemeBody();
        assertTrue(body.contains("<item name=\"actionBarStyle\">@style/Widget.App.ActionBar</item>"));
        assertTrue(body.contains("<item name=\"actionBarTheme\">@style/ThemeOverlay.App.ActionBar</item>"));
        assertFalse(styles.contains("ThemeOverlay.AppCompat.Dark.ActionBar"));
        String bar = styleBody("Widget.App.ActionBar");
        assertTrue(bar.contains("<item name=\"background\">?attr/colorSurface</item>"));
        assertTrue(bar.contains("<item name=\"elevation\">0dp</item>"));
        String overlay = styleBody("ThemeOverlay.App.ActionBar");
        assertTrue(overlay.contains("<item name=\"colorControlNormal\">?attr/colorOnSurface</item>"));
        // the bar color equals the page color in both schemes
        for (String file : new String[]{LIGHT, DARK}) {
            Map<String, String> colors = colors(file);
            assertEquals(file, colors.get("gray_surface"), colors.get("gray_background"));
        }
    }

    @Test
    public void theSystemBarsMatchTheTitleBarAndTheirIconsFollowTheTheme() throws Exception {
        String body = appThemeBody();
        assertTrue(body.contains("<item name=\"android:statusBarColor\">@color/gray_surface</item>"));
        assertTrue(body.contains("<item name=\"android:colorPrimaryDark\">@color/gray_surface</item>"));
        assertTrue(body.contains("<item name=\"android:navigationBarColor\">@color/gray_background</item>"));
        assertTrue(body.contains("<item name=\"android:windowLightStatusBar\">@bool/gray_light_system_bars</item>"));
        assertTrue(body.contains("<item name=\"android:windowLightNavigationBar\">@bool/gray_light_system_bars</item>"));
        assertTrue(read("src/main/res/values/bools.xml").contains("name=\"gray_light_system_bars\">true<"));
        assertTrue(read("src/main/res/values-night/bools.xml").contains("name=\"gray_light_system_bars\">false<"));
    }

    @Test
    public void theSettingsHeadlinesAreEmphasised() throws Exception {
        String styles = read(STYLES);
        assertTrue(styles.contains("<item name=\"preferenceTheme\">@style/PreferenceThemeOverlay.App</item>"));
        assertTrue(styles.contains("<item name=\"preferenceCategoryTitleTextColor\">?attr/colorOnSurface</item>"));
        assertTrue(Pattern.compile("<style name=\"TextAppearance.App.PreferenceCategory\"[^>]*>\\s*<item name=\"android:textStyle\">bold</item>")
                .matcher(styles).find());
    }

    @Test
    public void sheetsDialogsAndTheTimePickerUseTheMaterial3Overlays() throws Exception {
        String styles = read(STYLES);
        assertTrue(styles.contains("<style name=\"ThemeOverlay.App.DeviceSheet\" parent=\"@style/ThemeOverlay.Material3.BottomSheetDialog\" />"));
        assertTrue(styles.contains("<item name=\"materialTimePickerTheme\">@style/ThemeOverlay.App.TimePicker</item>"));
        assertTrue(styles.contains("<style name=\"ThemeOverlay.App.TimePicker\" parent=\"@style/ThemeOverlay.Material3.MaterialTimePicker\" />"));
    }

    @Test
    public void cardsAreFilledWithTheHighContainerWithoutShadow() throws Exception {
        String card = styleBody("Widget.App.CardView");
        assertTrue(Pattern.compile("<style name=\"Widget.App.CardView\" parent=\"Widget.Material3.CardView.Filled\">")
                .matcher(read(STYLES)).find());
        assertTrue(card.contains("<item name=\"cardBackgroundColor\">?attr/colorSurfaceContainerHigh</item>"));
        assertTrue(card.contains("<item name=\"cardElevation\">0dp</item>"));
        assertTrue(card.contains("<item name=\"strokeWidth\">0dp</item>"));
        assertTrue(styleBody("Widget.App.CardView.Content").contains("<item name=\"cardCornerRadius\">20dp</item>"));
        assertTrue(styleBody("Widget.App.CardView.List").contains("<item name=\"cardCornerRadius\">16dp</item>"));
        String layouts = "src/main/res/layout/";
        assertTrue(read(layouts + "browse_content_item_card.xml").contains("style=\"@style/Widget.App.CardView.Content\""));
        for (String list : new String[]{"mini_player_row.xml", "browse_device_item.xml", "browse_player_item.xml",
                "browse_receiver_device_item.xml", "playlist_item.xml"}) {
            assertTrue(list, read(layouts + list).contains("style=\"@style/Widget.App.CardView.List\""));
        }
    }

    @Test
    public void layoutsUseColorRolesInsteadOfPlatformForegroundAndTextColors() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : xmlFiles("src/main/res")) {
            String path = file.toString().replace('\\', '/');
            if (!path.contains("/res/layout") && !path.contains("/res/menu") && !path.contains("/res/drawable")) {
                continue;
            }
            String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            // cards are flat Material 3 filled cards: only the transparent list row may name an elevation, and it is 0
            if (xml.contains("android:attr/colorForeground") || xml.contains("android:attr/textColorPrimary")
                    || xml.contains("android:attr/textColorSecondary")
                    || Pattern.compile("app:cardElevation=\"[1-9]").matcher(xml).find()) {
                offenders.add(path);
            }
        }
        assertTrue("old color attributes or card shadows in " + offenders, offenders.isEmpty());
    }

    @Test
    public void noResourceFileHardCodesAColor() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : xmlFiles("src/main/res")) {
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

    private static List<Path> xmlFiles(String dir) {
        try (Stream<Path> walk = Files.walk(new File(dir).toPath())) {
            return walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".xml"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
