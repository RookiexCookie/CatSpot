package com.sidespot.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sidespot.bridge.NativeBridge
import com.sidespot.bridge.SearchAlbumResult
import com.sidespot.bridge.SearchPageResult
import com.sidespot.bridge.SearchPlaylistResult
import com.sidespot.bridge.SearchResults
import com.sidespot.bridge.SearchShowResult
import com.sidespot.bridge.ShowSummary
import com.sidespot.bridge.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

data class AlbumResult(
    val uri: String,
    val name: String,
    val artistName: String,
    val albumArtUrl: String?,
)

data class PlaylistResult(
    val uri: String,
    val name: String,
    val ownerName: String,
    val imageUrl: String?,
)

data class SearchUiState(
    val query: String = "",
    val trackUris: List<String> = emptyList(),
    val tracks: List<TrackInfo> = emptyList(),
    val albums: List<AlbumResult> = emptyList(),
    val shows: List<ShowSummary> = emptyList(),
    val playlists: List<PlaylistResult> = emptyList(),
    val tracksDisplayLimit: Int = SEARCH_PAGE_SIZE,
    val albumsDisplayLimit: Int = SEARCH_PAGE_SIZE,
    val showsDisplayLimit: Int = SEARCH_PAGE_SIZE,
    val playlistsDisplayLimit: Int = SEARCH_PAGE_SIZE,
    val hasMoreTracks: Boolean = false,
    val hasMoreAlbums: Boolean = false,
    val hasMoreShows: Boolean = false,
    val hasMorePlaylists: Boolean = false,
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
)

private const val SEARCH_PAGE_SIZE = 5
private const val SEARCH_TIMEOUT_MS = 15_000L

private val lenientJson = Json { ignoreUnknownKeys = true }

class SearchViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var totalTracksAvailable = 0
    private var totalAlbumsAvailable = 0
    private var totalShowsAvailable = 0
    private var totalPlaylistsAvailable = 0

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }

        // Debounce search
        searchJob?.cancel()
        totalTracksAvailable = 0
        totalAlbumsAvailable = 0
        totalShowsAvailable = 0
        totalPlaylistsAvailable = 0
        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    trackUris = emptyList(),
                    tracks = emptyList(),
                    albums = emptyList(),
                    shows = emptyList(),
                    playlists = emptyList(),
                    tracksDisplayLimit = SEARCH_PAGE_SIZE,
                    albumsDisplayLimit = SEARCH_PAGE_SIZE,
                    showsDisplayLimit = SEARCH_PAGE_SIZE,
                    playlistsDisplayLimit = SEARCH_PAGE_SIZE,
                    hasMoreTracks = false,
                    hasMoreAlbums = false,
                    hasMoreShows = false,
                    hasMorePlaylists = false,
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

        val json = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                NativeBridge.metadataSearch(query)
            }
        }

        if (json == null) {
            _uiState.update { it.copy(isSearching = false, error = "Search timed out") }
            return
        }

        if (json.startsWith("{\"error\"")) {
            _uiState.update { it.copy(isSearching = false, error = "Search failed") }
            return
        }

        val results = SearchResults.fromJson(json)
        if (results == null) {
            _uiState.update { it.copy(isSearching = false, error = "Search failed") }
            return
        }

        val tracks = results.tracks
        val albums = results.albums.map { it.toUiModel() }
        val playlists = results.playlists.map { it.toUiModel() }
        val shows = results.shows.map { it.toUiModel() }

        totalTracksAvailable = results.totalTracks
        totalAlbumsAvailable = results.totalAlbums
        totalPlaylistsAvailable = results.totalPlaylists
        totalShowsAvailable = results.totalShows

        _uiState.update {
            it.copy(
                isSearching = false,
                tracks = tracks,
                trackUris = tracks.map { t -> t.uri },
                albums = albums,
                shows = shows,
                playlists = playlists,
                tracksDisplayLimit = SEARCH_PAGE_SIZE,
                albumsDisplayLimit = SEARCH_PAGE_SIZE,
                showsDisplayLimit = SEARCH_PAGE_SIZE,
                playlistsDisplayLimit = SEARCH_PAGE_SIZE,
                hasMoreTracks = tracks.size > SEARCH_PAGE_SIZE || totalTracksAvailable > tracks.size,
                hasMoreAlbums = albums.size > SEARCH_PAGE_SIZE || totalAlbumsAvailable > albums.size,
                hasMoreShows = shows.size > SEARCH_PAGE_SIZE || totalShowsAvailable > shows.size,
                hasMorePlaylists = playlists.size > SEARCH_PAGE_SIZE || totalPlaylistsAvailable > playlists.size,
            )
        }
    }

    fun showMoreTracks() {
        val state = _uiState.value
        val newLimit = state.tracksDisplayLimit + SEARCH_PAGE_SIZE

        if (newLimit > state.tracks.size && state.tracks.size < totalTracksAvailable) {
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(isLoadingMore = true) }
                val page = fetchMoreNative<TrackInfo>(state.query, "track", state.tracks.size)
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
                val page = fetchMoreNative<SearchAlbumResult>(state.query, "album", state.albums.size)
                if (page != null) {
                    totalAlbumsAvailable = page.total
                    _uiState.update { s ->
                        val allAlbums = s.albums + page.items.map { it.toUiModel() }
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
                val page = fetchMoreNative<SearchShowResult>(state.query, "show", state.shows.size)
                if (page != null) {
                    totalShowsAvailable = page.total
                    _uiState.update { s ->
                        val allShows = s.shows + page.items.map { it.toUiModel() }
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

    fun showMorePlaylists() {
        val state = _uiState.value
        val newLimit = state.playlistsDisplayLimit + SEARCH_PAGE_SIZE

        if (newLimit > state.playlists.size && state.playlists.size < totalPlaylistsAvailable) {
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(isLoadingMore = true) }
                val page = fetchMoreNative<SearchPlaylistResult>(state.query, "playlist", state.playlists.size)
                if (page != null) {
                    totalPlaylistsAvailable = page.total
                    _uiState.update { s ->
                        val allPlaylists = s.playlists + page.items.map { it.toUiModel() }
                        s.copy(
                            playlists = allPlaylists,
                            playlistsDisplayLimit = newLimit,
                            hasMorePlaylists = newLimit < allPlaylists.size || allPlaylists.size < totalPlaylistsAvailable,
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
                    playlistsDisplayLimit = newLimit,
                    hasMorePlaylists = newLimit < it.playlists.size || it.playlists.size < totalPlaylistsAvailable,
                )
            }
        }
    }

    private inline fun <reified T> fetchMoreNative(query: String, type: String, offset: Int): SearchPageResult<T>? {
        val json = NativeBridge.metadataSearchMore(query, type, offset) ?: return null
        if (json.startsWith("{\"error\"")) return null
        return try {
            lenientJson.decodeFromString<SearchPageResult<T>>(json)
        } catch (_: Exception) {
            null
        }
    }
}

// Mapping extensions from bridge types to UI types
private fun SearchAlbumResult.toUiModel() = AlbumResult(
    uri = uri,
    name = name,
    artistName = artistName,
    albumArtUrl = albumArtUrl,
)

private fun SearchPlaylistResult.toUiModel() = PlaylistResult(
    uri = uri,
    name = name,
    ownerName = ownerName,
    imageUrl = imageUrl,
)

private fun SearchShowResult.toUiModel() = ShowSummary(
    uri = uri,
    name = name,
    publisher = publisher,
    imageUrl = imageUrl,
)
