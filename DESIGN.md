# sidespot -- Design Document

A lightweight Spotify client for the Sidephone SP-01 and similar
resource-constrained Android devices, powered by librespot.

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Motivation](#2-motivation)
3. [Target Device](#3-target-device)
4. [Architecture Overview](#4-architecture-overview)
5. [Core Library Layer (librespot)](#5-core-library-layer-librespot)
6. [JNI Bridge](#6-jni-bridge)
7. [Android Application Layer](#7-android-application-layer)
8. [UI Design](#8-ui-design)
9. [Authentication](#9-authentication)
10. [Audio Pipeline](#10-audio-pipeline)
11. [Caching Strategy](#11-caching-strategy)
12. [Distribution](#12-distribution)
13. [Risk Assessment](#13-risk-assessment)
14. [MVP Scope & Milestones](#14-mvp-scope--milestones)
15. [Build System](#15-build-system)
16. [Open Questions](#16-open-questions)

---

## 1. Project Overview

sidespot is a standalone Spotify client for Android that does not depend on the
official Spotify app or Google Play Services. It uses
[librespot](https://github.com/librespot-org/librespot) (an open-source Rust
implementation of the Spotify protocol) for authentication, metadata retrieval,
and audio streaming, paired with a minimal Jetpack Compose UI designed for
small-screen devices.

**sidespot is not an official Spotify product.** It uses a reverse-engineered
protocol implementation and operates in a legal gray area with respect to
Spotify's Terms of Service. See [Risk Assessment](#13-risk-assessment).

## 2. Motivation

The Sidephone SP-01 is a minimalist Android phone designed to reduce screen
time. It runs Android 12 without Google Play Services. The official Spotify app:

- **Requires GMS**: The Spotify Android app depends on Google Play Services for
  authentication, push notifications, and other infrastructure. It will not
  function on a device without GMS.
- **UI is unusable at 480x640**: Spotify's interface is designed for 5-7"
  screens. On a 2.8" display, touch targets are too small and information
  density is overwhelming.
- **Is resource-heavy**: While the SP-01 has 4GB RAM (adequate), Spotify's
  background services, telemetry, and UI rendering are unnecessarily heavy for
  simple music playback on a device with a 2000 mAh battery.

sidespot addresses all three issues by providing a GMS-free, small-screen-native
Spotify experience with minimal resource overhead.

## 3. Target Device

### Sidephone SP-01 Specifications

| Component       | Specification                              |
|-----------------|--------------------------------------------|
| SoC             | MediaTek MT8766 (quad-core Cortex-A53, 12nm) |
| RAM             | 4 GB                                       |
| Storage         | 64 GB (non-expandable)                     |
| OS              | Android 12 (AOSP, no GMS)                  |
| Display         | 2.8" LCD, 480x640, touchscreen             |
| Battery         | 2000 mAh (non-removable)                   |
| Connectivity    | 4G LTE, WiFi 802.11 a/b/g/n, BT 4.2 + BLE |
| Audio Output    | Loudspeaker, USB-C (no 3.5mm jack)         |
| Camera          | 13MP (not relevant)                        |
| GMS             | **Not available**                          |
| Headphone Jack  | **None** (BT or USB-C audio only)          |

### Design Constraints Derived from Hardware

- **480x640 resolution**: All UI must be designed for this exact viewport.
  Large touch targets (minimum 48dp), minimal text, icon-driven navigation.
- **No GMS**: OAuth must use a standard browser intent, not Google Sign-In.
  No Firebase, no Play Store distribution.
- **2000 mAh battery**: Minimize background CPU wake-ups, network polling, and
  unnecessary rendering. Target <3% battery/hour during active playback.
- **BT 4.2**: Supports A2DP profile for Bluetooth audio. SBC codec guaranteed;
  aptX/AAC codec support depends on the MT8766's Bluetooth stack.
- **No headphone jack**: USB-C audio and Bluetooth are the only wired/wireless
  options. The app should handle audio routing gracefully.
- **64 GB non-expandable storage**: Audio caching must be conservative and
  user-configurable with sensible defaults.

## 4. Architecture Overview

```
+--------------------------------------------------+
|                  Android App                      |
|                                                   |
|  +--------------------------------------------+  |
|  |         Jetpack Compose UI Layer            |  |
|  |  (Now Playing, Library, Search, Settings)   |  |
|  +--------------------+-----------------------+   |
|                       |                           |
|  +--------------------v-----------------------+   |
|  |          Kotlin ViewModel Layer             |  |
|  |   (State management, UI event handling)     |  |
|  +--------------------+-----------------------+   |
|                       |                           |
|  +--------------------v-----------------------+   |
|  |            JNI Bridge Layer                 |  |
|  |   (Kotlin <-> Rust FFI via JNI/JNA)        |  |
|  +--------------------+-----------------------+   |
|                       |                           |
|  +--------------------v-----------------------+   |
|  |         librespot Core (Rust .so)           |  |
|  |  - Spotify protocol (Mercury/Hermes)        |  |
|  |  - Authentication & session management      |  |
|  |  - Audio fetching & decryption              |  |
|  |  - Metadata retrieval                       |  |
|  |  - Spotify Connect (optional)               |  |
|  +--------------------+-----------------------+   |
|                       |                           |
|  +--------------------v-----------------------+   |
|  |        Android Audio Output                 |  |
|  |   (AudioTrack / Oboe via Rust or JNI)      |  |
|  +--------------------------------------------+  |
+--------------------------------------------------+
```

### Layer Responsibilities

| Layer | Language | Responsibility |
|-------|----------|----------------|
| UI | Kotlin + Compose | Rendering, user input, navigation |
| ViewModel | Kotlin | State management, business logic orchestration |
| JNI Bridge | Kotlin + Rust | Type marshaling, callback registration, lifecycle |
| librespot Core | Rust | Spotify protocol, auth, streaming, metadata |
| Audio Output | Rust (or Kotlin) | PCM delivery to Android audio subsystem |

## 5. Core Library Layer (librespot)

### Why librespot (Rust)?

- **Most actively maintained** implementation (v0.8.0, 6.6k stars, 167
  contributors, 2,159 commits).
- **Lightweight compiled binary**: A stripped ARM64 `.so` is typically 2-5 MB.
- **No runtime dependencies**: No JVM, no GC pressure, deterministic memory
  usage.
- **Modular crate structure**: We can select only the crates we need:
  - `librespot-core`: Session management, authentication, Mercury protocol
  - `librespot-metadata`: Track/album/artist/playlist metadata
  - `librespot-playback`: Audio fetching, decryption, decoding
  - `librespot-connect`: Spotify Connect receiver (optional/future)
  - `librespot-discovery`: Zeroconf discovery (optional/future)
  - `librespot-oauth`: OAuth token management

### Cross-Compilation Target

```
Target triple: aarch64-linux-android
NDK API level: 21 (Android 5.0) -- conservative floor
Build tool: cargo-ndk
```

The MT8766 is a 64-bit ARMv8-A processor. We target `aarch64` only (no need for
`armv7` or `x86` unless we want emulator support during development, in which
case we add `x86_64`).

### librespot Modifications

We will likely need to fork or patch librespot to:

1. **Custom audio sink**: Replace the default Rodio/ALSA backend with a sink
   that writes PCM samples across the JNI boundary (or directly to Android's
   `AAudio`/`OpenSL ES` via Rust bindings like `oboe-rs`).
2. **Expose a C-compatible API**: librespot's public API is Rust-native. We
   need to create a thin `extern "C"` wrapper exposing the functions we need
   for JNI.
3. **Disable unused features**: Strip out discovery backends (mDNS/libmdns),
   unused audio backends, and pipe/subprocess sinks to minimize binary size.
4. **Custom TLS backend**: Use `rustls` instead of `native-tls` to avoid
   OpenSSL dependency on Android.

### Exposed Core Functions (Preliminary)

```
// Session lifecycle
session_create(config) -> SessionHandle
session_connect(handle, credentials) -> Result
session_disconnect(handle)

// Authentication
auth_login_oauth(handle, callback_url) -> OAuthUrl
auth_login_token(handle, token) -> Result
auth_get_stored_credentials(handle) -> Option<Credentials>

// Player control
player_load(handle, track_uri, start_playing) -> Result
player_play(handle)
player_pause(handle)
player_seek(handle, position_ms)
player_next(handle)
player_previous(handle)
player_get_state(handle) -> PlayerState
player_set_volume(handle, volume_u16)

// Metadata
metadata_get_track(handle, track_id) -> TrackMetadata
metadata_get_album(handle, album_id) -> AlbumMetadata
metadata_get_artist(handle, artist_id) -> ArtistMetadata
metadata_get_playlist(handle, playlist_id) -> PlaylistMetadata
metadata_search(handle, query) -> SearchResults
metadata_get_user_playlists(handle) -> Vec<Playlist>
metadata_get_user_saved_tracks(handle) -> Vec<Track>

// Callbacks (Rust -> Kotlin)
register_player_event_callback(handle, callback)
register_metadata_callback(handle, callback)
```

## 6. JNI Bridge

### Approach

Use **JNI** (Java Native Interface) to bridge Kotlin and Rust. The Rust side
exposes `extern "C"` functions; the Kotlin side declares `external fun`
bindings.

We will use the [`jni` crate](https://crates.io/crates/jni) on the Rust side
to handle JNI environment access, object creation, and callback invocation.

### Callback Pattern (Rust -> Kotlin)

Player events (track changed, playback state, position updates) flow from Rust
to Kotlin via JNI callbacks:

1. Kotlin registers a callback interface with the native layer at startup.
2. Rust stores a `GlobalRef` to the Kotlin callback object.
3. When a player event occurs, Rust calls the appropriate method on the
   callback object via `JNIEnv::call_method`.

### Thread Safety

librespot is async (tokio-based). JNI calls must happen on threads attached to
the JVM. We will:

- Run librespot's tokio runtime on a dedicated thread.
- Use a bounded channel to dispatch events from the tokio runtime to a
  JNI-attached callback thread.
- Ensure all `JNIEnv` access happens on properly attached threads.

### Data Serialization

For simple types (strings, integers, booleans), use direct JNI type mapping.
For complex types (metadata objects, player state), serialize to JSON in Rust
and deserialize in Kotlin. This is simpler than constructing Java objects in
Rust and the overhead is negligible for metadata payloads.

## 7. Android Application Layer

### Min SDK & Dependencies

| Parameter | Value |
|-----------|-------|
| minSdk | 31 (Android 12, matching SP-01) |
| targetSdk | 34 |
| Kotlin | 2.0+ |
| Compose BOM | Latest stable |
| Architecture | MVVM (ViewModel + StateFlow) |

### Key Dependencies (Minimal Set)

- `androidx.compose.*` -- UI toolkit
- `androidx.lifecycle:lifecycle-viewmodel-compose` -- ViewModel integration
- `androidx.navigation:navigation-compose` -- Screen navigation
- `kotlinx.coroutines` -- Async coordination
- `kotlinx.serialization` -- JSON deserialization for JNI payloads
- `coil-compose` -- Lightweight image loading for album art

### No Dependencies On

- Google Play Services (any GMS library)
- Firebase
- Retrofit / OkHttp (all network calls happen in librespot/Rust)
- Room / SQLite (metadata cache uses a simple file-based approach)
- ExoPlayer / Media3 (audio playback is handled by librespot)

## 8. UI Design

### Design Principles

1. **Minimum 48dp touch targets** -- mandatory for 2.8" screen usability.
2. **Maximum 3 levels of navigation depth** -- Home -> List -> Detail.
3. **Persistent mini-player** at bottom of every screen.
4. **Dark theme only** -- saves battery on LCD (marginal) and reduces eye
   strain. Simpler to maintain one theme.
5. **No album art grids** -- too small at 480px width. Use list layouts with
   single album art thumbnails.
6. **Large, readable typography** -- minimum 14sp body, 20sp titles.
7. **Physical volume buttons** mapped to Spotify volume (not just system
   volume).

### Screen Map

```
+------------------+
|     Home         |  <-- Entry point after auth
|  - Now Playing   |
|  - Library       |
|  - Search        |
+--------+---------+
         |
    +----+----+--------+
    |         |        |
    v         v        v
Now Playing  Library  Search
    |         |        |
    |    +----+----+   |
    |    |    |    |   |
    |    v    v    v   |
    | Playlists       |
    | Albums          |
    | Liked Songs     |
    |                  |
    +---> Track List <-+
              |
              v
         Now Playing
         (Full Screen)
```

### Screen Descriptions

#### 1. Now Playing (Full Screen)
- Album art (large, centered, ~200x200dp)
- Track title + artist name
- Progress bar (seekable)
- Play/Pause, Previous, Next buttons (large: 56dp)
- Volume slider (or rely on hardware buttons)
- Shuffle / Repeat toggles
- Queue button

#### 2. Library
- Vertical list: Playlists, Albums, Liked Songs, Recently Played
- Each item: thumbnail (48x48dp) + title + subtitle
- Pull-to-refresh for re-syncing

#### 3. Search
- Text input at top
- Results as a simple scrollable list
- Grouped by type: Tracks, Albums, Artists, Playlists
- Tap to play or navigate to detail

#### 4. Track List (Playlist / Album Detail)
- Header: artwork + name + track count
- Scrollable list of tracks
- Tap to play, long-press for options (add to queue, etc.)

#### 5. Settings
- Audio quality (96 / 160 / 320 kbps)
- Cache size limit
- Logout
- About / version

### Wireframe Concept (480x640 viewport)

```
+---------------------+  480px
|  sidespot   [search]|  -- Top bar (48dp)
+---------------------+
|                     |
|   [Album Art 200dp] |
|                     |
|   Track Title       |
|   Artist Name       |
|                     |
|  ===|====---------- |  -- Seek bar
|  1:23      3:45     |
|                     |
|   [<<]  [> ||]  [>>]|  -- Transport (56dp buttons)
|                     |
|   [shuf]      [rep] |
+---------------------+
| [>] Track - Artist  |  -- Mini-player (collapsed, other screens)
+---------------------+
| [Home] [Lib] [Search]| -- Bottom nav (56dp)
+---------------------+
```

## 9. Authentication

### Flow

1. App launches, checks for cached credentials (stored in encrypted shared
   preferences or a file in app-private storage).
2. If no credentials, show a login screen with a "Log in with Spotify" button.
3. Button opens the device browser via an `Intent.ACTION_VIEW` to Spotify's
   OAuth authorization URL.
4. librespot's `librespot-oauth` crate handles constructing the OAuth URL and
   exchanging the authorization code for access/refresh tokens.
5. On successful auth, credentials (specifically the reusable auth blob that
   librespot generates) are cached locally.
6. Subsequent launches use cached credentials -- no re-authentication needed
   unless tokens are revoked.

### OAuth Details

- **Auth URL**: Constructed by librespot's OAuth module.
- **Redirect**: Use a custom URI scheme (`sidespot://callback`) registered in
  the AndroidManifest as an intent filter. The browser redirects back to the
  app after login.
- **Token storage**: The auth blob / reusable credentials are stored in the
  app's private directory with filesystem permissions. On Android 12+,
  app-private storage is encrypted at rest when the device is locked.

### No GMS Considerations

- Cannot use Google Sign-In or Firebase Auth.
- Cannot use Chrome Custom Tabs (requires GMS). Use a standard
  `Intent.ACTION_VIEW` to the default browser instead.
- If the device browser doesn't handle the redirect scheme, we may need a
  fallback: a built-in WebView that intercepts the redirect URL.

## 10. Audio Pipeline

### Architecture

```
librespot (Rust)                    Android
+------------------+              +------------------+
| Audio Fetch      |              |                  |
| (encrypted OGG)  |              |                  |
|        |         |              |                  |
|    Decrypt       |              |                  |
|        |         |              |                  |
|    Decode        |              |                  |
|   (Vorbis)       |              |                  |
|        |         |              |                  |
|    PCM Samples   |--- JNI ----->| AudioTrack       |
|                  |    or        | (low-level API)  |
|                  |  oboe-rs --->| AAudio           |
+------------------+              +------------------+
```

### Supported Audio Quality

| Quality | Format | Bitrate | Supported |
|---------|--------|---------|-----------|
| Low | OGG Vorbis | 96 kbps | Yes |
| Normal | OGG Vorbis | 160 kbps | Yes (default) |
| High | OGG Vorbis | 320 kbps | Yes |
| Lossless (FLAC) | FLAC | ~1411 kbps | **No** |
| Hi-Res Lossless | FLAC 24-bit | ~2117+ kbps | **No** |

#### Lossless Limitation

Spotify's lossless tier (launched September 2025) uses FLAC with additional DRM
protections that differ from the existing OGG Vorbis streaming pipeline.
**librespot cannot and will not implement lossless support.** In November 2025,
Spotify directly contacted the librespot maintainers and communicated that
pursuing development to circumvent their lossless technical protections would
jeopardize the project's existence. The librespot team redacted all related
technical discussion and locked the issue.

This is a hard constraint: lossless audio is not achievable through the
librespot ecosystem. The maximum quality available is **320 kbps OGG Vorbis**.

**Practical impact on the Sidephone SP-01**: Minimal. The device's primary audio
outputs are a phone loudspeaker and Bluetooth 4.2 (SBC codec, which tops out
at ~328 kbps and introduces its own lossy compression). Even over USB-C wired
audio, the difference between 320 kbps Vorbis and lossless FLAC is negligible
without high-end DAC hardware and headphones. 320 kbps OGG Vorbis is the
practical quality ceiling for this device.

### Audio Sink Options

**Option A: oboe-rs (Recommended)**
- Use the [`oboe`](https://crates.io/crates/oboe) Rust crate, which wraps
  Google's Oboe C++ library.
- Oboe automatically selects the best available backend (AAudio on Android 8.1+,
  OpenSL ES on older).
- Audio stays entirely in native code -- no JNI crossing for audio samples.
- Lowest latency, lowest CPU overhead.

**Option B: JNI AudioTrack**
- Write PCM samples from Rust to a `ByteBuffer`, pass across JNI to Kotlin,
  which feeds them to `AudioTrack`.
- More JNI overhead but simpler Rust code (no C++ dependency).
- Fallback if oboe-rs proves problematic on MT8766.

### Audio Configuration

| Parameter | Value |
|-----------|-------|
| Sample rate | 44100 Hz |
| Channels | Stereo (2) |
| Format | 16-bit PCM (S16LE) |
| Default bitrate | 160 kbps (balance of quality and bandwidth) |
| Buffer size | ~10ms latency target via Oboe |

### Bluetooth Audio

BT 4.2 on the SP-01 supports A2DP. Audio routing to Bluetooth is handled by
the Android audio framework transparently -- our app just writes to
AudioTrack/AAudio and the system routes to the active output device.

### Audio Focus

The app must request and manage Android audio focus:
- Request `AUDIOFOCUS_GAIN` when starting playback.
- Duck or pause on transient focus loss (incoming call, notification).
- Resume on focus regain.

This is handled in the Kotlin layer via `AudioManager.requestAudioFocus()`.

## 11. Caching Strategy

### Metadata Cache

- Cache track/album/artist metadata in a simple JSON file or lightweight
  key-value store in app-private storage.
- TTL: 24 hours for most metadata, 1 hour for "now playing" context.
- Max size: 10 MB (enough for thousands of metadata entries).

### Audio Cache (Optional)

- librespot supports caching decrypted audio files to disk.
- **Default: disabled** to conserve storage.
- User-configurable in Settings: 0 / 500 MB / 1 GB / 2 GB.
- LRU eviction when cache limit is reached.
- Stored in app-private storage (`/data/data/com.sidespot.app/cache/audio/`).

### Credential Cache

- Auth blob stored in app-private storage.
- Not backed up (no GMS backup service anyway).

## 12. Distribution

### Primary: Direct APK Download

- Build signed APK/AAB.
- Host on GitHub Releases.
- Users sideload via USB or direct download on device.

### Future Considerations

- **F-Droid**: Possible if the project is open-sourced. F-Droid builds from
  source and requires FOSS licensing. librespot is MIT-licensed, Compose is
  Apache-2.0 -- compatible.
- **Sidephone App Store**: If Sidephone has or develops their own app
  distribution mechanism, submit there.
- **No Google Play**: Cannot distribute via Play Store (no GMS dependency,
  and Spotify TOS concerns would likely trigger removal).

## 13. Risk Assessment

### Spotify TOS Violation

**Risk: HIGH**

librespot reverse-engineers Spotify's proprietary protocol. This violates
Spotify's Terms of Service. Consequences could include:

- Account suspension/ban for users.
- Protocol changes that break librespot (has happened before; the community
  adapts within days-weeks typically).
- Legal action (unlikely for a small open-source project; Spotify has tolerated
  librespot and its ecosystem for years, but no guarantee this continues).

**Mitigation:**
- Users must log in with their own Spotify Premium account.
- sidespot does not enable piracy, downloading, or any use beyond what a
  Premium subscriber can already do.
- The app adds genuine accessibility value for a device the official app
  doesn't support.
- Keep the project low-profile if not open-sourcing.

### Lossless DRM Boundary

**Risk: DO NOT CROSS**

Spotify has explicitly communicated to librespot maintainers that circumventing
lossless DRM protections would jeopardize the project. sidespot must not
attempt to implement lossless streaming. This is not a technical limitation to
work around -- it is a red line that protects librespot's continued existence.

### Protocol Breakage

**Risk: MEDIUM**

Spotify periodically changes their internal protocol. librespot has historically
kept up, but there can be periods of breakage.

**Mitigation:**
- Track librespot upstream releases closely.
- Design the JNI bridge to make upgrading the Rust library straightforward.
- Pin to stable librespot releases, not `dev` branch.

### librespot Maintenance Risk

**Risk: LOW-MEDIUM**

librespot has 167 contributors and consistent activity, but it's a volunteer
project.

**Mitigation:**
- The codebase is well-structured and MIT-licensed. If upstream stalls, we
  can maintain a fork.
- go-librespot exists as a fallback implementation if the Rust version is
  abandoned.

### MT8766 Compatibility

**Risk: LOW**

The MT8766 is a standard ARMv8-A processor. Rust cross-compiles to
`aarch64-linux-android` without issues. The main unknown is the audio stack
(Oboe/AAudio compatibility on this specific BSP).

**Mitigation:**
- Test on actual hardware early.
- Have JNI AudioTrack as fallback audio path.

## 14. MVP Scope & Milestones

### Phase 1: Foundation (MVP)

**Goal: Play a song on the Sidephone.**

- [ ] Cross-compile librespot to `aarch64-linux-android`
- [ ] Create JNI bridge with basic session + player functions
- [ ] OAuth login flow (browser-based)
- [ ] Credential caching
- [ ] Audio output via oboe-rs (or AudioTrack fallback)
- [ ] Hardcoded "play a track by URI" proof of concept
- [ ] Basic Now Playing screen (art, title, play/pause/seek)

### Phase 2: Core Experience

**Goal: Usable daily driver for playing your library.**

- [ ] Library screen (user playlists, saved albums, liked songs)
- [ ] Playlist / album detail view (track list)
- [ ] Search functionality
- [ ] Queue management (view queue, add to queue)
- [ ] Mini-player + bottom navigation
- [ ] Volume control (hardware buttons integration)
- [ ] Audio focus management
- [ ] Background playback via foreground service + notification

### Phase 3: Polish

**Goal: Reliable, pleasant experience.**

- [ ] Metadata caching
- [ ] Optional audio caching + settings UI
- [ ] Shuffle / repeat modes
- [ ] Error handling and offline resilience
- [ ] Battery optimization (doze mode handling, wake lock management)
- [ ] Bluetooth audio device switching
- [ ] Artist / album detail screens

### Phase 4: Extended (Optional)

- [ ] Spotify Connect receiver mode (appear as a playable device)
- [ ] Gapless playback
- [ ] Crossfade
- [ ] Lyrics (if available via librespot metadata)
- [ ] Podcasts / episodes support

## 15. Build System

### Repository Structure

```
sidespot/
├── DESIGN.md
├── README.md
├── LICENSE
├── app/                          # Android application (Kotlin)
│   ├── build.gradle.kts
│   └── src/
│       └── main/
│           ├── java/com/sidespot/
│           │   ├── MainActivity.kt
│           │   ├── ui/           # Compose screens
│           │   ├── viewmodel/    # ViewModels
│           │   ├── bridge/       # JNI bindings (Kotlin side)
│           │   ├── service/      # Playback foreground service
│           │   └── audio/        # Audio focus, routing
│           ├── res/
│           └── AndroidManifest.xml
├── native/                       # Rust native library
│   ├── Cargo.toml
│   ├── src/
│   │   ├── lib.rs               # JNI entry points
│   │   ├── bridge.rs            # C API wrappers around librespot
│   │   ├── player.rs            # Player management
│   │   ├── session.rs           # Session/auth management
│   │   ├── metadata.rs          # Metadata queries
│   │   └── audio_sink.rs        # Android audio output (oboe)
│   └── build.rs
├── build.gradle.kts              # Root Gradle build
├── settings.gradle.kts
└── gradle/
```

### Build Pipeline

1. **Rust compilation**: `cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release`
2. **Android compilation**: Standard Gradle build picks up the `.so` from
   `jniLibs/`.
3. **Combined**: A Gradle task that runs cargo-ndk before `assembleRelease`.

### CI/CD (Future)

- GitHub Actions workflow:
  1. Install Rust + Android NDK
  2. Cross-compile native library
  3. Build APK
  4. Upload as release artifact

## 16. Open Questions

1. **oboe-rs on MT8766**: Does Google's Oboe library work correctly on this
   specific MediaTek BSP? Need to test on hardware.
2. **OAuth redirect on Sidephone browser**: Does the Sidephone's browser
   correctly handle custom URI scheme redirects (`sidespot://callback`)?
   May need `http://localhost` fallback or in-app WebView.
3. **Spotify Connect feasibility**: Is there value in making the Sidephone
   appear as a Spotify Connect device? This would let users start playback
   from their main phone and have it play on the Sidephone.
4. **librespot API stability**: librespot doesn't have a stable public API.
   How tightly should we couple to their internals vs. maintaining our own
   abstraction?
5. **Audio decoding CPU impact**: Vorbis decoding on the Cortex-A53 at
   what battery cost? Need profiling on device.
6. **Notification controls**: Android media notification with playback controls
   -- how well does this work without GMS/MediaBrowserService?
7. **Lossless in the future**: If Spotify ever relaxes their lossless DRM or
   librespot finds a sanctioned path, the Symphonia decoder in librespot
   already supports FLAC decoding. The pipeline is ready; only the content
   access is blocked. The audio sink would need to handle 16-bit/24-bit
   44.1kHz/48kHz PCM, which Oboe/AudioTrack support natively. No
   architectural changes needed -- just a configuration flag.
