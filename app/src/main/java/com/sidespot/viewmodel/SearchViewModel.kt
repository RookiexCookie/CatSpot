package com.sidespot.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sidespot.api.SpotifyWebApi
import com.sidespot.auth.AuthManager
import com.sidespot.bridge.NativeBridge
import com.sidespot.bridge.SearchResults
import com.sidespot.bridge.ShowSummary
import com.sidespot.bridge.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumResult(
    val uri: String,
    val name: String,
    val artistName: String,
    val albumArtUrl: String?,
)

data class SearchUiState(
    val query: String = "",
    val trackUris: List<String> = emptyList(),
    val tracks: List<TrackInfo> = emptyList(),
    val albums: List<AlbumResult> = emptyList(),
    val shows: List<ShowSummary> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
)

class SearchViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var webApi: SpotifyWebApi? = null
    private val metadataDispatcher = Dispatchers.IO.limitedParallelism(4)

    fun initApi(authManager: AuthManager) {
        if (webApi == null) {
            webApi = SpotifyWebApi(authManager)
        }
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }

        // Debounce search
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    trackUris = emptyList(),
                    tracks = emptyList(),
                    albums = emptyList(),
                    shows = emptyList(),
                    error = null,
                )
            }
            return
        }

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400) // debounce
            performSearch(query)
        }
    }

    private fun deriveAlbums(tracks: List<TrackInfo>): List<AlbumResult> {
        val seen = mutableSetOf<String>()
        return tracks.mapNotNull { track ->
            if (track.albumUri.isBlank()) return@mapNotNull null
            if (seen.add(track.albumUri)) {
                AlbumResult(
                    uri = track.albumUri,
                    name = track.albumName,
                    artistName = track.artistName,
                    albumArtUrl = track.albumArtUrl,
                )
            } else null
        }
    }

    private suspend fun performSearch(query: String) {
        _uiState.update { it.copy(isSearching = true, error = null) }

        // Search shows via Web API in parallel with native track search
        val showsDeferred = webApi?.let {
            viewModelScope.async(Dispatchers.IO) {
                try { it.searchShows(query) } catch (_: Exception) { emptyList() }
            }
        }

        val json = NativeBridge.metadataSearch(query)
        if (json == null || json.startsWith("{\"error\"")) {
            val shows = showsDeferred?.await() ?: emptyList()
            _uiState.update {
                it.copy(
                    isSearching = false,
                    shows = shows,
                    error = if (shows.isEmpty()) (json ?: "Search failed") else null,
                )
            }
            return
        }

        val results = SearchResults.fromJson(json)
        if (results == null) {
            val shows = showsDeferred?.await() ?: emptyList()
            _uiState.update {
                it.copy(
                    isSearching = false,
                    shows = shows,
                    error = if (shows.isEmpty()) "Failed to parse results" else null,
                )
            }
            return
        }

        _uiState.update { it.copy(trackUris = results.trackUris) }

        // Fetch track metadata concurrently
        val allTracks = mutableListOf<TrackInfo>()
        for (chunk in results.trackUris.take(50).chunked(10)) {
            val deferred = chunk.map { uri ->
                viewModelScope.async(metadataDispatcher) {
                    val trackJson = NativeBridge.metadataGetTrack(uri)
                    trackJson?.let { TrackInfo.fromJson(it) }
                }
            }
            val batch = deferred.awaitAll().filterNotNull()
            allTracks.addAll(batch)
            val albums = deriveAlbums(allTracks)
            _uiState.update { it.copy(tracks = allTracks.toList(), albums = albums) }
        }

        val shows = showsDeferred?.await() ?: emptyList()
        _uiState.update { it.copy(isSearching = false, shows = shows) }
    }
}
