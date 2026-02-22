#!/bin/bash
set -e

echo "Generating test media files..."

# Check if ffmpeg is available
if ! command -v ffmpeg &> /dev/null; then
    echo "Error: ffmpeg is required to generate test media files"
    echo "Install with: sudo apt install ffmpeg"
    exit 1
fi

cd "$(dirname "$0")/../test-media"

# Generate audio files
echo "Creating MP3 files..."
ffmpeg -f lavfi -i "sine=frequency=440:duration=5" -c:a libmp3lame -b:a 128k audio/mp3/test-track-1.mp3 -y -loglevel error
ffmpeg -f lavfi -i "sine=frequency=880:duration=3" -c:a libmp3lame -b:a 128k audio/mp3/test-track-2.mp3 -y -loglevel error

echo "Creating FLAC file..."
ffmpeg -f lavfi -i "sine=frequency=523:duration=4" -c:a flac audio/flac/test-track.flac -y -loglevel error

echo "Creating OGG file..."
ffmpeg -f lavfi -i "sine=frequency=659:duration=3" -c:a libvorbis audio/ogg/test-track.ogg -y -loglevel error

# Generate video files
echo "Creating MP4 video..."
ffmpeg -f lavfi -i "testsrc=duration=5:size=320x240:rate=1" -c:v libx264 -pix_fmt yuv420p video/mp4/test-video.mp4 -y -loglevel error

echo "Creating MKV video..."
ffmpeg -f lavfi -i "testsrc=duration=3:size=320x240:rate=1" -c:v libx264 -pix_fmt yuv420p video/mkv/test-video.mkv -y -loglevel error

# Generate image files
echo "Creating JPEG image..."
ffmpeg -f lavfi -i "testsrc=duration=1:size=640x480:rate=1" -frames:v 1 images/jpg/test-image.jpg -y -loglevel error

echo "Creating PNG image..."
ffmpeg -f lavfi -i "testsrc=duration=1:size=640x480:rate=1" -frames:v 1 images/png/test-image.png -y -loglevel error

echo "Test media files generated successfully!"
echo ""
echo "Generated files:"
find . -name "*.mp3" -o -name "*.flac" -o -name "*.ogg" -o -name "*.mp4" -o -name "*.mkv" -o -name "*.jpg" -o -name "*.png" | sort
