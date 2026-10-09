# Yenten Android Miner

## Overview

Yenten Android Miner is a Java Android application with a native C/C++ CPU
mining engine. The app configures a pool connection, wallet or pool login,
password, CPU thread count, and mining algorithm. It also provides a local
benchmark mode and displays miner output in the app.

The current application version is **1.1.0** (`versionCode` **2**). The Android
application supports Android 5.0 (API 21) and later, targets API 34, and builds
native libraries for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.

## How it works

1. `MainActivity` restores the saved pool, credentials, thread count, algorithm,
   appearance, and app language.
2. The user starts mining or runs a benchmark. An empty thread count is passed
   to the native miner as zero, which selects its automatic thread count.
3. `BitZenyMiningLibrary` maps the selected Java algorithm to the native
   algorithm identifier and calls the JNI entry points in
   `bitzenymininglibrary.cpp`.
4. The JNI bridge calls the cpuminer engine. Native log output is sent back to
   Java through a `Handler` and shown in the output panel.

The algorithm selector offers `yescryptr16` and `yespowerr16`; the latter is
selected by default. Pool presets are Yenten Pool at
`stratum+tcp://yenten-pool.info:63368` and CPU Pool at
`stratum+tcp://cpu-pool.com:63368`. A custom endpoint can also be entered.

The UI uses Material Components with dark, light, and Matrix appearance modes.
The app language can be set to English, Russian, or Simplified Chinese. The
language selection uses AndroidX AppCompat application locales and is also
listed in Android's per-app language settings on supported Android versions.

## Project layout

- `app/` — Android application, Java activity, Material UI, themes, and
  translations.
- `bitzenymininglibrary/` — Java API, JNI bridge, and CMake configuration for
  the native miner.
- `bitzenymininglibrary/src/main/cpp/libcpuminer/` — cpuminer C sources,
  including the yescrypt and yespower implementations.
- `distribution/` — headers and prebuilt static zlib, libcurl, and Jansson
  libraries for each supported Android ABI.
- `gradle/` — Gradle wrapper files.

## Main dependencies

### Android application

- Android Gradle Plugin 8.6.1
- Gradle 8.7 (via the checked-in wrapper)
- AndroidX AppCompat 1.7.0
- Material Components 1.12.0
- AndroidX ConstraintLayout 2.1.4

### Native library

- Android NDK 27.1.12297006
- CMake 3.28.3
- Native cpuminer sources in the repository
- Bundled per-ABI static libraries and headers for zlib, libcurl, and Jansson

Gradle resolves Android and Java dependencies from Google's Maven repository
and Maven Central. The native third-party libraries are supplied under
`distribution/` and are not fetched during the build.

## Build requirements

- JDK 17
- Android SDK with platform 34 and matching build tools
- Android NDK 27.1.12297006
- CMake 3.28.3
- Ninja and host-compatible NDK/CMake tools

Set the SDK location in the ignored `local.properties` file. For example:

```properties
sdk.dir=/path/to/android-sdk
```

The project also configures a CMake location in `local.properties` where the
build environment needs an explicit override. Keep machine-specific SDK paths
out of shared project documentation and source control.

## Build a debug APK

From the repository root, run:

```bash
./gradlew --no-daemon assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

The debug APK is signed with Android's debug key and is intended for
development and local installation.

## Building on Linux ARM64

All build tools must match the host architecture. Google SDK packages commonly
ship x86_64-only CMake, Ninja, and NDK host tools; those cannot run on an ARM64
host. Use ARM64-compatible CMake, Ninja, NDK tools, and `aapt2`. Configure the
ARM-compatible `aapt2` path with
`android.aapt2FromMavenOverride` in `gradle.properties`. The project's
`android-arm-build` workflow can be used to check the toolchain and build the
APK. The checked-in `gradle.properties` currently points this override at the
build workspace's `/tmp/android-arm64-sdk/build-tools/34.0.0/aapt2`; update it
for another machine, or set the override in the user's Gradle properties so a
host-specific path does not need to be committed.

## Release builds and versioning

The application version is set in `app/build.gradle` using `versionName` and
`versionCode`. Increase `versionCode` for every distributable update and update
`versionName` to the corresponding human-readable release number.

The release build type currently has minification disabled and does not define
a release signing configuration. A release APK must be signed with the project's
release key before distribution. Do not commit signing keys or passwords.
