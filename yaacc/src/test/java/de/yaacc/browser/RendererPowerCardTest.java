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

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The power button of the renderer cards (task 12.4) as resources and sources (plain JVM, the files are read from the module
 * directory): the button is in the card layout, with theme colours and a 48dp touch target; one binder places it for every kind of
 * device and the phone's own card and the Snapcast groups have none; the tap goes through the one controller; the live updates
 * of OpenHome reach the same row rebind as the MusicCast events; the texts exist in every locale.
 */
public class RendererPowerCardTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String source(String name) throws Exception {
        return read("src/main/java/de/yaacc/browser/" + name + ".java").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String methodBody(String source, String signatureStart) {
        int at = source.indexOf(signatureStart);
        assertTrue("no method " + signatureStart, at >= 0);
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (source.charAt(i) == '{') {
                depth++;
            } else if (source.charAt(i) == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced " + signatureStart);
    }

    @Test
    public void theButtonIsInTheCardWithThemeColoursAndATouchTarget() throws Exception {
        String card = read("src/main/res/layout/browse_receiver_device_item.xml");
        String icon = read("src/main/res/drawable/ic_baseline_power_settings_new_32.xml");

        int at = card.indexOf("@+id/power_btn");
        assertTrue(at > 0);
        String button = card.substring(card.lastIndexOf("<androidx.appcompat.widget.AppCompatImageButton", at), card.indexOf("/>", at));
        assertTrue(button, button.contains("android:layout_width=\"48dp\"") && button.contains("android:layout_height=\"48dp\""));
        assertTrue("hidden until a device supports power", button.contains("android:visibility=\"gone\""));
        assertTrue(button.contains("android:background=\"?attr/selectableItemBackgroundBorderless\""));
        assertTrue(icon, icon.contains("?attr/colorControlNormal"));
        assertFalse("colours come from the theme", Pattern.compile("#[0-9a-fA-F]{3,8}\\b").matcher(button + icon).find());
        assertTrue("it is in the name row, before the check box", at < card.indexOf("@+id/browseReceiverDeviceItemCheckbox"));
    }

    @Test
    public void oneBinderPlacesItForEveryKindAndTheSnapcastBinderDecidesForItsGroupsAndThePhoneHasNone() throws Exception {
        String binder = source("ReceiverRowBinder");
        String bind = methodBody(binder, "private void bindPower(");

        assertTrue(binder.contains("final ImageButton power;") && binder.contains("R.id.power_btn"));
        assertTrue("every status rebind places it", methodBody(binder, "void bindStatus(").contains("bindPower(holder, device, deviceId)"));
        assertTrue(bind.contains("!(device instanceof SnapcastGroupDevice) && !isLocalDevice(device)"));
        // a Snapcast group card goes to the Snapcast binder, which shows the button only on the card of the phone's own client
        assertTrue(bind.indexOf("snapcastRows.bindPower(holder, (SnapcastGroupDevice) device)") >= 0
                && bind.indexOf("snapcastRows.bindPower(") < bind.indexOf("MusicCastPower.of("));
        assertTrue("MusicCast first, then OpenHome", bind.indexOf("MusicCastPower.of(") < bind.indexOf("OpenHomePower.offeredBy(device)"));
        assertTrue(bind.contains("RendererPowerButton.bind("));
        assertTrue("the name is cleaned display text", bind.contains("YxcRowText.clean("));
        assertTrue("an error is a toast like the other card actions", bind.contains("RendererPowerLook.errorText(outcome.error())")
                && bind.contains("Toast.makeText("));
        assertTrue("busy during a group change too", bind.contains("YxcGroupController.getShared().isBusy(udn)"));
    }

    @Test
    public void everyTapGoesThroughTheOneControllerAndTheOneToggle() throws Exception {
        String controller = source("RendererPowerController");

        assertEquals(1, controller.split("RendererPowerToggle\\.toggle\\(", -1).length - 1);
        for (String file : new String[]{"ReceiverRowBinder", "OpenHomePowerMonitor", "RendererPowerButton", "YxcRowFeed"}) {
            assertFalse(file, source(file).contains("RendererPowerToggle.toggle("));
        }
        assertTrue(source("ReceiverRowBinder").contains("RendererPowerController.getShared().toggle("));
        assertTrue("the rows are rebound the way group operations rebind them", controller.contains("YxcGroupController.getShared().requestRebind("));
        assertTrue(controller.contains("gate.tryStart("));
        assertFalse("nothing of the device is logged", controller.contains("getMessage()") || controller.contains("friendlyName"));
    }

    @Test
    public void theOpenHomeStateReachesTheSameRebindAsTheMusicCastEvents() throws Exception {
        String feed = source("YxcRowFeed");

        assertTrue(methodBody(feed, "void start(").contains("OpenHomePowerMonitor.getShared().subscribe(this, devices)"));
        assertTrue(methodBody(feed, "void stop()").contains("OpenHomePowerMonitor.getShared().unsubscribe(this)"));
        assertTrue(methodBody(feed, "public void onPowerChanged(").contains("enqueue(udn)"));
        assertTrue(methodBody(feed, "public void onStateChanged(").contains("enqueue(udn)"));
    }

    @Test
    public void theOpenHomeMonitorDropsPollsOfAnEarlierSubscriptionAndOnlyTellsChanges() throws Exception {
        String monitor = source("OpenHomePowerMonitor");
        String poll = methodBody(monitor, "private void poll(");

        assertTrue(poll.contains("subscriptions.isCurrent(generation)"));
        assertTrue("a listener is told only when the value changed", poll.contains("if (changed)"));
        assertFalse("not logged: the message holds the address of the device", monitor.contains("YaaccLogger"));
        assertTrue(monitor.contains("handler.removeCallbacksAndMessages(null)"));
        assertTrue("only devices that offer power are read", monitor.contains("OpenHomePower.offeredBy(device)"));
    }

    @Test
    public void theTextsExistInEveryLocale() throws Exception {
        String[] needed = {"power_button_description", "power_state_on", "power_state_standby", "power_state_unknown",
                "power_error_unreachable", "power_error_unknown_state", "power_error_refused"};
        for (String locale : LOCALES) {
            String xml = read("src/main/res/" + locale + "/strings.xml");
            for (String name : needed) {
                Matcher matcher = Pattern.compile("<string name=\"" + name + "\">([^<]+)</string>").matcher(xml);
                assertTrue(locale + " lacks " + name, matcher.find());
                if (name.equals("power_button_description")) {
                    assertTrue(locale, matcher.group(1).contains("%1$s"));
                }
            }
        }
    }
}
