#!/bin/bash
set -e

echo "Installing YAACC..."
adb install -r /workspace/yaacc-debug.apk

echo "Installing YAACC test APK..."
adb install -r /workspace/yaacc-test.apk

echo "Granting permissions..."
adb shell pm grant de.yaacc android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant de.yaacc android.permission.WRITE_EXTERNAL_STORAGE || true

echo "Running tests..."
adb shell am instrument -w -r \
  -e class de.yaacc.ServerSelectionTest \
  de.yaacc.test/androidx.test.runner.AndroidJUnitRunner

echo "Done!"
