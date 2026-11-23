#!/usr/bin/env bash
# Downloads the OpenCV Android SDK next to the project and prints the Gradle flag to use.
set -euo pipefail
V="${OPENCV_VERSION:-4.10.0}"
[ -d opencv-sdk ] || { curl -fL -o opencv.zip "https://github.com/opencv/opencv/releases/download/$V/opencv-$V-android-sdk.zip"; unzip -q opencv.zip -d opencv-sdk; rm opencv.zip; }
echo "Add to gradle.properties (or pass on the command line):"
echo "OpenCV_DIR=$(pwd)/opencv-sdk/OpenCV-android-sdk/sdk/native/jni"
