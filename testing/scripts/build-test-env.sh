#!/bin/bash
set -e

# Find project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT/testing"

echo "=== Building Android Test Container ==="
docker compose -f docker/docker-compose.test.yml build android-test

echo ""
echo "✅ Build complete!"
