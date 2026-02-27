# sidespot

A lightweight Spotify client for the [Sidephone SP-01](https://sidephone.com) and similar small-screen Android devices without Google Play Services.

Built on [librespot](https://github.com/librespot-org/librespot) (Rust) with a minimal [Jetpack Compose](https://developer.android.com/jetpack/compose) UI optimized for 480x640 displays.

## Status

**Pre-development** -- see [DESIGN.md](DESIGN.md) for the full design document.

## Requirements

- Spotify Premium account
- Android 12+ device (no GMS required)

## Architecture

- **Core**: librespot (Rust), cross-compiled to `aarch64-linux-android` via cargo-ndk
- **Bridge**: JNI (Rust `extern "C"` <-> Kotlin `external fun`)
- **UI**: Jetpack Compose, MVVM, dark theme, 48dp+ touch targets
- **Audio**: Oboe (AAudio/OpenSL ES) or AudioTrack fallback

## Disclaimer

sidespot is not affiliated with or endorsed by Spotify. It uses a reverse-engineered protocol implementation. Use at your own risk. Spotify Premium is required.

## License

TBD
