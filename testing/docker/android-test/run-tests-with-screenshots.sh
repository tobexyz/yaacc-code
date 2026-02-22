#!/bin/bash
set -e

echo "Installing YAACC..."
adb install -r /workspace/yaacc-debug.apk

echo "Installing YAACC test APK..."
adb install -r /workspace/yaacc-test.apk

echo "Granting permissions..."
adb shell pm grant de.yaacc android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant de.yaacc android.permission.WRITE_EXTERNAL_STORAGE || true
adb shell pm grant de.yaacc android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant de.yaacc android.permission.ACCESS_COARSE_LOCATION || true
adb shell pm grant de.yaacc android.permission.POST_NOTIFICATIONS || true

echo "Dismissing permission dialogs..."
adb shell input keyevent KEYCODE_ENTER || true
sleep 1
adb shell input keyevent KEYCODE_ENTER || true

echo "Starting screen recording..."
adb shell "nohup screenrecord /sdcard/test_recording.mp4 > /dev/null 2>&1 &"
sleep 2

echo "Running tests..."
adb shell am instrument -w -r \
  -e class de.yaacc.ServerSelectionTest \
  de.yaacc.test/androidx.test.runner.AndroidJUnitRunner

echo "Stopping screen recording..."
adb shell "pkill screenrecord"
sleep 2

echo "Pulling screenshots and video..."
mkdir -p /tmp/screenshots
adb pull /sdcard/*.png /tmp/screenshots/ 2>/dev/null || echo "No screenshots"
adb pull /sdcard/test_recording.mp4 /tmp/screenshots/ 2>/dev/null || echo "No video"

echo "Done! Files at: /tmp/screenshots/"
ls -lh /tmp/screenshots/
