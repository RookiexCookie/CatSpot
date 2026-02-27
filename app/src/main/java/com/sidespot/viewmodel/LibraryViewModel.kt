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

    private var webApi: SpotifyWebApi? = null

    fun initApi(authManager: AuthManager) {
        if (webApi == null) {
            webApi = SpotifyWebApi(authManager)
        }
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
        val api = webApi ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingAlbums = true) }
            val albums = api.getUserSavedAlbums()
            _uiState.update { it.copy(albums = albums, isLoadingAlbums = false) }
        }
    }

    fun loadSavedShows() {
        val api = webApi ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingShows = true) }
            val shows = api.getUserSavedShows()
            _uiState.update { it.copy(shows = shows, isLoadingShows = false) }
        }
    }

    fun loadShowEpisodes(showUri: String) {
        val api = webApi ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingEpisodes = true, episodes = emptyList()) }
            val episodes = api.getShowEpisodes(showUri)
            _uiState.update { it.copy(episodes = episodes, isLoadingEpisodes = false) }
        }
    }

    fun saveAlbum(albumUri: String, onResult: (ApiResult) -> Unit) {
        val api = webApi ?: run {
            onResult(ApiResult.Error("API not initialized"))
            return
        }
        viewModelScope.launch {
            val result = api.saveAlbum(albumUri)
            onResult(result)
        }
    }

    fun saveShow(showUri: String, onResult: (ApiResult) -> Unit) {
        val api = webApi ?: run {
            onResult(ApiResult.Error("API not initialized"))
            return
        }
        viewModelScope.launch {
            val result = api.saveShow(showUri)
            onResult(result)
        }
    }
}
