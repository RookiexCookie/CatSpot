package com.sidespot.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sidespot.api.ApiResult
import com.sidespot.api.SpotifyWebApi
import com.sidespot.auth.AuthManager
import com.sidespot.bridge.AlbumSummary
import com.sidespot.bridge.EpisodeSummary
import com.sidespot.bridge.NativeBridge
import com.sidespot.bridge.PlaylistSummary
import com.sidespot.bridge.ShowSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val playlists: List<PlaylistSummary> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val shows: List<ShowSummary> = emptyList(),
    val episodes: List<EpisodeSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingAlbums: Boolean = false,
    val isLoadingShows: Boolean = false,
    val isLoadingEpisodes: Boolean = false,
    val error: String? = null,
)

class LibraryViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    private var api: SpotifyWebApi? = null

    fun initApi(authManager: AuthManager) {
        if (api == null) api = SpotifyWebApi(authManager)
    }

    fun loadPlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val json = NativeBridge.metadataGetUserPlaylists()
            if (json == null) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to load playlists")
                }
                return@launch
            }

            // Check for error response
            if (json.startsWith("{\"error\"")) {
                _uiState.update { it.copy(isLoading = false, error = json) }
                return@launch
            }

            val playlists = PlaylistSummary.listFromJson(json)
            if (playlists != null) {
                _uiState.update {
                    it.copy(playlists = playlists, isLoading = false)
                }
            } else {
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to parse playlists")
                }
            }
        }
    }

    fun loadSavedAlbums() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingAlbums = true) }
            val json = NativeBridge.metadataGetSavedAlbums()
            val albums = if (json != null && !json.startsWith("{\"error\"")) {
                AlbumSummary.listFromJson(json) ?: emptyList()
            } else {
                emptyList()
            }
            _uiState.update { it.copy(albums = albums, isLoadingAlbums = false) }
        }
    }

    fun loadSavedShows() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingShows = true) }
            val json = NativeBridge.metadataGetSavedShows()
            val shows = if (json != null && !json.startsWith("{\"error\"")) {
                ShowSummary.listFromJson(json) ?: emptyList()
            } else {
                emptyList()
            }
            _uiState.update { it.copy(shows = shows, isLoadingShows = false) }
        }
    }

    fun loadShowEpisodes(showUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingEpisodes = true, episodes = emptyList()) }
            val json = NativeBridge.metadataGetShowEpisodes(showUri)
            val episodes = if (json != null && !json.startsWith("{\"error\"")) {
                EpisodeSummary.listFromJson(json) ?: emptyList()
            } else {
                emptyList()
            }
            _uiState.update { it.copy(episodes = episodes, isLoadingEpisodes = false) }
        }
    }

    fun saveAlbum(albumUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.librarySaveAlbum(albumUri))
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    if (state.albums.none { it.uri == albumUri }) {
                        state.copy(albums = listOf(
                            AlbumSummary(uri = albumUri, name = "", artistName = ""),
                        ) + state.albums)
                    } else state
                }
            }
            onResult(result)
        }
    }

    fun unsaveAlbum(albumUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.libraryUnsaveAlbum(albumUri))
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    state.copy(albums = state.albums.filter { it.uri != albumUri })
                }
            }
            onResult(result)
        }
    }

    fun saveShow(showUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.librarySaveShow(showUri))
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    if (state.shows.none { it.uri == showUri }) {
                        state.copy(shows = listOf(
                            ShowSummary(uri = showUri, name = "", publisher = ""),
                        ) + state.shows)
                    } else state
                }
            }
            onResult(result)
        }
    }

    fun unsaveShow(showUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.libraryUnsaveShow(showUri))
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    state.copy(shows = state.shows.filter { it.uri != showUri })
                }
            }
            onResult(result)
        }
    }

    fun savePlaylist(playlistUri: String, playlistName: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.librarySavePlaylist(playlistUri))
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    if (state.playlists.none { it.uri == playlistUri }) {
                        state.copy(playlists = listOf(
                            PlaylistSummary(uri = playlistUri, name = playlistName),
                        ) + state.playlists)
                    } else state
                }
            }
            onResult(result)
        }
    }

    fun unsavePlaylist(playlistUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = api?.unfollowPlaylist(playlistUri)
                ?: ApiResult.Error("API not initialized")
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    state.copy(playlists = state.playlists.filter { it.uri != playlistUri })
                }
            }
            onResult(result)
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
}
