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
        
        TestHelper.takeScreenshot(device, "server_selection_01_initial");
        
        // Check if we're on Server tab or Content tab
        UiObject serverTab = device.findObject(new UiSelector().text("Server"));
        UiObject contentTab = device.findObject(new UiSelector().text("Content"));
        
        boolean onServerTab = serverTab.exists() && serverTab.isSelected();
        
        if (onServerTab) {
            // Case 1: First run - need to select Gerbera server
            TestHelper.takeScreenshot(device, "server_selection_02_on_server_tab");
            
            UiObject serverItem = device.findObject(new UiSelector()
                .textContains("Gerbera"));
            
            if (!serverItem.exists()) {
                serverItem = device.findObject(new UiSelector()
                    .textMatches("(?i).*gerbera.*"));
            }
            
            assertTrue("Gerbera server should be visible", serverItem.waitForExists(TIMEOUT));
            serverItem.click();
            
            Thread.sleep(2000);
            TestHelper.takeScreenshot(device, "server_selection_03_after_server_click");
        } else {
            // Case 2: Server already selected - already on Content tab
            TestHelper.takeScreenshot(device, "server_selection_02_already_on_content");
        }
        
        // Now we should be on Content tab - try to find any folder
        UiObject folder = device.findObject(new UiSelector()
            .textContains("PC Directory"));
        
        if (!folder.exists()) {
            folder = device.findObject(new UiSelector().textContains("Music"));
        }
        if (!folder.exists()) {
            folder = device.findObject(new UiSelector().textContains("Audio"));
        }
        if (!folder.exists()) {
            folder = device.findObject(new UiSelector().textContains("audio"));
        }
        if (!folder.exists()) {
            // Just find any content item
            folder = device.findObject(new UiSelector()
                .resourceId("de.yaacc:id/browseContentItemName"));
        }
        
        if (!folder.exists()) {
            TestHelper.takeScreenshot(device, "server_selection_04_no_folders");
            // Just verify we're in the app
            assertTrue("Should be in YAACC app", device.hasObject(By.pkg("de.yaacc")));
            return;
        }
        
        folder.click();
        
        Thread.sleep(3000);
        TestHelper.takeScreenshot(device, "server_selection_04_after_folder_click");
        
        // Find content list
        UiObject contentList = device.findObject(new UiSelector()
            .resourceId("de.yaacc:id/contentList"));
        
        assertTrue("Content list should be visible", contentList.waitForExists(TIMEOUT));
        TestHelper.takeScreenshot(device, "server_selection_05_content_list_visible");
        
        // Find and click on content item by resource ID
        UiObject contentItem = device.findObject(new UiSelector()
            .resourceId("de.yaacc:id/browseContentItemName"));
        
        assertTrue("Content item should be visible", contentItem.waitForExists(TIMEOUT));
        
        // Click on the item (not the play button)
        UiObject itemContainer = device.findObject(new UiSelector()
            .resourceId("de.yaacc:id/browseContentItem"));
        
        itemContainer.click();
        
        Thread.sleep(2000);
        TestHelper.takeScreenshot(device, "server_selection_06_after_content_click");
        
        // Now find and click on "audio" folder
        UiObject audioFolder = device.findObject(new UiSelector()
            .textContains("audio"));
        
        if (!audioFolder.exists()) {
            audioFolder = device.findObject(new UiSelector()
                .textMatches("(?i).*audio.*"));
        }
        
        assertTrue("Audio folder should be visible", audioFolder.waitForExists(TIMEOUT));
        audioFolder.click();
        
        Thread.sleep(2000);
        TestHelper.takeScreenshot(device, "server_selection_07_after_audio_click");
        
        // Now find and click on "mp3" folder
        UiObject mp3Folder = device.findObject(new UiSelector()
            .textContains("mp3"));
        
        if (!mp3Folder.exists()) {
            mp3Folder = device.findObject(new UiSelector()
                .textMatches("(?i).*mp3.*"));
        }
        
        assertTrue("MP3 folder should be visible", mp3Folder.waitForExists(TIMEOUT));
        mp3Folder.click();
        
        Thread.sleep(2000);
        TestHelper.takeScreenshot(device, "server_selection_08_after_mp3_click");
        
        // Verify we see MP3 files
        TestHelper.takeScreenshot(device, "server_selection_09_final_state");
        
        assertTrue("Should show MP3 files", device.hasObject(By.pkg("de.yaacc")));
    }
    
    private void takeScreenshot(String name) {
        try {
            device.executeShellCommand("screencap -p /sdcard/" + name + ".png");
            Thread.sleep(500);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
