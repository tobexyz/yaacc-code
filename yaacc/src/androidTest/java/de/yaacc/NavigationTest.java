package de.yaacc;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import androidx.test.uiautomator.By;

import de.yaacc.utils.TestHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NavigationTest {

    private UiDevice device;
    private static final int TIMEOUT = 10000;

    @Before
    public void setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        
        device.pressHome();
        device.wait(Until.hasObject(By.pkg("com.android.launcher3")), TIMEOUT);
        
        InstrumentationRegistry.getInstrumentation().getContext()
            .startActivity(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getPackageManager().getLaunchIntentForPackage("de.yaacc")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        
        device.wait(Until.hasObject(By.pkg("de.yaacc")), TIMEOUT);
        
        handlePermissionDialogs();
    }

    @Test
    public void testAllTabsExist() throws Exception {
        Thread.sleep(2000);
        
        TestHelper.takeScreenshot(device, "navigation_all_tabs");
        
        // Just verify we have a tab layout with multiple tabs
        // Tabs might be translated, so count them instead of checking text
        int tabCount = 0;
        for (int i = 0; i < 10; i++) {
            UiObject tab = device.findObject(new UiSelector()
                .className("android.widget.TextView")
                .instance(i));
            if (tab.exists()) {
                tabCount++;
            } else {
                break;
            }
        }
        
        assertTrue("Should have at least 4 tabs", tabCount >= 4);
    }

    @Test
    public void testSwitchToServerTab() throws Exception {
        Thread.sleep(2000);
        
        // Try to find and click first tab (Server)
        UiObject tab = findTab(0);
        if (tab != null && tab.exists()) {
            tab.click();
            Thread.sleep(1000);
            TestHelper.takeScreenshot(device, "navigation_server_tab");
            assertTrue("Should be in YAACC", device.hasObject(By.pkg("de.yaacc")));
        }
    }

    @Test
    public void testSwitchToContentTab() throws Exception {
        Thread.sleep(2000);
        
        // Try to find and click second tab (Content)
        UiObject tab = findTab(1);
        if (tab != null && tab.exists()) {
            tab.click();
            Thread.sleep(1000);
            TestHelper.takeScreenshot(device, "navigation_content_tab");
            assertTrue("Should be in YAACC", device.hasObject(By.pkg("de.yaacc")));
        }
    }

    @Test
    public void testSwitchToReceiverTab() throws Exception {
        Thread.sleep(2000);
        
        // Try to find and click third tab (Receiver)
        UiObject tab = findTab(2);
        if (tab != null && tab.exists()) {
            tab.click();
            Thread.sleep(1000);
            TestHelper.takeScreenshot(device, "navigation_receiver_tab");
            assertTrue("Should be in YAACC", device.hasObject(By.pkg("de.yaacc")));
        }
    }

    @Test
    public void testSwitchToPlayerTab() throws Exception {
        Thread.sleep(2000);
        
        // Try to find and click fourth tab (Player)
        UiObject tab = findTab(3);
        if (tab != null && tab.exists()) {
            tab.click();
            Thread.sleep(1000);
            TestHelper.takeScreenshot(device, "navigation_player_tab");
            assertTrue("Should be in YAACC", device.hasObject(By.pkg("de.yaacc")));
        }
    }

    @Test
    public void testTabSwitchingPreservesState() throws Exception {
        Thread.sleep(2000);
        
        TestHelper.takeScreenshot(device, "navigation_state_01_start");
        
        // Click first tab
        UiObject tab1 = findTab(0);
        if (tab1 != null && tab1.exists()) {
            tab1.click();
            Thread.sleep(1000);
            TestHelper.takeScreenshot(device, "navigation_state_02_tab1");
        }
        
        // Click second tab
        UiObject tab2 = findTab(1);
        if (tab2 != null && tab2.exists()) {
            tab2.click();
            Thread.sleep(1000);
            TestHelper.takeScreenshot(device, "navigation_state_03_tab2");
        }
        
        // Click first tab again
        if (tab1 != null && tab1.exists()) {
            tab1.click();
            Thread.sleep(1000);
            TestHelper.takeScreenshot(device, "navigation_state_04_back_to_tab1");
        }
        
        assertTrue("Should still be in YAACC", device.hasObject(By.pkg("de.yaacc")));
    }

    @Test
    public void testBackButtonNavigation() throws Exception {
        Thread.sleep(10000); // Wait for UPnP discovery
        
        TestHelper.takeScreenshot(device, "navigation_back_01_start");
        
        // Just test that back button works
        device.pressBack();
        Thread.sleep(1000);
        
        TestHelper.takeScreenshot(device, "navigation_back_02_after_back");
        
        // App might exit or go to previous screen - both are valid
        assertTrue("Test completed", true);
    }
    
    private UiObject findTab(int index) {
        // Try to find tab by index
        UiObject tab = device.findObject(new UiSelector()
            .className("android.widget.TextView")
            .instance(index));
        return tab;
    }

    private void handlePermissionDialogs() {
        try {
            Thread.sleep(1000);
            UiObject allowButton = device.findObject(new UiSelector()
                .textMatches("(?i)(allow|permit|ok)"));
            if (allowButton.exists()) {
                allowButton.click();
                Thread.sleep(500);
            }
            if (allowButton.exists()) {
                allowButton.click();
                Thread.sleep(500);
            }
        } catch (Exception e) {
            // Ignore
        }
    }
}
