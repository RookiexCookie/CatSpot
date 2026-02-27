package com.sidespot.bridge

/**
 * Kotlin-side JNI bindings to the sidespot native library (libsidespot.so).
 *
 * All methods are static and map to extern "C" functions in native/src/bridge.rs.
 * Complex return values are serialized as JSON strings by the Rust side.
 */
object NativeBridge {

    init {
        System.loadLibrary("sidespot")
    }

    /** Initialize the native library (logging, runtime). Call once from Application.onCreate(). */
    external fun nativeInit()

    // -- Configuration --

    /** Set the temporary directory for audio file downloads. Must be called before sessionConnect. */
    external fun setTmpDir(path: String)

    /**
     * Update player/session configuration from a JSON string.
     * @return null on success, or an error message string on failure.
     */
    external fun playerConfigure(configJson: String): String?

    /**
     * Recreate the player with the current config.
     * @return null on success, or an error message string on failure.
     */
    external fun playerRecreate(): String?

    // -- Session management --

    /**
     * Connect to Spotify with an OAuth access token.
     * @return null on success, or an error message string on failure.
     */
    external fun sessionConnect(accessToken: String): String?

    /** Disconnect the current Spotify session. */
    external fun sessionDisconnect()

    /** Check if a session is currently connected. */
    external fun sessionIsConnected(): Boolean

    // -- Audio callback registration --

    /**
     * Register the audio callback that receives PCM data from the native player.
     * The callback object must have a method: void onAudioData(byte[] data)
     * @return null on success, or an error message string on failure.
     */
    external fun registerAudioCallback(callback: Any): String?

    // -- Player control --

    /**
     * Create the player instance. Must be called after sessionConnect() succeeds
     * and registerAudioCallback() has been called.
     * @return null on success, or an error message string on failure.
     */
    external fun playerCreate(): String?

    /**
     * Load a track by Spotify URI and optionally start playing.
     * @param trackUri e.g. "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
     * @param startPlaying whether to auto-play after loading
     * @return null on success, or an error message string on failure.
     */
    external fun playerLoad(trackUri: String, startPlaying: Boolean): String?

    /**
     * Preload a track so it starts instantly when loaded next.
     * @return null on success, or an error message string on failure.
     */
    external fun playerPreload(trackUri: String): String?

    /** Resume playback. */
    external fun playerPlay()

    /** Pause playback. */
    external fun playerPause()

    /** Seek to a position in milliseconds. */
    external fun playerSeek(positionMs: Int)

    /** Stop playback. */
    external fun playerStop()

    /**
     * Poll for the next player event.
     * @return JSON string of the event, or null if no event pending.
     */
    external fun playerPollEvent(): String?

    // -- Volume control --

    /** Set playback volume (0-65535). */
    external fun playerSetVolume(volume: Int)

    /** Get current playback volume (0-65535). */
    external fun playerGetVolume(): Int

    // -- Metadata --

    /** Get track metadata by URI. Returns JSON string. */
    external fun metadataGetTrack(trackUri: String): String?

    /** Get album metadata by URI. Returns JSON string. */
    external fun metadataGetAlbum(albumUri: String): String?

    /** Get playlist metadata by URI. Returns JSON string. */
    external fun metadataGetPlaylist(playlistUri: String): String?

    /** Get user's playlists. Returns JSON array of playlist summaries. */
    external fun metadataGetUserPlaylists(): String?

    /** Get user's liked songs. Returns JSON playlist info. */
    external fun metadataGetLikedSongs(): String?

    /** Search Spotify. Returns JSON search results. */
    external fun metadataSearch(query: String): String?

    // -- Convenience --

    /** Initialize the native library. */
    fun init() {
        nativeInit()
    }
}
