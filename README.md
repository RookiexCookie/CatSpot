# CatSpot

A lightweight, GMS-free Spotify client specifically tailored and optimized for the Cat S22 Flip and similar small-screen Android devices. 

This is a fork of the awesome [Sidespot](https://codeberg.org/jtaekman/sidespot) project, retaining its beautiful Jetpack Compose UI while adding full support for 32-bit (armeabi-v7a) architectures and physical keypad navigation specific to the Cat S22 Flip.

Built on [librespot](https://github.com/librespot-org/librespot) (Rust) with a minimal Jetpack Compose UI optimized for 480x640 displays. With the help of AI

## Features

- **Cat S22 Flip Optimized** -- natively supports 32-bit (armeabi-v7a) Android architecture required by the Cat S22 Flip.
- **Hardware Keypad Integration** -- fully navigable using the physical keys on the Cat S22 Flip.
- **No Google Play Services required** -- runs cleanly on minimal Android setups.
- **Full playback** -- play, pause, seek, skip, shuffle, repeat, queue management.
- **Library browsing** -- playlists, liked songs, saved albums, saved podcasts.
- **Background playback** -- foreground service with media notification controls.
- **Dynamic theming** -- album art colors tint the entire UI.

## Screenshots

<p align="center">
  <img src="screenshots/library_top.png" width="180" alt="Library">
  <img src="screenshots/search.png" width="180" alt="Search">
  <img src="screenshots/queue.png" width="180" alt="Queue">
  <img src="screenshots/now_playing.png" width="180" alt="Now Playing">
</p>


## Cat S22 Flip Keyboard Support

CatSpot features full hardware navigation so you can control the app without needing to use the touchscreen.

| Control | Action |
|---------|--------|
| **Demo App 1 (301)** | Navigate tabs (Go Left) |
| **Delete (DEL)** | Navigate tabs (Go Right) |
| **Enter (Center)** | Tapping once will open the menu options (add to queue, liked songs, playlist). Holding will play/pause the current track. |
| **D-Pad Up / Down** | Scroll lists |
| **Volume Buttons** | Adjust media volume |

## Requirements

- Spotify Premium account
- Android 11+ (API 30+), arm-v7a (32-bit) or arm64 device

## Install

Download the latest APK and sideload it onto your Cat S22 Flip:

```sh
adb install CatSpot-v*.apk
```

## Build from Source

### Prerequisites

- **Java 17**
- **Android SDK** with NDK
- **Rust toolchain** with the `armv7-linux-androideabi` target
- **cargo-ndk**

### Setup

```sh
# Install Rust 32-bit Android target
rustup target add armv7-linux-androideabi

# Clone with submodules
git clone --recurse-submodules <your-repo-url>
cd CatSpot
```

### Build & Install

The Gradle build automatically compiles the 32-bit Rust native library via `cargo-ndk` before assembling the APK.

```sh
./gradlew assembleDebug
```

## Architecture

- **Native core**: librespot handles Spotify protocol, authentication, audio streaming, and decryption.
- **JNI bridge**: Serializes data as JSON between Kotlin and Rust.
- **UI**: Jetpack Compose with Navigation, Material3, Coil for album art.

## Current Limitations

- **Spotify Premium required** -- free-tier accounts are not supported by librespot.
- **No lossless/HiFi** -- max quality is 320 kbps OGG Vorbis.
- **No offline mode** -- streaming only.
- **Account risk** -- Spotify has not sanctioned third-party clients. Use at your own risk.

## Disclaimer

CatSpot is a fork of Sidespot. It is not affiliated with or endorsed by Spotify. It uses a reverse-engineered protocol implementation (librespot). Use at your own risk. A Spotify Premium subscription is required.

## License

GPLv3. See LICENSE for details.
