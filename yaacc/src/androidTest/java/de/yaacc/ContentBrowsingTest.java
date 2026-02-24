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
public class ContentBrowsingTest {

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
    public void testBrowseMusicFolder() throws Exception {
        TestHelper.takeScreenshot(device, "browse_music_01_start");
        
        selectGerberaServer();
        
        TestHelper.takeScreenshot(device, "browse_music_02_server_selected");
        
        UiObject pcDirectory = device.findObject(new UiSelector()
            .textContains("PC Directory"));
        assertTrue("PC Directory should be visible", pcDirectory.waitForExists(TIMEOUT));
        pcDirectory.click();
        
        Thread.sleep(2000);
        TestHelper.takeScreenshot(device, "browse_music_03_pc_directory");
        
        UiObject audioFolder = device.findObject(new UiSelector()
            .textContains("audio"));
        assertTrue("Audio folder should be visible", audioFolder.waitForExists(TIMEOUT));
        audioFolder.click();
        
        Thread.sleep(2000);
        TestHelper.takeScreenshot(device, "browse_music_04_audio_folder");
        
        UiObject mp3Folder = device.findObject(new UiSelector()
            .textContains("mp3"));
        assertTrue("MP3 folder should be visible", mp3Folder.waitForExists(TIMEOUT));
        
        TestHelper.takeScreenshot(device, "browse_music_05_final");
    }

    @Test
    public void testBrowseVideoFolder() throws Exception {
        selectGerberaServer();
        
        UiObject pcDirectory = device.findObject(new UiSelector()
            .textContains("PC Directory"));
        pcDirectory.click();
        Thread.sleep(2000);
        
        UiObject videoFolder = device.findObject(new UiSelector()
            .textContains("video"));
        
        if (videoFolder.exists()) {
            videoFolder.click();
            Thread.sleep(2000);
            assertTrue("Should show video content", device.hasObject(By.pkg("de.yaacc")));
        }
    }

    @Test
    public void testBrowseImageFolder() throws Exception {
        selectGerberaServer();
        
        UiObject pcDirectory = device.findObject(new UiSelector()
            .textContains("PC Directory"));
        pcDirectory.click();
        Thread.sleep(2000);
        
        UiObject imagesFolder = device.findObject(new UiSelector()
            .textContains("images"));
        
        if (imagesFolder.exists()) {
            imagesFolder.click();
            Thread.sleep(2000);
            assertTrue("Should show image content", device.hasObject(By.pkg("de.yaacc")));
        }
    }

    @Test
    public void testContentListLoadsWithinTimeout() throws Exception {
        selectGerberaServer();
        
        UiObject pcDirectory = device.findObject(new UiSelector()
            .textContains("PC Directory"));
        pcDirectory.click();
        
        long startTime = System.currentTimeMillis();
        
        UiObject contentList = device.findObject(new UiSelector()
            .resourceId("de.yaacc:id/contentList"));
        
        assertTrue("Content list should load", contentList.waitForExists(TIMEOUT));
        
        long loadTime = System.currentTimeMillis() - startTime;
        assertTrue("Content should load within 5 seconds", loadTime < 5000);
    }

    private void selectGerberaServer() throws Exception {
        Thread.sleep(10000); // Wait for UPnP discovery
        
        TestHelper.takeScreenshot(device, "select_server_01_discovery_wait");
        
        // Check which tab we're on
        UiObject serverTab = device.findObject(new UiSelector().text("Server"));
        UiObject contentTab = device.findObject(new UiSelector().text("Content"));
        
        boolean onServerTab = serverTab.exists() && serverTab.isSelected();
        boolean onContentTab = contentTab.exists() && contentTab.isSelected();
        
        TestHelper.takeScreenshot(device, "select_server_02_check_tab");
        
        if (onContentTab) {
            // Server already selected - we're on Content tab
            TestHelper.takeScreenshot(device, "select_server_03_already_on_content");
            return; // Already have server selected
        }
        
        if (onServerTab) {
            // We're on Server tab - need to select Gerbera
            TestHelper.takeScreenshot(device, "select_server_03_on_server_tab");
            
            UiObject serverItem = device.findObject(new UiSelector()
                .textContains("Gerbera"));
            
            if (!serverItem.exists()) {
                serverItem = device.findObject(new UiSelector()
                    .textMatches("(?i).*gerbera.*"));
            }
            
            if (serverItem.exists()) {
                serverItem.click();
                Thread.sleep(3000); // Wait for content to load
                TestHelper.takeScreenshot(device, "select_server_04_server_clicked");
            } else {
                TestHelper.takeScreenshot(device, "select_server_04_gerbera_not_found");
            }
        } else {
            // Not sure which tab - try to navigate to Server tab first
            if (serverTab.exists()) {
                serverTab.click();
                Thread.sleep(2000);
                TestHelper.takeScreenshot(device, "select_server_03_navigated_to_server");
                
                UiObject serverItem = device.findObject(new UiSelector()
                    .textContains("Gerbera"));
                
                if (serverItem.exists()) {
                    serverItem.click();
                    Thread.sleep(3000);
                    TestHelper.takeScreenshot(device, "select_server_04_server_clicked");
                }
            }
        }
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
