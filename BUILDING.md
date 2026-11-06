# Building

## Prerequisites

- JDK 17 or newer
- Android SDK platform 36 (install Android Studio, or use `sdkmanager`)
- The Gradle wrapper is included; no separate Gradle install needed

## Build

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install on a device

Enable USB debugging on the phone, then:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and use Run.

## Tests

Unit tests (no device required):

```sh
./gradlew testDebugUnitTest
```
