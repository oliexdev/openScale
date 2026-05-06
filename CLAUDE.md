# CLAUDE.md

This file provides context for AI assistants working with this repository.

## Project Overview

**openScale** is an open-source Android app for weight and body metrics tracking with Bluetooth scale support.

## Repository Structure

```
android_app/        # Android application (Kotlin + Jetpack Compose)
arduino_mcu/        # Arduino firmware for custom scale hardware
docs/               # Documentation and hardware schematics
fastlane/           # Fastlane metadata and release automation
```

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose
- **DI**: Hilt
- **DB**: Room (with migration schemas in `app/schemas/`)
- **Android Gradle Plugin**: 8.13.2
- **Kotlin**: 2.3.10
- **Min SDK**: 31 / Target SDK: 36
- **Java / Kotlin JVM target**: 21

## Build Requirements

- **JDK 21+** required — the system default may be too old. Use JDK 23 (Temurin):
  ```
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home
  ```
- Android SDK with API 36

## Build Commands

```bash
cd android_app

# Build debug APK
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home ./gradlew assembleDebug

# Build + install + launch on connected device
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home ./gradlew installDebug && \
  adb shell am start -n com.health.openscale.debug/com.health.openscale.MainActivity
```

Press **F5** in VS Code to build, install, and launch the app (requires a connected device or running emulator).

## App IDs

| Build type | Application ID |
|------------|----------------|
| debug      | `com.health.openscale.debug` |
| release    | `com.health.openscale` |
| beta       | `com.health.openscale.beta` |
| oss        | `com.health.openscale.oss` |

## Known Issues / Notes

- `kotlinOptions` DSL is deprecated in this Kotlin version — use `kotlin { compilerOptions { } }` instead.
- Release/OSS signing requires keystore files outside the repo (`../../openScale.keystore`). Debug builds work without them.
- Some string resources (e.g. `insights_chip_now`) lack default values and are stripped during resource processing — this is a known warning, not an error.
