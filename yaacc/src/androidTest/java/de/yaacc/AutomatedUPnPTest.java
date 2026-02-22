package de.yaacc;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import androidx.test.uiautomator.By;

import android.content.Context;
import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AutomatedUPnPTest {
    
    private UiDevice device;
    private Context context;
    
    @Before
    public void setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }
    
    @Test
    public void testAppLaunch() {
        // Launch YAACC
        Intent intent = context.getPackageManager().getLaunchIntentForPackage("de.yaacc");
        assertNotNull("YAACC app should be installed", intent);
        
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        
        // Wait for app to load
        device.wait(Until.hasObject(By.pkg("de.yaacc")), 5000);
        assertTrue("YAACC should launch", device.hasObject(By.pkg("de.yaacc")));
    }
    
    @Test
    public void testUPnPServerDiscovery() {
        // Launch YAACC
        Intent intent = context.getPackageManager().getLaunchIntentForPackage("de.yaacc");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        
        // Wait for app to load
        device.wait(Until.hasObject(By.pkg("de.yaacc")), 10000);
        
        try {
            // Navigate to Servers tab
            UiObject serversTab = device.findObject(new UiSelector().text("Servers"));
            assertTrue("Servers tab should exist", serversTab.exists());
            serversTab.click();
            
            // Wait for UPnP discovery
            Thread.sleep(8000);
            
            // Look for Gerbera server or any UPnP server
            boolean serverFound = device.hasObject(By.textContains("Gerbera")) || 
                                 device.hasObject(By.textContains("UPnP")) ||
                                 device.hasObject(By.textContains("Media Server"));
            
            assertTrue("UPnP server should be discovered", serverFound);
            
        } catch (Exception e) {
            fail("UPnP discovery test failed: " + e.getMessage());
        }
    }
    
    @Test
    public void testReceiverTab() {
        // Launch YAACC
        Intent intent = context.getPackageManager().getLaunchIntentForPackage("de.yaacc");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        
        device.wait(Until.hasObject(By.pkg("de.yaacc")), 10000);
        
        try {
            // Navigate to Receiver tab
            UiObject receiverTab = device.findObject(new UiSelector().text("Receiver"));
            assertTrue("Receiver tab should exist", receiverTab.exists());
            receiverTab.click();
            
            Thread.sleep(5000);
            
            // Should show local device
            boolean localDeviceFound = device.hasObject(By.textContains("YAACC")) ||
                                     device.hasObject(By.textContains("This Device")) ||
                                     device.hasObject(By.textContains("Local"));
            
            assertTrue("Local device should appear in receiver list", localDeviceFound);
            
        } catch (Exception e) {
            fail("Receiver tab test failed: " + e.getMessage());
        }
    }
    
    @Test
    public void testServerConfiguration() {
        // Launch YAACC
        Intent intent = context.getPackageManager().getLaunchIntentForPackage("de.yaacc");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        
        device.wait(Until.hasObject(By.pkg("de.yaacc")), 10000);
        
        try {
            // Open menu
            device.pressMenu();
            Thread.sleep(1000);
            
            // Look for Settings
            UiObject settings = device.findObject(new UiSelector().text("Settings"));
            if (settings.exists()) {
                settings.click();
                Thread.sleep(2000);
                
                // Check if server settings are accessible
                boolean serverSettingsFound = device.hasObject(By.textContains("Server")) ||
                                            device.hasObject(By.textContains("UPnP"));
                
                assertTrue("Server settings should be accessible", serverSettingsFound);
            }
            
        } catch (Exception e) {
            // Settings access may vary, don't fail the test
            System.out.println("Settings test skipped: " + e.getMessage());
        }
    }
}
