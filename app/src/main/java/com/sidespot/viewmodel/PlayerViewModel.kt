package com.sidespot.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.sidespot.api.ApiResult
import com.sidespot.audio.AudioCallback
import com.sidespot.audio.AudioFocusManager
import com.sidespot.bridge.ArtistSummary
import com.sidespot.bridge.EpisodeSummary
import com.sidespot.bridge.NativeBridge
import com.sidespot.bridge.PlayerEvent
import com.sidespot.bridge.TrackInfo
import com.sidespot.service.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val trackUri: String = "",
    val trackTitle: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val albumArtUrl: String? = null,
    val durationMs: Long = 0L,
    val error: String? = null,
    val connectionStatus: String = "Disconnected",
    val volume: Int = 32768,
    val showVolumeOverlay: Boolean = false,
)

class PlayerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    val queueManager = QueueManager()

    private val audioCallback = AudioCallback()
    private var eventPollingActive = false

    private var appContext: Context? = null
    private var audioFocusManager: AudioFocusManager? = null
    private var mediaCommandReceiver: BroadcastReceiver? = null
    private var savedVolumeBeforeDuck: Int? = null

    private var consecutiveErrors = 0
    private var isReconnecting = false
    private var tokenProvider: (suspend () -> String?)? = null

    /**
     * Initialize platform services. Called from MainActivity after ViewModel creation.
     * Uses application context to avoid activity leak.
     */
    fun initPlatform(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        audioFocusManager = AudioFocusManager(context.applicationContext).apply {
            listener = object : AudioFocusManager.Listener {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onDuck() {
                    savedVolumeBeforeDuck = NativeBridge.playerGetVolume()
                    val ducked = (savedVolumeBeforeDuck!! * 0.3).toInt()
                    NativeBridge.playerSetVolume(ducked)
                }
                override fun onUnduck() {
                    savedVolumeBeforeDuck?.let { NativeBridge.playerSetVolume(it) }
                    savedVolumeBeforeDuck = null
                }
            }
        }

        // Register broadcast receiver for media session commands from PlaybackService
        mediaCommandReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.getStringExtra("command")) {
                    "play" -> play()
                    "pause" -> pause()
                    "next" -> next()
                    "previous" -> previous()
                    "stop" -> stop()
                    "seek" -> {
                        val pos = intent.getLongExtra("position", 0)
                        seek(pos.toInt())
                    }
                }
            }
        }
        context.applicationContext.registerReceiver(
            mediaCommandReceiver,
            IntentFilter("com.sidespot.MEDIA_COMMAND"),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    fun connect(accessToken: String, getToken: (suspend () -> String?)? = null) {
        tokenProvider = getToken
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(connectionStatus = "Connecting...", error = null) }

            val callbackError = NativeBridge.registerAudioCallback(audioCallback)
            if (callbackError != null) {
                _uiState.update {
                    it.copy(
                        connectionStatus = "Connection failed",
                        error = callbackError,
                        isConnected = false,
                    )
                }
                return@launch
            }

            val error = NativeBridge.sessionConnect(accessToken)
            if (error != null) {
                _uiState.update {
                    it.copy(
                        connectionStatus = "Connection failed",
                        error = error,
                        isConnected = false,
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(connectionStatus = "Connected", isConnected = true, error = null)
            }

            val playerError = NativeBridge.playerCreate()
            if (playerError != null) {
                _uiState.update {
                    it.copy(
                        connectionStatus = "Player creation failed",
                        error = playerError,
                    )
                }
                return@launch
            }

            val vol = NativeBridge.playerGetVolume()
            _uiState.update { it.copy(connectionStatus = "Ready", volume = vol) }

            startEventPolling()
        }
    }

    fun loadTrack(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = queueManager.state.value.trackMetadata[uri]
            _uiState.update {
                it.copy(
                    isLoading = true,
                    trackUri = uri,
                    trackTitle = cached?.name ?: "",
                    artistName = cached?.artistName ?: "",
                    albumName = cached?.albumName ?: "",
                    albumArtUrl = cached?.albumArtUrl ?: it.albumArtUrl,
                    durationMs = cached?.durationMs?.toLong() ?: it.durationMs,
                    error = null,
                )
            }

            // Request audio focus before playing
            audioFocusManager?.requestFocus()

            val error = NativeBridge.playerLoad(uri, true)
            if (error != null) {
                // Track unavailable — skip to next
                val nextUri = queueManager.next()
                if (nextUri != null) {
                    loadTrack(nextUri)
                }
                return@launch
            }

            // Start foreground service
            appContext?.let { PlaybackService.startService(it) }

            // Use cached metadata if available, otherwise fetch via JNI
            if (cached != null) {
                updatePlaybackService()
            } else {
                fetchAndApplyMetadata(uri)
            }
        }
    }

    fun loadTrackFromContext(tracks: List<String>, index: Int, contextName: String = "") {
        queueManager.loadContext(tracks, index, contextName)
        val uri = tracks.getOrNull(index) ?: return
        loadTrack(uri)
        // Preload metadata + art + audio for upcoming tracks
        viewModelScope.launch(Dispatchers.IO) {
            val alreadyCached = queueManager.state.value.trackMetadata
            tracks.drop(index + 1).take(5).forEach { nextUri ->
                if (nextUri !in alreadyCached) {
                    resolveAndCacheMetadata(nextUri)
                }
            }
            val cached = queueManager.state.value.trackMetadata
            val artUrls = tracks.drop(index).take(5).mapNotNull { cached[it]?.albumArtUrl }
            preloadAlbumArt(artUrls)

            // Preload audio for the very next track
            tracks.getOrNull(index + 1)?.let { nextUri ->
                NativeBridge.playerPreload(nextUri)
            }
        }
    }

    fun play() {
        viewModelScope.launch(Dispatchers.IO) {
            audioFocusManager?.requestFocus()
            NativeBridge.playerPlay()
        }
    }

    fun pause() {
        viewModelScope.launch(Dispatchers.IO) { NativeBridge.playerPause() }
    }

    fun seek(positionMs: Int) {
        viewModelScope.launch(Dispatchers.IO) { NativeBridge.playerSeek(positionMs) }
    }

    fun stop() {
        viewModelScope.launch(Dispatchers.IO) {
            NativeBridge.playerStop()
            audioFocusManager?.abandonFocus()
            appContext?.let { PlaybackService.stopService(it) }
        }
    }

    fun next() {
        viewModelScope.launch(Dispatchers.IO) {
            val nextUri = queueManager.next() ?: return@launch
            loadTrack(nextUri)
            preloadUpcoming()
        }
    }

    fun previous() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_positionMs.value > 3000) {
                NativeBridge.playerSeek(0)
                return@launch
            }
            val prevUri = queueManager.previous() ?: return@launch
            loadTrack(prevUri)
            preloadUpcoming()
        }
    }

    fun skipToQueueItem(isUserQueue: Boolean, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = if (isUserQueue) {
                queueManager.playFromUserQueue(index)
            } else {
                queueManager.playFromContext(index)
            } ?: return@launch
            loadTrack(uri)
            preloadUpcoming()
        }
    }

    fun addToQueue(uri: String) {
        queueManager.addToQueue(uri)
        // Also resolve metadata for the queued track (skip if already cached, e.g. episodes)
        if (uri !in queueManager.state.value.trackMetadata) {
            viewModelScope.launch(Dispatchers.IO) {
                resolveAndCacheMetadata(uri)
            }
        }
    }

    /**
     * Pre-cache episode metadata as TrackInfo so the player can display
     * episode name, show name, and art in the mini-player and now playing screen.
     */
    fun cacheEpisodeMetadata(episodes: List<EpisodeSummary>, showName: String) {
        for (episode in episodes) {
            val info = TrackInfo(
                uri = episode.uri,
                name = episode.name,
                artists = listOf(ArtistSummary(uri = "", name = showName)),
                albumName = showName,
                albumUri = "",
                albumArtUrl = episode.imageUrl,
                durationMs = episode.durationMs,
                trackNumber = 0,
                discNumber = 0,
                isExplicit = false,
            )
            queueManager.cacheMetadata(episode.uri, info)
        }
    }

    /**
     * Recreate the native player with updated config, preserving playback state.
     * Called after SettingsManager.applyAudioSettings().
     */
    fun recreatePlayer() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val savedUri = state.trackUri
            val savedPosition = _positionMs.value
            val wasPlaying = state.isPlaying

            val error = NativeBridge.playerRecreate()
            if (error != null) {
                _uiState.update { it.copy(error = "Player recreate failed: $error") }
                return@launch
            }

            // Resume playback if a track was loaded
            if (savedUri.isNotEmpty()) {
                NativeBridge.playerLoad(savedUri, wasPlaying)
                if (savedPosition > 0) {
                    // Add 1s offset to account for propagation delay
                    NativeBridge.playerSeek((savedPosition + 1000).toInt())
                }
            }
        }
    }

    fun addToLikedSongs(trackUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.libraryAddToLikedSongs(trackUri))
            onResult(result)
        }
    }

    fun addToPlaylist(playlistUri: String, trackUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.libraryAddToPlaylist(playlistUri, trackUri))
            onResult(result)
        }
    }

    fun createPlaylistAndAddTrack(
        name: String,
        trackUri: String,
        onResult: (ApiResult) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val createJson = NativeBridge.libraryCreatePlaylist(name)
            if (createJson == null) {
                onResult(ApiResult.Error("Create playlist failed: null response"))
                return@launch
            }
            try {
                val obj = org.json.JSONObject(createJson)
                if (!obj.optBoolean("success", false)) {
                    onResult(ApiResult.Error(obj.optString("error", "Unknown error")))
                    return@launch
                }
                val newUri = obj.optString("uri", "")
                if (newUri.isNotEmpty()) {
                    val addResult = parseNativeResult(
                        NativeBridge.libraryAddToPlaylist(newUri, trackUri)
                    )
                    onResult(addResult)
                } else {
                    // Playlist was created but URI not returned — still success
                    onResult(ApiResult.Success)
                }
            } catch (e: Exception) {
                onResult(ApiResult.Error("Parse error: ${e.message}"))
            }
        }
    }

    private fun parseNativeResult(json: String?): ApiResult {
        if (json == null) return ApiResult.Error("Null response from native")
        return try {
            val obj = org.json.JSONObject(json)
            if (obj.optBoolean("success", false)) {
                ApiResult.Success
            } else {
                ApiResult.Error(obj.optString("error", "Unknown error"))
            }
        } catch (e: Exception) {
            ApiResult.Error("Parse error: ${e.message}")
        }
    }

    fun toggleShuffle() {
        queueManager.toggleShuffle()
    }

    fun cycleRepeatMode() {
        queueManager.cycleRepeatMode()
    }

    fun resolveQueueMetadata() {
        viewModelScope.launch(Dispatchers.IO) {
            val queueState = queueManager.state.value
            val urisToResolve = mutableListOf<String>()
            urisToResolve.addAll(queueState.userQueue)
            urisToResolve.addAll(
                queueState.contextTracks.drop(queueState.contextIndex + 1).take(20),
            )

            val cached = queueState.trackMetadata
            val artUrlsToPreload = mutableListOf<String>()
            for (uri in urisToResolve) {
                if (uri !in cached) {
                    resolveAndCacheMetadata(uri)
                }
            }

            // Preload album art for the next few tracks
            val updated = queueManager.state.value.trackMetadata
            urisToResolve.take(5).forEach { uri ->
                updated[uri]?.albumArtUrl?.let { artUrlsToPreload.add(it) }
            }
            preloadAlbumArt(artUrlsToPreload)
        }
    }

    private suspend fun preloadUpcoming() {
        val qState = queueManager.state.value
        val upcoming = qState.userQueue.ifEmpty {
            qState.contextTracks.drop(qState.contextIndex + 1)
        }.take(3)
        val cached = qState.trackMetadata
        val artUrls = mutableListOf<String>()
        for (uri in upcoming) {
            if (uri !in cached) {
                resolveAndCacheMetadata(uri)
            }
            queueManager.state.value.trackMetadata[uri]?.albumArtUrl?.let { artUrls.add(it) }
        }
        preloadAlbumArt(artUrls)

        // Preload audio for the very next track
        upcoming.firstOrNull()?.let { nextUri ->
            NativeBridge.playerPreload(nextUri)
        }
    }

    private suspend fun resolveAndCacheMetadata(uri: String) {
        val json = NativeBridge.metadataGetTrack(uri) ?: return
        val info = TrackInfo.fromJson(json) ?: return
        queueManager.cacheMetadata(uri, info)
    }

    private fun preloadAlbumArt(urls: List<String>) {
        val ctx = appContext ?: return
        val loader = ctx.imageLoader
        for (url in urls) {
            loader.enqueue(
                ImageRequest.Builder(ctx)
                    .data(url)
                    .size(128, 128)
                    .build(),
            )
        }
    }

    fun onVolumeChanged(volume: Int) {
        _uiState.update { it.copy(volume = volume, showVolumeOverlay = true) }
        viewModelScope.launch {
            delay(1500)
            _uiState.update { it.copy(showVolumeOverlay = false) }
        }
    }

    private suspend fun fetchAndApplyMetadata(uri: String) {
        val json = NativeBridge.metadataGetTrack(uri) ?: return
        val trackInfo = TrackInfo.fromJson(json) ?: return
        _uiState.update {
            it.copy(
                trackTitle = trackInfo.name,
                artistName = trackInfo.artistName,
                albumName = trackInfo.albumName,
                albumArtUrl = trackInfo.albumArtUrl,
                durationMs = trackInfo.durationMs.toLong(),
            )
        }
        queueManager.cacheMetadata(uri, trackInfo)
        updatePlaybackService()
    }

    private fun updatePlaybackService() {
        val state = _uiState.value
        appContext?.let { ctx ->
            PlaybackService.updateMetadata(
                context = ctx,
                title = state.trackTitle,
                artist = state.artistName,
                artUrl = state.albumArtUrl,
                isPlaying = state.isPlaying,
                positionMs = _positionMs.value,
                durationMs = state.durationMs,
            )
        }
    }

    private fun startEventPolling() {
        if (eventPollingActive) return
        eventPollingActive = true

        viewModelScope.launch(Dispatchers.IO) {
            while (eventPollingActive) {
                val json = NativeBridge.playerPollEvent()
                if (json != null) {
                    val event = PlayerEvent.fromJson(json)
                    if (event != null) {
                        handlePlayerEvent(event)
                    }
                }
                delay(50)
            }
        }
    }

    private fun handlePlayerEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.Playing -> {
                consecutiveErrors = 0
                val current = _uiState.value
                _positionMs.value = event.positionMs.toLong()
                if (!current.isPlaying || current.isLoading) {
                    _uiState.update {
                        it.copy(isPlaying = true, isLoading = false)
                    }
                    if (!current.isPlaying) updatePlaybackService()
                }
            }
            is PlayerEvent.Paused -> {
                val wasPlaying = _uiState.value.isPlaying
                _positionMs.value = event.positionMs.toLong()
                if (wasPlaying) {
                    _uiState.update { it.copy(isPlaying = false) }
                    updatePlaybackService()
                }
            }
            is PlayerEvent.Stopped -> {
                _positionMs.value = 0L
                _uiState.update { it.copy(isPlaying = false) }
                updatePlaybackService()
            }
            is PlayerEvent.Loading -> {
                _uiState.update {
                    it.copy(isLoading = true)
                }
            }
            is PlayerEvent.EndOfTrack -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val nextUri = queueManager.next()
                    if (nextUri != null) {
                        loadTrack(nextUri)
                        preloadUpcoming()
                    } else {
                        _positionMs.value = 0L
                        _uiState.update { it.copy(isPlaying = false) }
                        audioFocusManager?.abandonFocus()
                        appContext?.let { PlaybackService.stopService(it) }
                    }
                }
            }
            is PlayerEvent.Error -> {
                if (isReconnecting) return
                consecutiveErrors++
                if (consecutiveErrors < 2) {
                    // Single error — likely a bad track, skip to next
                    val nextUri = queueManager.next()
                    if (nextUri != null) {
                        loadTrack(nextUri)
                    }
                } else {
                    // Multiple consecutive errors — session likely dead, reconnect
                    val currentUri = _uiState.value.trackUri
                    viewModelScope.launch(Dispatchers.IO) {
                        val reconnected = attemptReconnect()
                        if (reconnected && currentUri.isNotEmpty()) {
                            loadTrack(currentUri)
                        } else {
                            _uiState.update {
                                it.copy(
                                    isPlaying = false,
                                    isLoading = false,
                                    error = "Playback failed. Please reconnect.",
                                )
                            }
                            audioFocusManager?.abandonFocus()
                            appContext?.let { PlaybackService.stopService(it) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun attemptReconnect(): Boolean {
        isReconnecting = true
        try {
            NativeBridge.sessionDisconnect()
            val token = tokenProvider?.invoke() ?: return false
            val error = NativeBridge.sessionConnect(token)
            if (error != null) return false
            val playerError = NativeBridge.playerRecreate()
            if (playerError != null) return false
            consecutiveErrors = 0
            return true
        } finally {
            isReconnecting = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        eventPollingActive = false
        audioCallback.release()
        audioFocusManager?.abandonFocus()
        appContext?.let { ctx ->
            mediaCommandReceiver?.let { ctx.unregisterReceiver(it) }
            PlaybackService.stopService(ctx)
        }
        NativeBridge.sessionDisconnect()
    }
}
