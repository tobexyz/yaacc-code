#!/bin/bash
set -e

echo "=== Running YAACC Tests ==="
docker exec yaacc-test /scripts/run-tests-with-screenshots.sh

echo ""
echo "=== Copying Test Results ==="
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_DIR="./reports/$TIMESTAMP"
mkdir -p "$REPORT_DIR"

echo "Copying all files from container..."
docker cp yaacc-test:/tmp/screenshots/. "$REPORT_DIR/" 2>/dev/null || echo "No files found"

echo ""
echo "Test results saved to: $REPORT_DIR/"
ls -lh "$REPORT_DIR/"
