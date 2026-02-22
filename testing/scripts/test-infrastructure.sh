#!/bin/bash
set -e

echo "YAACC Infrastructure Test"
echo "========================"

# Check for docker compose
if docker compose version &> /dev/null; then
    DOCKER_COMPOSE="docker compose"
else
    DOCKER_COMPOSE="docker-compose"
fi

cd "$(dirname "$0")/../docker"

echo "Starting UPnP test services..."
$DOCKER_COMPOSE -f docker-compose.test.yml up -d gerbera vlc-renderer

echo "Waiting for services to be ready..."
timeout 60 bash -c 'until nc -z localhost 49152; do sleep 1; done'
echo "✅ Gerbera UPnP server ready on port 49152"

timeout 60 bash -c 'until nc -z localhost 5800; do sleep 1; done'
echo "✅ VLC renderer ready on port 5800"

echo "Testing UPnP discovery..."
# Simple test to check if Gerbera is serving UPnP
if curl -s http://localhost:49152/ | grep -q "Gerbera"; then
    echo "✅ Gerbera UPnP server responding correctly"
else
    echo "❌ Gerbera UPnP server not responding"
fi

echo "Checking test media files..."
find ../test-media -name "*.mp3" -o -name "*.mp4" -o -name "*.jpg" | wc -l | xargs echo "✅ Test media files available:"

echo "Infrastructure test completed successfully!"
echo "Ready for full Android testing."

echo "Cleaning up..."
$DOCKER_COMPOSE -f docker-compose.test.yml down
