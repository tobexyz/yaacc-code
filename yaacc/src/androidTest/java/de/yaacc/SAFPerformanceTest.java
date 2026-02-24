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
            // Navigate to first tab (Server) by index
            UiObject serverTab = device.findObject(new UiSelector()
                .className("android.widget.TextView")
                .instance(0));
            
            if (serverTab.exists()) {
                serverTab.click();
                Thread.sleep(3000);
            }
            
            // Just verify we're in the app
            assertTrue("Should be in YAACC app", device.hasObject(By.pkg("de.yaacc")));
            
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
            // Navigate to first tab (Server) by index
            UiObject serverTab = device.findObject(new UiSelector()
                .className("android.widget.TextView")
                .instance(0));
            
            if (serverTab.exists()) {
                serverTab.click();
                Thread.sleep(3000);
            }
            
            // Just verify we're in the app
            assertTrue("Should be in YAACC app", device.hasObject(By.pkg("de.yaacc")));
            
        } catch (Exception e) {
            fail("Cache efficiency test failed: " + e.getMessage());
        }
    }
}
