package de.yaacc;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import androidx.test.uiautomator.By;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ServerSelectionTest {

    private UiDevice device;
    private static final int TIMEOUT = 10000;

    @Before
    public void setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        
        // Launch YAACC
        device.pressHome();
        device.wait(Until.hasObject(By.pkg("com.android.launcher3")), TIMEOUT);
        
        InstrumentationRegistry.getInstrumentation().getContext()
            .startActivity(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getPackageManager().getLaunchIntentForPackage("de.yaacc")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        
        device.wait(Until.hasObject(By.pkg("de.yaacc")), TIMEOUT);
        
        // Handle permission dialogs
        try {
            Thread.sleep(1000);
            UiObject allowButton = device.findObject(new UiSelector()
                .textMatches("(?i)(allow|permit|ok)"));
            if (allowButton.exists()) {
                allowButton.click();
                Thread.sleep(500);
            }
            // Try again for second dialog
            if (allowButton.exists()) {
                allowButton.click();
                Thread.sleep(500);
            }
        } catch (Exception e) {
            // Ignore if no dialogs
        }
    }

    @Test
    public void testServerSelectionSwitchesToContentTab() throws Exception {
        // Wait for app to load and UPnP discovery
        Thread.sleep(10000);
        
        // Screenshot 1: Before clicking
        device.takeScreenshot(new java.io.File("/sdcard/01_before_click.png"));
        
        // Find and click on Gerbera server
        UiObject serverItem = device.findObject(new UiSelector()
            .textContains("Gerbera"));
        
        if (!serverItem.exists()) {
            serverItem = device.findObject(new UiSelector()
                .textMatches("(?i).*gerbera.*"));
        }
        
        assertTrue("Gerbera server should be visible", serverItem.waitForExists(TIMEOUT));
        serverItem.click();
        
        // Screenshot 2: After clicking
        Thread.sleep(2000);
        device.takeScreenshot(new java.io.File("/sdcard/02_after_click.png"));
        
        // Verify content list is visible
        UiObject contentList = device.findObject(new UiSelector()
            .className("android.widget.ListView"));
        
        if (!contentList.exists()) {
            contentList = device.findObject(new UiSelector()
                .className("androidx.recyclerview.widget.RecyclerView"));
        }
        
        // Screenshot 3: Final state
        device.takeScreenshot(new java.io.File("/sdcard/03_final_state.png"));
        
        assertTrue("Content list should be visible", contentList.waitForExists(TIMEOUT));
        
        // Verify content is loaded
        UiObject firstItem = device.findObject(new UiSelector()
            .className("android.widget.TextView")
            .instance(0));
        
        assertTrue("Content items should be visible", firstItem.exists());
    }
}
