# Android Camera UI — Kotlin

High-performance Android Camera application based on the Android Camera & Image-Processing Technical Design Package (v1.0).

## Current Status

The project has moved beyond the initial UI-only phase and now includes a functional Camera2 integration.

### Implemented
- **Live Camera Viewfinder**: Real-time preview using Camera2 API and `SurfaceView`.
- **Camera Capability Discovery**: Automated inspection of all device cameras, including logical and physical sensor metadata.
- **Fixed Resolution Tiers**: UI-selectable 8MP, 12MP, 24MP, and 48MP modes based on hardware entitlement.
- **Intelligent Camera Selection**: Heuristics to identify the primary 1× rear camera and best front-facing camera.
- **Dynamic UI Controls**:
    - Exposure compensation panel (EV) with manual and auto modes.
    - Zoom control (1.0×, 1.5×, 2.0×).
    - Flash mode toggle (On, Auto, Off).
    - Format badge (HEIC/JPEG toggle).
    - Resolution badge (cycling through supported MP tiers).
- **Auto-Rotation**: UI controls rotate to match device orientation while maintaining a portrait activity orientation.
- **Focus Reticle**: Interactive touch-to-focus UI animation.

### In Progress / Not Implemented
- Still capture (JPEG/HEIC encoding)
- MediaStore integration (saving to gallery)
- Video recording (MediaCodec)
- EXIF processing
- Device quirk database (OEM-specific fixes)

## Technical Architecture

- **Language**: Kotlin 1.7+
- **API Level**: Target 34+, Min SDK 28 (Android 9)
- **Camera API**: Camera2 (with support for `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION` on Android 12+)
- **UI Architecture**: Single Activity, native XML layouts, and custom drawing for camera controls.

## Development Setup

1. Open in Android Studio (Giraffe or newer recommended).
2. Ensure **Gradle JDK** is set to JDK 17.
3. Grant **Camera Permission** on first run.

The application logs detailed camera hardware characteristics to Logcat under the tag `SimpleCam.Camera` during startup.
