#!/bin/bash
set -e

echo "Starting automated YAACC UPnP tests..."

# Wait for UPnP services
echo "Waiting for UPnP services..."
timeout 60 bash -c 'until nc -z gerbera 49494; do sleep 1; done'
echo "✅ Gerbera UPnP server is ready"

timeout 60 bash -c 'until nc -z vlc-renderer 5800; do sleep 1; done'
echo "✅ VLC renderer is ready"

# Create and start emulator
echo "Starting Android emulator (cold boot)..."

# Kill any existing emulator processes
pkill -9 emulator || true
pkill -9 qemu-system || true
sleep 2

emulator -avd test_avd -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -memory 2048 -partition-size 512 &
EMULATOR_PID=$!

# Wait for emulator boot
echo "Waiting for emulator boot..."
adb wait-for-device
echo "Device connected, waiting for boot completion..."

# More robust boot detection
timeout 300 bash -c '
while true; do
    boot_completed=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")
    if [ "$boot_completed" = "1" ]; then
        echo "Boot completed!"
        break
    fi
    echo "Still booting... (boot_completed=$boot_completed)"
    sleep 5
done'

# Additional wait for system to stabilize
echo "Waiting for system to stabilize..."
sleep 10

# Install YAACC (pre-built APK mounted from host)
echo "Installing YAACC..."
adb install -r /workspace/yaacc-debug.apk

echo "Installing YAACC test APK..."
adb install -r /workspace/yaacc-test.apk

# Grant permissions
echo "Granting permissions..."
adb shell pm grant de.yaacc android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant de.yaacc android.permission.WRITE_EXTERNAL_STORAGE || true

# List installed instrumentation to verify
echo "Checking installed instrumentation..."
adb shell pm list instrumentation | grep yaacc

# Run automated tests
echo "Running automated UI tests..."
adb shell am instrument -w -r \
  -e class de.yaacc.AutomatedUPnPTest \
  de.yaacc.test/androidx.test.runner.AndroidJUnitRunner

echo "Tests completed successfully!"

# Cleanup
echo "Stopping emulator..."
kill $EMULATOR_PID 2>/dev/null || true
pkill -9 emulator || true
pkill -9 qemu-system || true
