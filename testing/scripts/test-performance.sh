#!/bin/bash
set -e

echo "YAACC Performance Monitoring"
echo "============================"

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo "Error: adb is not available"
    exit 1
fi

# Wait for device
echo "Waiting for Android device..."
adb wait-for-device

# Clear logs
echo "Clearing logcat..."
adb logcat -c

# Launch YAACC
echo "Launching YAACC..."
adb shell am start -n de.yaacc/.browser.TabBrowserActivity
sleep 5

# Trigger SAF browsing (simulate user interaction)
echo "Simulating SAF browsing..."
adb shell input tap 200 400  # Servers tab
sleep 2
adb shell input tap 200 500  # Local device
sleep 2
adb shell input tap 200 600  # SAF folder (if available)
sleep 5

# Capture performance metrics
echo "Capturing performance data..."
adb logcat -d | grep -E "(browseItem END|CACHE_HIT|CACHE_MISS|createItem)" > ../reports/performance/performance-$(date +%Y%m%d-%H%M%S).log

# Parse results
PERFORMANCE_LOG="../reports/performance/performance-$(date +%Y%m%d-%H%M%S).log"
if [ -f "$PERFORMANCE_LOG" ]; then
    BROWSE_TIME=$(grep "browseItem END" "$PERFORMANCE_LOG" | tail -1 | grep -o "total [0-9]*ms" | grep -o "[0-9]*" || echo "0")
    CACHE_HITS=$(grep "CACHE_HIT" "$PERFORMANCE_LOG" | wc -l)
    CACHE_MISSES=$(grep "CACHE_MISS" "$PERFORMANCE_LOG" | wc -l)
    
    echo ""
    echo "Performance Results:"
    echo "==================="
    echo "Browse time: ${BROWSE_TIME}ms"
    echo "Cache hits: $CACHE_HITS"
    echo "Cache misses: $CACHE_MISSES"
    
    # Calculate hit rate
    if [ $((CACHE_HITS + CACHE_MISSES)) -gt 0 ]; then
        HIT_RATE=$((CACHE_HITS * 100 / (CACHE_HITS + CACHE_MISSES)))
        echo "Cache hit rate: ${HIT_RATE}%"
        
        # Performance checks
        if [ "$BROWSE_TIME" -gt 2000 ]; then
            echo "WARNING: Browse time too slow (${BROWSE_TIME}ms > 2000ms)"
        fi
        
        if [ "$HIT_RATE" -lt 80 ]; then
            echo "WARNING: Cache hit rate too low (${HIT_RATE}% < 80%)"
        fi
    fi
    
    echo ""
    echo "Detailed log saved to: $PERFORMANCE_LOG"
else
    echo "No performance data captured"
fi
