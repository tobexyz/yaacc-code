#!/bin/bash
set -e

echo "YAACC Automated Testing Suite"
echo "=============================="

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo "Error: Docker is not installed or not in PATH"
    exit 1
fi

# Check for docker compose (new syntax) or docker-compose (legacy)
if ! docker compose version &> /dev/null && ! command -v docker-compose &> /dev/null; then
    echo "Error: docker compose is not available"
    exit 1
fi

# Use docker compose if available, fallback to docker-compose
if docker compose version &> /dev/null; then
    DOCKER_COMPOSE="docker compose"
else
    DOCKER_COMPOSE="docker-compose"
fi

# Navigate to docker directory
cd "$(dirname "$0")/../docker"

echo "Starting test environment..."
$DOCKER_COMPOSE -f docker-compose.test.yml up --build --abort-on-container-exit

echo "Cleaning up..."
$DOCKER_COMPOSE -f docker-compose.test.yml down

echo "Test results available in ../reports/"
