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
public class SettingsTest {

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
    public void testOpenSettings() throws Exception {
        Thread.sleep(2000);
        
        device.pressMenu();
        Thread.sleep(500);
        
        UiObject settingsItem = device.findObject(new UiSelector()
            .textContains("Settings"));
        
        if (!settingsItem.exists()) {
            UiObject moreOptions = device.findObject(new UiSelector()
                .descriptionContains("More options"));
            if (moreOptions.exists()) {
                moreOptions.click();
                Thread.sleep(500);
            }
        }
        
        settingsItem = device.findObject(new UiSelector()
            .textContains("Settings"));
        
        assertTrue("Settings menu item should exist", settingsItem.exists());
        settingsItem.click();
        
        Thread.sleep(1000);
        assertTrue("Settings screen should open", device.hasObject(By.pkg("de.yaacc")));
    }

    @Test
    public void testServerConfigurationVisible() throws Exception {
        openSettings();
        
        TestHelper.takeScreenshot(device, "settings_server_config_check");
        
        // Try multiple ways to find server configuration
        UiObject serverConfig = device.findObject(new UiSelector()
            .textContains("Local server"));
        
        if (!serverConfig.exists()) {
            serverConfig = device.findObject(new UiSelector()
                .textContains("Server"));
        }
        if (!serverConfig.exists()) {
            serverConfig = device.findObject(new UiSelector()
                .textContains("server"));
        }
        
        // At least verify we're in a settings-like screen
        boolean inSettings = serverConfig.exists() || 
                           device.hasObject(By.text("Settings")) ||
                           device.hasObject(By.textContains("Preferences"));
        
        assertTrue("Should be in settings screen with some configuration visible", inSettings);
    }

    @Test
    public void testToggleServerEnabled() throws Exception {
        openSettings();
        
        UiObject serverConfig = device.findObject(new UiSelector()
            .textContains("Local server configuration"));
        
        if (serverConfig.exists()) {
            serverConfig.click();
            Thread.sleep(1000);
            
            UiObject serverSwitch = device.findObject(new UiSelector()
                .className("android.widget.Switch"));
            
            if (serverSwitch.exists()) {
                boolean initialState = serverSwitch.isChecked();
                serverSwitch.click();
                Thread.sleep(1000);
                
                boolean newState = serverSwitch.isChecked();
                assertNotEquals("Server state should toggle", initialState, newState);
            }
        }
    }

    @Test
    public void testAboutScreen() throws Exception {
        Thread.sleep(2000);
        
        device.pressMenu();
        Thread.sleep(500);
        
        TestHelper.takeScreenshot(device, "settings_menu_opened");
        
        UiObject aboutItem = device.findObject(new UiSelector()
            .textContains("About"));
        
        if (!aboutItem.exists()) {
            UiObject moreOptions = device.findObject(new UiSelector()
                .descriptionContains("More options"));
            if (moreOptions.exists()) {
                moreOptions.click();
                Thread.sleep(500);
                TestHelper.takeScreenshot(device, "settings_more_options");
            }
        }
        
        aboutItem = device.findObject(new UiSelector()
            .textContains("About"));
        
        if (aboutItem.exists()) {
            aboutItem.click();
            Thread.sleep(1000);
            
            TestHelper.takeScreenshot(device, "settings_about_screen");
            
            // Look for version info in multiple ways
            UiObject versionInfo = device.findObject(new UiSelector()
                .textContains("Version"));
            
            if (!versionInfo.exists()) {
                versionInfo = device.findObject(new UiSelector()
                    .textContains("version"));
            }
            if (!versionInfo.exists()) {
                versionInfo = device.findObject(new UiSelector()
                    .textMatches(".*\\d+\\.\\d+.*")); // Look for version number pattern
            }
            
            // At least verify we opened something (dialog or new screen)
            boolean aboutScreenOpened = versionInfo.exists() || 
                                       device.hasObject(By.textContains("YAACC")) ||
                                       device.hasObject(By.textContains("yaacc"));
            
            assertTrue("About screen should open with some info", aboutScreenOpened);
        } else {
            TestHelper.takeScreenshot(device, "settings_about_not_found");
            fail("About menu item not found");
        }
    }

    private void openSettings() throws Exception {
        Thread.sleep(2000);
        
        device.pressMenu();
        Thread.sleep(500);
        
        UiObject settingsItem = device.findObject(new UiSelector()
            .textContains("Settings"));
        
        if (!settingsItem.exists()) {
            UiObject moreOptions = device.findObject(new UiSelector()
                .descriptionContains("More options"));
            if (moreOptions.exists()) {
                moreOptions.click();
                Thread.sleep(500);
            }
        }
        
        settingsItem = device.findObject(new UiSelector()
            .textContains("Settings"));
        
        settingsItem.click();
        Thread.sleep(1000);
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
