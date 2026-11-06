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

## Signed release builds

The release build is intentionally unsigned unless you provide your own key,
so every user signs with their own identity. Create a keystore once:

```sh
keytool -genkeypair -v -keystore ~/triplogger-release.jks -alias triplogger \
    -keyalg RSA -keysize 2048 -validity 10950
```

Then create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=/home/you/triplogger-release.jks
storePassword=...
keyAlias=triplogger
keyPassword=...
```

```sh
./gradlew assembleRelease
```

The signed APK is written to
`app/build/outputs/apk/release/app-release.apk`. Without
`keystore.properties` the APK is left unsigned; sign it yourself with
`apksigner` if you prefer.

Keep your keystore backed up: the same key is required for every future
upgrade install on a device.

## Sideloading notes

Prebuilt or self-built APKs install fine on current Android, but installing
from a browser/file manager requires allowing "Install unknown apps" for
that app once, and confirming Google Play Protect's warning on first run —
this is Google's friction against drive-by APKs, not a restriction specific
to this app. The debug APK built by `assembleDebug` and a self-signed
release APK cannot both be installed under the same package name without
uninstalling the other (different signing keys).
