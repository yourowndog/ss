#!/bin/bash
# ScreenSense - Download Kokoro v0.19 assets
# Run from project root

set -e

ASSETS_DIR="./assets"
mkdir -p "$ASSETS_DIR"
cd "$ASSETS_DIR"

echo "=== Downloading ScreenSense Assets (Kokoro v0.19) ==="
echo ""

# 1. Sherpa-ONNX AAR
echo "[1/2] Downloading sherpa-onnx v1.12.19 AAR..."
if [ ! -f "sherpa-onnx-1.12.19.aar" ]; then
    aria2c -x 16 -s 16 -c \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.19/sherpa-onnx-1.12.19.aar" \
      -o sherpa-onnx-1.12.19.aar
else
    echo "  - Already exists, skipping."
fi

# 2. Kokoro Model
echo ""
echo "[2/2] Downloading Kokoro v0.19 voice model..."
if [ ! -d "kokoro-en-v0_19" ]; then
    aria2c -x 16 -s 16 -c \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2" \
      -o kokoro-en-v0_19.tar.bz2
      
    echo "Extracting Kokoro model..."
    tar xvf kokoro-en-v0_19.tar.bz2
    rm kokoro-en-v0_19.tar.bz2
else
    echo "  - 'kokoro-en-v0_19' directory exists, skipping download."
fi

echo ""
echo "=== Download Complete ==="
echo "Assets ready in: $ASSETS_DIR"
echo ""
echo "Next step: ADB push to phone"
echo "  adb push assets/kokoro-en-v0_19 /sdcard/ScreenSense/"
