package de.yaacc.utils;

import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import androidx.test.uiautomator.By;

import android.content.Context;
import android.content.Intent;

public class TestHelper {
    
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
        
        // Wait for app to load
        return device.wait(Until.hasObject(By.pkg("de.yaacc")), 10000);
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
}
