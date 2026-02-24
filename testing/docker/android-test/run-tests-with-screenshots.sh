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

echo "Running tests..."
adb shell am instrument -w -r \
  -e class de.yaacc.ServerSelectionTest \
  de.yaacc.test/androidx.test.runner.AndroidJUnitRunner &

TEST_PID=$!

echo "Waiting for app to launch..."
sleep 3

echo "Starting screen recording..."
adb shell "screenrecord --time-limit 60 /sdcard/test_recording.mp4 &"
sleep 1

echo "Waiting for test to complete..."
wait $TEST_PID

echo "Stopping screen recording..."
adb shell "pkill -SIGINT screenrecord" || true
sleep 3

echo "Checking all files on device sdcard..."
adb shell "ls -lh /sdcard/" | grep -E "\.(png|mp4)$" || echo "No media files found"

echo "Pulling screenshots and video..."
mkdir -p /tmp/screenshots

# Pull each file individually
for file in 01_initial_state.png 02_after_server_click.png 02_already_on_content.png 03_after_folder_click.png 04_content_list_visible.png 05_after_content_click.png 06_after_audio_click.png 07_after_mp3_click.png 08_final_state.png test_recording.mp4 ui_hierarchy.xml; do
    echo "Pulling $file..."
    adb pull /sdcard/$file /tmp/screenshots/ 2>&1 || echo "  -> Not found"
done

echo ""
echo "Done! Files at: /tmp/screenshots/"
ls -lh /tmp/screenshots/ || echo "Directory empty"
