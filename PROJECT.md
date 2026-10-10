# Yenten Android Miner

## Overview

Yenten Android Miner is a Java Android application with a native C/C++ CPU
mining engine. The app configures a pool connection, wallet or pool login,
password, CPU thread count, and mining algorithm. It also provides a local
benchmark mode and displays miner output in the app.

The current application version is **1.4.0** (`versionCode` **5**). The Android
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

Mining runs inside `MiningService`, a foreground service with a persistent
notification and a stop action. The service acquires a partial CPU wake lock
for the active mining session and releases it when mining stops. Log output is
kept in a bounded in-memory buffer and can be scrolled independently from the
rest of the screen.

The device panel reads the processor model, CPU core count, and any readable CPU
capacity/frequency topology data. The displayed efficiency/performance groups
are inferred from Android's per-core capacity or maximum-frequency files. CPU
temperature is read from CPU/SoC thermal zones under `/sys/class/thermal`; many
devices restrict access to those files, in which case the app displays that
temperature is unavailable.

The algorithm selector offers `yescryptr16` and `yespowerr16`; the latter is
selected by default. Pool presets are Yenten Pool at
`stratum+tcp://yenten-pool.info:63368` and CPU Pool at
`stratum+tcp://cpu-pool.com:63368`, plus Rplant Pool (`pool.rplant.xyz`)
through its EU endpoint at `stratum+tcp://eu.rplant.xyz:3382`. A custom
endpoint can also be entered.

The UI uses Material Components with dark, light, and Matrix appearance modes.
The app language can be set to English, Russian, or Simplified Chinese. The
language selection uses AndroidX AppCompat application locales and is also
listed in Android's per-app language settings on supported Android versions.

The wallet page generates a compressed secp256k1 key locally and
encodes a legacy Yenten mainnet address and WIF private key. Yenten Core's mainnet
Base58 prefixes are used (address `78`, private key `123`). The miner address
field is populated with the generated address. The address and WIF are stored in
app-private preferences encrypted with AES-GCM; the random data-encryption key is
wrapped by an RSA key held in Android Keystore. The encrypted preference file is
excluded from cloud and device-transfer backups because the device-bound Keystore
key cannot be restored on another installation. The WIF is hidden until
requested and is exported only when the user chooses a destination through
Android's document picker. The exported text file is unencrypted; anyone with it
can spend funds controlled by the key. The file includes the Core console command
for `importprivkey` and a note to wait for the wallet rescan. Users need an
exported backup before uninstalling the app or moving to another device.

The wallet card also explains how to reveal and paste WIF into the Yenten web
wallet. Before opening it, the app warns that entering WIF grants the website
spending access to the key. Address balance checks call
`https://api.yentencoin.info/balance/{address}` on a background thread. The API
receives only the public address, and its integer atomic-unit amounts are shown
as YTN with eight decimal places; the WIF is never sent to this endpoint.

The mining service declares Android's `specialUse` foreground-service type for
the user-started CPU-mining task. Android 13 and later can require notification
permission before the ongoing notification appears in the notification drawer.

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
- Spongy Castle 1.58.0.0 for secp256k1 and RIPEMD-160 key/address encoding

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
