#!/bin/bash
set -e

# Find project root (contains testing/ directory)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT/testing"

echo "=== Starting UPnP Services ==="
docker compose -f docker/docker-compose.test.yml up -d gerbera upnp-renderer

echo "Waiting for services to be ready..."
sleep 3

echo ""
echo "=== Starting Android Test Container ==="
docker rm -f yaacc-test 2>/dev/null || true
docker compose -f docker/docker-compose.test.yml run -d --name yaacc-test android-test bash -c "
adb start-server
pkill -9 emulator || true
sleep 2
emulator -avd test_avd -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -memory 2048 &
sleep 40
adb wait-for-device
adb shell 'while [[ -z \$(getprop sys.boot_completed) ]]; do sleep 1; done'
echo '✅ Emulator ready! Run: ./scripts/run-test.sh'
exec tail -f /dev/null
"

echo ""
echo "Waiting for emulator to boot..."
sleep 45

echo ""
echo "=== Test Environment Ready ==="
echo "Services running:"
docker compose -f docker/docker-compose.test.yml ps
echo ""
echo "Run tests with: ./scripts/run-test.sh"
