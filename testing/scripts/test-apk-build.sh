#!/bin/bash
set -e

echo "YAACC APK Build and Install Test"
echo "================================"

# Navigate to project root
cd "$(dirname "$0")/../.."

# Build the APK
echo "Building YAACC debug APK..."
./gradlew assembleDebug --no-daemon

# Check if APK was created
APK_PATH="yaacc/build/outputs/apk/debug/yaacc-debug.apk"
if [ -f "$APK_PATH" ]; then
    echo "✅ APK built successfully: $APK_PATH"
    
    # Show APK info
    APK_SIZE=$(ls -lh "$APK_PATH" | awk '{print $5}')
    echo "✅ APK size: $APK_SIZE"
    
    # Verify APK structure (if aapt is available)
    if command -v aapt &> /dev/null; then
        if aapt dump badging "$APK_PATH" | grep -q "package: name='de.yaacc'"; then
            echo "✅ APK package verified: de.yaacc"
        else
            echo "❌ APK package verification failed"
        fi
    else
        echo "ℹ️  aapt not available - skipping package verification"
    fi
    
    # Check if device is connected
    if command -v adb &> /dev/null && adb devices | grep -q "device$"; then
        echo "📱 Android device detected, installing APK..."
        adb install -r "$APK_PATH"
        echo "✅ APK installed successfully"
        
        # Verify installation
        if adb shell pm list packages | grep -q "de.yaacc"; then
            echo "✅ YAACC package verified on device"
        else
            echo "❌ YAACC package not found on device"
        fi
    else
        echo "ℹ️  No Android device connected - APK build verified only"
    fi
    
else
    echo "❌ APK build failed - file not found: $APK_PATH"
    exit 1
fi

echo "APK test completed successfully!"
