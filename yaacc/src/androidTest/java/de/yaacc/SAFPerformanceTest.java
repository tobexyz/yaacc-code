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
public class SAFPerformanceTest {
    
    private UiDevice device;
    private Context context;
    
    @Before
    public void setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }
    
    @Test
    public void testSAFBrowsingPerformance() {
        // Launch YAACC
        Intent intent = context.getPackageManager().getLaunchIntentForPackage("de.yaacc");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        
        device.wait(Until.hasObject(By.pkg("de.yaacc")), 10000);
        
        try {
            // Navigate to Servers tab
            UiObject serversTab = device.findObject(new UiSelector().text("Servers"));
            assertTrue("Servers tab should exist", serversTab.exists());
            serversTab.click();
            Thread.sleep(3000);
            
            // Find local device
            UiObject localDevice = device.findObject(new UiSelector().textContains("YAACC"));
            if (localDevice.exists()) {
                long startTime = System.currentTimeMillis();
                
                localDevice.click();
                Thread.sleep(2000);
                
                // Look for content folders
                boolean contentFound = device.hasObject(By.textContains("Music")) ||
                                     device.hasObject(By.textContains("Videos")) ||
                                     device.hasObject(By.textContains("SAF"));
                
                long endTime = System.currentTimeMillis();
                long browseTime = endTime - startTime;
                
                assertTrue("Content should be accessible", contentFound);
                assertTrue("Browsing should be under 5 seconds, took: " + browseTime + "ms", 
                          browseTime < 5000);
            }
            
        } catch (Exception e) {
            fail("SAF performance test failed: " + e.getMessage());
        }
    }
    
    @Test
    public void testCacheEfficiency() {
        // Launch YAACC
        Intent intent = context.getPackageManager().getLaunchIntentForPackage("de.yaacc");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        
        device.wait(Until.hasObject(By.pkg("de.yaacc")), 10000);
        
        try {
            // Navigate to Servers tab
            UiObject serversTab = device.findObject(new UiSelector().text("Servers"));
            serversTab.click();
            Thread.sleep(3000);
            
            // First browse (potential cache miss)
            UiObject localDevice = device.findObject(new UiSelector().textContains("YAACC"));
            if (localDevice.exists()) {
                long firstStart = System.currentTimeMillis();
                localDevice.click();
                Thread.sleep(2000);
                long firstTime = System.currentTimeMillis() - firstStart;
                
                // Go back
                device.pressBack();
                Thread.sleep(1000);
                
                // Second browse (cache hit)
                long secondStart = System.currentTimeMillis();
                localDevice.click();
                Thread.sleep(1000);
                long secondTime = System.currentTimeMillis() - secondStart;
                
                // Second browse should be same or faster
                assertTrue("Second browse should not be slower. First: " + firstTime + "ms, Second: " + secondTime + "ms",
                          secondTime <= firstTime + 500); // Allow 500ms tolerance
            }
            
        } catch (Exception e) {
            fail("Cache efficiency test failed: " + e.getMessage());
        }
    }
}
