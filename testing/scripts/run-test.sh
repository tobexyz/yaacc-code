#!/bin/bash
set -e

TEST_CLASS=$1
PROJECT_ROOT="/home/tobias/src/github.com/tobexyz/yaacc-code"

echo "=== Building APKs ==="
cd "$PROJECT_ROOT"
./gradlew assembleDebug assembleDebugAndroidTest

echo ""
echo "=== Installing APKs ==="
docker exec yaacc-test adb install -r /workspace/yaacc-debug.apk
docker exec yaacc-test adb install -r /workspace/yaacc-test.apk

echo ""
echo "=== Granting Permissions ==="
docker exec yaacc-test adb shell pm grant de.yaacc android.permission.READ_EXTERNAL_STORAGE || true
docker exec yaacc-test adb shell pm grant de.yaacc android.permission.WRITE_EXTERNAL_STORAGE || true
docker exec yaacc-test adb shell pm grant de.yaacc android.permission.POST_NOTIFICATIONS || true

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BASE_REPORT_DIR="$PROJECT_ROOT/reports/$TIMESTAMP"
mkdir -p "$BASE_REPORT_DIR"

if [ -z "$TEST_CLASS" ]; then
    echo ""
    echo "=== Running All Test Classes ==="
    
    TEST_CLASSES=(
        "ReceiverControlsTest"
        "ContentBrowsingTest"
        "PlaybackControlTest"
        "SettingsTest"
        "NavigationTest"
        "UPnPDiscoveryTest"
        "ServerSelectionTest"
        "SAFPerformanceTest"
        "AutomatedUPnPTest"
    )
    
    for CLASS in "${TEST_CLASSES[@]}"; do
        echo ""
        echo "=== Running: $CLASS ==="
        
        # Clear previous screenshots
        docker exec yaacc-test adb shell "rm -f /sdcard/*.png /sdcard/*.mp4" || true
        
        # Start video recording
        VIDEO_NAME="${CLASS}_${TIMESTAMP}.mp4"
        docker exec -d yaacc-test adb shell "screenrecord --time-limit 180 /sdcard/$VIDEO_NAME"
        sleep 2
        
        # Run test
        docker exec yaacc-test adb shell am instrument -w -e class de.yaacc.$CLASS \
            de.yaacc.test/androidx.test.runner.AndroidJUnitRunner || echo "Test failed or completed"
        
        # Stop recording
        docker exec yaacc-test adb shell "pkill -SIGINT screenrecord" || true
        sleep 3
        
        # Extract results for this test class
        CLASS_REPORT_DIR="$BASE_REPORT_DIR/$CLASS"
        mkdir -p "$CLASS_REPORT_DIR"
        
        echo "Copying results for $CLASS..."
        # Copy video
        docker exec yaacc-test adb pull /sdcard/$VIDEO_NAME /tmp/$VIDEO_NAME 2>/dev/null || true
        docker cp yaacc-test:/tmp/$VIDEO_NAME "$CLASS_REPORT_DIR/" 2>/dev/null || true
        
        # Copy screenshots
        for png in $(docker exec yaacc-test adb shell "ls /sdcard/*.png 2>/dev/null" | tr -d '\r'); do
            FILENAME=$(basename "$png")
            docker exec yaacc-test adb pull "$png" /tmp/$FILENAME 2>/dev/null || true
            docker cp yaacc-test:/tmp/$FILENAME "$CLASS_REPORT_DIR/" 2>/dev/null || true
        done
        
        echo "Results saved to: $CLASS_REPORT_DIR/"
        ls -lh "$CLASS_REPORT_DIR/" 2>/dev/null || echo "No files"
    done
    
    echo ""
    echo "=== All Tests Complete ==="
    echo "Results saved to: $BASE_REPORT_DIR/"
    ls -lh "$BASE_REPORT_DIR/"
    
else
    echo ""
    echo "=== Running Test Class: $TEST_CLASS ==="
    
    # Clear previous screenshots
    docker exec yaacc-test adb shell "rm -f /sdcard/*.png /sdcard/*.mp4" || true
    
    # Start video recording
    VIDEO_NAME="${TEST_CLASS}_${TIMESTAMP}.mp4"
    docker exec -d yaacc-test adb shell "screenrecord --time-limit 180 /sdcard/$VIDEO_NAME"
    sleep 2
    
    # Run test
    docker exec yaacc-test adb shell am instrument -w -e class de.yaacc.$TEST_CLASS \
        de.yaacc.test/androidx.test.runner.AndroidJUnitRunner
    
    # Stop recording
    docker exec yaacc-test adb shell "pkill -SIGINT screenrecord" || true
    sleep 3
    
    # Extract results
    echo ""
    echo "=== Copying Test Results ==="
    # Copy video
    docker exec yaacc-test adb pull /sdcard/$VIDEO_NAME /tmp/$VIDEO_NAME 2>/dev/null || true
    docker cp yaacc-test:/tmp/$VIDEO_NAME "$BASE_REPORT_DIR/" 2>/dev/null || true
    
    # Copy screenshots
    for png in $(docker exec yaacc-test adb shell "ls /sdcard/*.png 2>/dev/null" | tr -d '\r'); do
        FILENAME=$(basename "$png")
        docker exec yaacc-test adb pull "$png" /tmp/$FILENAME 2>/dev/null || true
        docker cp yaacc-test:/tmp/$FILENAME "$BASE_REPORT_DIR/" 2>/dev/null || true
    done
    
    echo ""
    echo "Test results saved to: $BASE_REPORT_DIR/"
    ls -lh "$BASE_REPORT_DIR/" 2>/dev/null || echo "No files copied"
fi

echo ""
echo "To run specific test class:"
echo "  ./run-test.sh ReceiverControlsTest"
echo "  ./run-test.sh ContentBrowsingTest"
echo "  ./run-test.sh PlaybackControlTest"
echo "  ./run-test.sh SettingsTest"
echo "  ./run-test.sh NavigationTest"
echo "  ./run-test.sh UPnPDiscoveryTest"
