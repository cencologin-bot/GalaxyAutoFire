# VisualAutomation 0.3.0 — Live recognition build

Android app (Kotlin + C++17 + OpenCV + JNI + MediaProjection) that recognises the **silver star** barrel,
tracks it in real time, and reports when its centre enters a calibrated destination area.
Vision only: no taps, clicks, firing or any action on the game.

## Pipeline (native, `vision/DetectionCore.cpp`)
1. **Capture**: MediaProjection -> ImageReader (RGBA, <=720 px wide) -> direct ByteBuffer -> JNI (no copies to Java).
2. **Work frame**: downscaled to 480 px wide, gray + HSV, restricted to a configurable *search region* (default: bottom game strip).
3. **Detection**: multi-scale template matching (`TM_CCOEFF_NORMED`, 22 scales) of the silver-star barrel, top-3 peaks per scale with non-max suppression.
4. **Colour gate**: the star centre must have mean saturation <= 70. Gold/orange stars, gifts and chests measure 145-220, silver ~6-11.
5. **Tracking**: constant-velocity Kalman filter. While tracking, only a small window around the prediction is searched at the locked scale (+-1 step). Misses coast on the prediction (dashed rectangle); periodic wider re-acquisition; LOST after 6 misses, SEARCHING after 90.
6. **Arrival**: centre inside the calibrated circle for 3 consecutive measured frames -> ARRIVED; leaves the circle (15 % hysteresis) -> re-armed.
7. **Lantern**: lit green/red lamp found by HSV in a fixed region (colour is informational only). Used as spatial reference: `movingTowardLantern` = velocity points toward it.
8. **Outputs**: bounds, centre, confidence, velocity/speed, FPS, 64-point history, status.

Prototype numbers on the bundled screenshot (Python/OpenCV mirror of the same algorithm):
silver barrel NCC 0.97-0.99 vs. <= 0.76 for every other barrel; negative crops <= 0.74; lantern located within ~10 px; coarse scale scan ~23 ms on desktop, local tracking is a single small match per frame.

## Build on GitHub (no local setup)
1. Create a repository and push this folder as its root.
2. The workflow `.github/workflows/android.yml` installs SDK/NDK/CMake, downloads the OpenCV Android SDK 4.10.0 and runs `gradle :app:assembleDebug`.
3. Download the APK from **Actions -> latest run -> Artifacts** (debug-signed, installable). Push a tag such as `v0.3.0` to also attach it to a GitHub Release.
No Gradle wrapper is committed; CI uses Gradle 8.9 directly. Run `gradle wrapper --gradle-version 8.9` once locally if you want `./gradlew`.

## Build locally
Run `scripts/setup_opencv.sh`, put the printed `OpenCV_DIR=...` line in `gradle.properties`, then build `app` in Android Studio.

## Build (manual details)
1. Android Studio, SDK 35, NDK + CMake 3.22.1.
2. OpenCV Android SDK; pass `-POpenCV_DIR=/path/to/OpenCV-android-sdk/sdk/native/jni`.
3. Build `app` (ABI: arm64-v8a).

## Use
- **Floating panel over the game**: tap *Iniciar análisis visual*, grant overlay + capture, open the game and press ▶ on the draggable panel (🎯 calibrate by touching the game, ◎± radius, ■ stop, ✕ close). Capture starts paused; nothing is processed until ▶.
- **Full-screen reference test**: *Test a pantalla completa* shows the reference screenshot full screen with the same panel on top; ⇆ scrolls the barrel block so the star moves through the destination.
- Drag the green point to the destination, set the radius, **Guardar calibración** (state CALIBRATING until saved).
- **Probar detección en la imagen** runs the engine on the bundled screenshot.
- **Iniciar análisis visual** asks for overlay + screen-capture permission, then draws the rectangle, trail, destination ring and HUD over the game.

## Tuning
- `Params` in `DetectionCore.h` (thresholds, saturation gate, frames).
- SharedPreferences keys `search_*` and `lantern_*` (normalized rects) if the game layout differs.
- Template: `assets/templates/silver_star_barrel.jpg`.

## Notes
- The overlay is part of the captured screen; strokes are thin and the rectangle is inflated to avoid disturbing the match.
- Not yet compiled or run on a device in the authoring environment: validate on hardware and profile FPS.
