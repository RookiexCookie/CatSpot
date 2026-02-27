package com.sidespot.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sidespot.api.SearchPage
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
    val tracksDisplayLimit: Int = SEARCH_PAGE_SIZE,
    val albumsDisplayLimit: Int = SEARCH_PAGE_SIZE,
    val showsDisplayLimit: Int = SEARCH_PAGE_SIZE,
    val hasMoreTracks: Boolean = false,
    val hasMoreAlbums: Boolean = false,
    val hasMoreShows: Boolean = false,
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
)

private const val SEARCH_PAGE_SIZE = 5

class SearchViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var webApi: SpotifyWebApi? = null
    private var totalTracksAvailable = 0
    private var totalAlbumsAvailable = 0
    private var totalShowsAvailable = 0

    fun initApi(authManager: AuthManager) {
        if (webApi == null) {
            webApi = SpotifyWebApi(authManager)
        }
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }

        // Debounce search
        searchJob?.cancel()
        totalTracksAvailable = 0
        totalAlbumsAvailable = 0
        totalShowsAvailable = 0
        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    trackUris = emptyList(),
                    tracks = emptyList(),
                    albums = emptyList(),
                    shows = emptyList(),
                    tracksDisplayLimit = SEARCH_PAGE_SIZE,
                    albumsDisplayLimit = SEARCH_PAGE_SIZE,
                    showsDisplayLimit = SEARCH_PAGE_SIZE,
                    hasMoreTracks = false,
                    hasMoreAlbums = false,
                    hasMoreShows = false,
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

    private suspend fun performSearch(query: String) {
        _uiState.update { it.copy(isSearching = true, error = null) }

        val api = webApi
        if (api != null) {
            val tracksDeferred = viewModelScope.async(Dispatchers.IO) {
                try { api.searchTracks(query) } catch (_: Exception) { null }
            }
            val albumsDeferred = viewModelScope.async(Dispatchers.IO) {
                try { api.searchAlbums(query) } catch (_: Exception) { null }
            }
            val showsDeferred = viewModelScope.async(Dispatchers.IO) {
                try { api.searchShows(query) } catch (_: Exception) { null }
            }

            val tracksPage = tracksDeferred.await()
            val albumsPage = albumsDeferred.await()
            val showsPage = showsDeferred.await()

            if (tracksPage != null || albumsPage != null || showsPage != null) {
                totalTracksAvailable = tracksPage?.total ?: 0
                totalAlbumsAvailable = albumsPage?.total ?: 0
                totalShowsAvailable = showsPage?.total ?: 0
                val tracks = tracksPage?.items ?: emptyList()
                val albums = albumsPage?.items ?: emptyList()
                val shows = showsPage?.items ?: emptyList()
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        tracks = tracks,
                        trackUris = tracks.map { t -> t.uri },
                        albums = albums,
                        shows = shows,
                        tracksDisplayLimit = SEARCH_PAGE_SIZE,
                        albumsDisplayLimit = SEARCH_PAGE_SIZE,
                        showsDisplayLimit = SEARCH_PAGE_SIZE,
                        hasMoreTracks = tracks.size > SEARCH_PAGE_SIZE || totalTracksAvailable > tracks.size,
                        hasMoreAlbums = albums.size > SEARCH_PAGE_SIZE || totalAlbumsAvailable > albums.size,
                        hasMoreShows = shows.size > SEARCH_PAGE_SIZE || totalShowsAvailable > shows.size,
                    )
                }
                return
            }
        }

        // Fallback: native search if Web API unavailable
        fallbackNativeSearch(query)
    }

    private suspend fun fallbackNativeSearch(query: String) {
        val json = NativeBridge.metadataSearch(query)
        if (json == null || json.startsWith("{\"error\"")) {
            _uiState.update { it.copy(isSearching = false, error = json ?: "Search failed") }
            return
        }
        val results = SearchResults.fromJson(json)
        if (results == null) {
            _uiState.update { it.copy(isSearching = false, error = "Failed to parse results") }
            return
        }
        _uiState.update { it.copy(trackUris = results.trackUris) }

        val metadataDispatcher = Dispatchers.IO.limitedParallelism(4)
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
        _uiState.update { it.copy(isSearching = false) }
    }

    fun showMoreTracks() {
        val state = _uiState.value
        val newLimit = state.tracksDisplayLimit + SEARCH_PAGE_SIZE

        if (newLimit > state.tracks.size && state.tracks.size < totalTracksAvailable) {
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(isLoadingMore = true) }
                val page = webApi?.searchTracks(state.query, offset = state.tracks.size)
                if (page != null) {
                    totalTracksAvailable = page.total
                    _uiState.update { s ->
                        val allTracks = s.tracks + page.items
                        s.copy(
                            tracks = allTracks,
                            trackUris = allTracks.map { it.uri },
                            tracksDisplayLimit = newLimit,
                            hasMoreTracks = newLimit < allTracks.size || allTracks.size < totalTracksAvailable,
                            isLoadingMore = false,
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    tracksDisplayLimit = newLimit,
                    hasMoreTracks = newLimit < it.tracks.size || it.tracks.size < totalTracksAvailable,
                )
            }
        }
    }

    fun showMoreAlbums() {
        val state = _uiState.value
        val newLimit = state.albumsDisplayLimit + SEARCH_PAGE_SIZE

        if (newLimit > state.albums.size && state.albums.size < totalAlbumsAvailable) {
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(isLoadingMore = true) }
                val page = webApi?.searchAlbums(state.query, offset = state.albums.size)
                if (page != null) {
                    totalAlbumsAvailable = page.total
                    _uiState.update { s ->
                        val allAlbums = s.albums + page.items
                        s.copy(
                            albums = allAlbums,
                            albumsDisplayLimit = newLimit,
                            hasMoreAlbums = newLimit < allAlbums.size || allAlbums.size < totalAlbumsAvailable,
                            isLoadingMore = false,
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    albumsDisplayLimit = newLimit,
                    hasMoreAlbums = newLimit < it.albums.size || it.albums.size < totalAlbumsAvailable,
                )
            }
        }
    }

    fun showMoreShows() {
        val state = _uiState.value
        val newLimit = state.showsDisplayLimit + SEARCH_PAGE_SIZE

        if (newLimit > state.shows.size && state.shows.size < totalShowsAvailable) {
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(isLoadingMore = true) }
                val page = webApi?.searchShows(state.query, offset = state.shows.size)
                if (page != null) {
                    totalShowsAvailable = page.total
                    _uiState.update { s ->
                        val allShows = s.shows + page.items
                        s.copy(
                            shows = allShows,
                            showsDisplayLimit = newLimit,
                            hasMoreShows = newLimit < allShows.size || allShows.size < totalShowsAvailable,
                            isLoadingMore = false,
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    showsDisplayLimit = newLimit,
                    hasMoreShows = newLimit < it.shows.size || it.shows.size < totalShowsAvailable,
                )
            }
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
}
