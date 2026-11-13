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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The owner's findings 11.1 and 11.3 as resources and sources (plain JVM, the files are read from the module directory):
 * the mute of a Snapcast group and of its clients is the mute symbol of the other renderer cards (the speaker and muted-speaker
 * drawable on a check box, not a switch or a plain check box), it is labelled for accessibility, the colours come from the
 * theme, and the texts of the link sheet exist in every locale. The group sheet has no member controls of its own any more.
 */
public class SnapcastCardResourcesTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt",
            "values-zh"};

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void theMuteToggleIsTheSymbolOfTheOtherCardsOnACheckBox() throws Exception {
        String toggle = read("src/main/res/layout/snapcast_mute_toggle.xml");
        String card = read("src/main/res/layout/browse_receiver_device_item.xml");

        assertTrue(toggle, toggle.contains("<CheckBox"));
        assertTrue("the drawable of the other cards", toggle.contains("android:button=\"@drawable/sl_mute_32\""));
        assertTrue("and the other cards use it too", card.contains("android:button=\"@drawable/sl_mute_32\""));
        assertFalse("colours come from the theme", Pattern.compile("#[0-9a-fA-F]{3,8}\\b").matcher(toggle).find());
        assertTrue("a touch target of 48dp", toggle.contains("android:minHeight=\"48dp\"") && toggle.contains("android:minWidth=\"48dp\""));
    }

    @Test
    public void theToggleIsInflatedWithAParentSoLintStaysAtItsBaseline() throws Exception {
        String toggle = read("src/main/java/de/yaacc/browser/SnapcastMuteToggle.java");

        assertFalse("review S4: no null root for inflate", toggle.contains(", null, false)"));
        assertTrue(toggle.contains("inflate(R.layout.snapcast_mute_toggle, parent, false)"));
    }

    @Test
    public void theLinkSheetShowsTheServerAndTheGroupNameInFull() throws Exception {
        String sheet = read("src/main/java/de/yaacc/browser/YxcLinkSheet.java");

        assertTrue("review S6: the subtitle of a Snapcast sheet has room for two long lines of two",
                sheet.contains("subtitle.setMaxLines(options.subtitleMaxLines)"));
    }

    @Test
    public void theSheetHasNoSwitchAndNoPlainCheckBoxForTheMute() throws Exception {
        String sheet = read("src/main/java/de/yaacc/browser/SnapcastGroupSheet.java");

        assertFalse("the group mute was a switch", sheet.contains("SwitchCompat"));
        assertFalse("the client mute was a check box", sheet.contains("new CheckBox"));
        assertTrue(sheet.contains("SnapcastMuteToggle"));
    }

    @Test
    public void theSheetHasNoMemberControlsOfItsOwnAnyMore() throws Exception {
        String sheet = read("src/main/java/de/yaacc/browser/SnapcastGroupSheet.java");

        assertFalse("add client moved to the link sheet", sheet.contains("showAddClients"));
        assertFalse("remove from group moved to the link sheet", sheet.contains("snapcast_member_remove"));
        assertFalse(sheet.contains("controls.removeClient"));
        assertFalse(sheet.contains("controls.addClients"));
    }

    @Test
    public void theCardShowsTheLinkIconForAGroupLikeTheMusicCastCard() throws Exception {
        String binder = read("src/main/java/de/yaacc/browser/SnapcastRowBinder.java");
        String card = read("src/main/res/layout/browse_receiver_device_item.xml");

        assertTrue("the link icon of the MusicCast card", card.contains("@drawable/ic_yxc_link"));
        assertTrue(binder.contains("yxcToggle"));
        assertTrue(binder.contains("SnapcastLinkSheet"));
        assertFalse("the Snapcast row no longer hides the link icon", binder.contains("hide(views.yxcToggle)"));
    }

    @Test
    public void theTextsExistInEveryLocaleAndTheOldMemberTextsAreGone() throws Exception {
        String[] needed = {"snapcast_row_link", "snapcast_link_keep_one", "snapcast_link_none", "snapcast_link_last_member",
                "snapcast_mute_state_on", "snapcast_mute_state_off"};
        String[] removed = {"snapcast_sheet_add_client", "snapcast_sheet_add_client_title", "snapcast_sheet_add_client_none",
                "snapcast_member_remove"};
        for (String locale : LOCALES) {
            String xml = read("src/main/res/" + locale + "/strings.xml");
            for (String name : needed) {
                Matcher matcher = Pattern.compile("<string name=\"" + name + "\">([^<]+)</string>").matcher(xml);
                assertTrue(locale + " lacks " + name, matcher.find());
            }
            Matcher last = Pattern.compile("<string name=\"snapcast_link_last_member\">([^<]+)</string>").matcher(xml);
            assertTrue(locale, last.find() && last.group(1).contains("%1$s"));
            for (String name : removed) {
                assertFalse(locale + " still has " + name, xml.contains("<string name=\"" + name + "\">"));
            }
        }
    }
}
