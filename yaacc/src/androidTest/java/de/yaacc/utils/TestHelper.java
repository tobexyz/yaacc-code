package de.yaacc.utils;

import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import androidx.test.uiautomator.By;

import android.content.Context;
import android.content.Intent;

public class TestHelper {
    
    public static final int DEFAULT_TIMEOUT = 10000;
    public static final int DISCOVERY_TIMEOUT = 15000;
    
    /**
     * Launch YAACC app and wait for it to load
     */
    public static boolean launchYAACC(UiDevice device, Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage("de.yaacc");
        if (intent == null) {
            return false;
        }
        
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        
        return device.wait(Until.hasObject(By.pkg("de.yaacc")), DEFAULT_TIMEOUT);
    }
    
    /**
     * Navigate to a specific tab in YAACC
     */
    public static boolean navigateToTab(UiDevice device, String tabName) {
        try {
            UiObject tab = device.findObject(new UiSelector().text(tabName));
            if (tab.exists()) {
                tab.click();
                device.waitForIdle();
                return true;
            }
        } catch (Exception e) {
            // Tab navigation failed
        }
        return false;
    }
    
    /**
     * Wait for UPnP discovery to complete
     */
    public static boolean waitForUPnPDiscovery(UiDevice device, String deviceName, int timeoutMs) {
        return device.wait(Until.hasObject(By.textContains(deviceName)), timeoutMs);
    }
    
    /**
     * Check if YAACC is currently running
     */
    public static boolean isYAACCRunning(UiDevice device) {
        return device.hasObject(By.pkg("de.yaacc"));
    }
    
    /**
     * Handle permission dialogs (Allow/Permit/OK)
     */
    public static void handlePermissionDialogs(UiDevice device) {
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
            // Ignore if no dialogs
        }
    }
    
    /**
     * Take a screenshot with the given name
     */
    public static void takeScreenshot(UiDevice device, String name) {
        try {
            device.executeShellCommand("screencap -p /sdcard/" + name + ".png");
            Thread.sleep(500);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Select Gerbera server if on Server tab
     */
    public static boolean selectGerberaServer(UiDevice device) {
        try {
            Thread.sleep(DISCOVERY_TIMEOUT);
            
            UiObject serverTab = device.findObject(new UiSelector().text("Server"));
            if (serverTab.exists() && serverTab.isSelected()) {
                UiObject serverItem = device.findObject(new UiSelector()
                    .textContains("Gerbera"));
                
                if (serverItem.exists()) {
                    serverItem.click();
                    Thread.sleep(2000);
                    return true;
                }
            }
        } catch (Exception e) {
            // Selection failed
        }
        return false;
    }
    
    /**
     * Navigate to music folder (PC Directory -> audio -> mp3)
     */
    public static boolean navigateToMusicFolder(UiDevice device) {
        try {
            UiObject pcDirectory = device.findObject(new UiSelector()
                .textContains("PC Directory"));
            if (!pcDirectory.exists()) {
                return false;
            }
            pcDirectory.click();
            Thread.sleep(2000);
            
            UiObject audioFolder = device.findObject(new UiSelector()
                .textContains("audio"));
            if (!audioFolder.exists()) {
                return false;
            }
            audioFolder.click();
            Thread.sleep(2000);
            
            UiObject mp3Folder = device.findObject(new UiSelector()
                .textContains("mp3"));
            if (!mp3Folder.exists()) {
                return false;
            }
            mp3Folder.click();
            Thread.sleep(2000);
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Open settings menu
     */
    public static boolean openSettings(UiDevice device) {
        try {
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
            
            if (settingsItem.exists()) {
                settingsItem.click();
                Thread.sleep(1000);
                return true;
            }
        } catch (Exception e) {
            // Failed to open settings
        }
        return false;
    }
}
