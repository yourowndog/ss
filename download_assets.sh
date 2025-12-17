#!/bin/bash
# ScreenSense - Download all required assets using aria2c
# Run from /home/sam/projects/ScreenSense

set -e

ASSETS_DIR="./assets"
mkdir -p "$ASSETS_DIR"
cd "$ASSETS_DIR"

echo "=== Downloading ScreenSense Assets ==="
echo ""

# 1. Sherpa-ONNX AAR (Android library)
echo "[1/2] Downloading sherpa-onnx v1.12.19 AAR..."
aria2c -x 16 -s 16 -c \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.19/sherpa-onnx-1.12.19.aar" \
  -o sherpa-onnx-1.12.19.aar

# 2. Piper TTS Model: libritts_r-medium (904 speakers, includes espeak-ng-data)
echo ""
echo "[2/2] Downloading Piper libritts_r-medium voice model (75MB, 904 speakers)..."
aria2c -x 16 -s 16 -c \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-libritts_r-medium.tar.bz2" \
  -o vits-piper-en_US-libritts_r-medium.tar.bz2

# Extract the model
echo ""
echo "Extracting voice model..."
tar xvf vits-piper-en_US-libritts_r-medium.tar.bz2
rm vits-piper-en_US-libritts_r-medium.tar.bz2

echo ""
echo "=== Download Complete ==="
echo ""
echo "Assets downloaded to: $ASSETS_DIR"
echo ""
echo "Contents:"
ls -lh .
echo ""
echo "Voice model contents:"
ls -lh vits-piper-en_US-libritts_r-medium/
echo ""
echo "Next step: ADB push to phone"
echo "  adb push assets/ /sdcard/ScreenSense/"
