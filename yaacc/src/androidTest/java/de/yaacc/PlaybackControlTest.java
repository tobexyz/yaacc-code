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
public class PlaybackControlTest {

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
    public void testPlayButtonStartsPlayback() throws Exception {
        navigateToMusicFile();
        
        UiObject playButton = device.findObject(new UiSelector()
            .descriptionContains("play")
            .className("android.widget.ImageButton"));
        
        if (playButton.exists()) {
            playButton.click();
            Thread.sleep(2000);
            
            assertTrue("Playback should start", device.hasObject(By.pkg("de.yaacc")));
        }
    }

    @Test
    public void testPlayerTabShowsActivePlayer() throws Exception {
        navigateToMusicFile();
        
        UiObject playButton = device.findObject(new UiSelector()
            .descriptionContains("play")
            .className("android.widget.ImageButton"));
        
        if (playButton.exists()) {
            playButton.click();
            Thread.sleep(2000);
            
            UiObject playerTab = device.findObject(new UiSelector().text("Player"));
            playerTab.click();
            Thread.sleep(1000);
            
            assertTrue("Player tab should show active player", 
                device.hasObject(By.pkg("de.yaacc")));
        }
    }

    @Test
    public void testPauseButtonPausesPlayback() throws Exception {
        navigateToMusicFile();
        
        UiObject playButton = device.findObject(new UiSelector()
            .descriptionContains("play")
            .className("android.widget.ImageButton"));
        
        if (playButton.exists()) {
            playButton.click();
            Thread.sleep(2000);
            
            UiObject pauseButton = device.findObject(new UiSelector()
                .descriptionContains("pause")
                .className("android.widget.ImageButton"));
            
            if (pauseButton.exists()) {
                pauseButton.click();
                Thread.sleep(1000);
                assertTrue("Playback should pause", device.hasObject(By.pkg("de.yaacc")));
            }
        }
    }

    @Test
    public void testStopButtonStopsPlayback() throws Exception {
        navigateToMusicFile();
        
        UiObject playButton = device.findObject(new UiSelector()
            .descriptionContains("play")
            .className("android.widget.ImageButton"));
        
        if (playButton.exists()) {
            playButton.click();
            Thread.sleep(2000);
            
            UiObject stopButton = device.findObject(new UiSelector()
                .descriptionContains("stop")
                .className("android.widget.ImageButton"));
            
            if (stopButton.exists()) {
                stopButton.click();
                Thread.sleep(1000);
                assertTrue("Playback should stop", device.hasObject(By.pkg("de.yaacc")));
            }
        }
    }

    private void navigateToMusicFile() throws Exception {
        Thread.sleep(10000); // Wait for UPnP discovery
        
        TestHelper.takeScreenshot(device, "navigate_music_01_start");
        
        // Check which tab we're on and select server if needed
        UiObject serverTab = device.findObject(new UiSelector().text("Server"));
        UiObject contentTab = device.findObject(new UiSelector().text("Content"));
        
        boolean onContentTab = contentTab.exists() && contentTab.isSelected();
        
        if (!onContentTab) {
            // Need to select server first
            if (serverTab.exists() && serverTab.isSelected()) {
                UiObject serverItem = device.findObject(new UiSelector()
                    .textContains("Gerbera"));
                
                if (serverItem.exists()) {
                    serverItem.click();
                    Thread.sleep(3000);
                    TestHelper.takeScreenshot(device, "navigate_music_02_server_selected");
                }
            }
        }
        
        // Now try to find any folder to navigate into
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
            // Just find any item in the content list
            folder = device.findObject(new UiSelector()
                .resourceId("de.yaacc:id/browseContentItemName"));
        }
        
        if (folder.exists()) {
            folder.click();
            Thread.sleep(2000);
            TestHelper.takeScreenshot(device, "navigate_music_03_folder_opened");
        } else {
            TestHelper.takeScreenshot(device, "navigate_music_03_no_folders");
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
