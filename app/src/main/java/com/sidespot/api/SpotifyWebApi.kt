package com.sidespot.api

import com.sidespot.auth.AuthManager
import com.sidespot.bridge.AlbumSummary
import com.sidespot.bridge.ArtistSummary
import com.sidespot.bridge.EpisodeSummary
import com.sidespot.bridge.ShowSummary
import com.sidespot.bridge.TrackInfo
import com.sidespot.viewmodel.AlbumResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed class ApiResult {
    data object Success : ApiResult()
    data class Error(val message: String) : ApiResult()
}

sealed class CreatePlaylistResult {
    data class Success(val playlistUri: String) : CreatePlaylistResult()
    data class Error(val message: String) : CreatePlaylistResult()
}

data class SearchPage<T>(val items: List<T>, val total: Int)

class SpotifyWebApi(private val authManager: AuthManager) {

    companion object {
        private const val BASE_URL = "https://api.spotify.com/v1"
    }

    /**
     * Add a track to the user's Liked Songs.
     * PUT /v1/me/tracks?ids={id}
     */
    suspend fun addToLikedSongs(trackUri: String): ApiResult = withContext(Dispatchers.IO) {
        val trackId = trackUri.substringAfterLast(":")
        val token = authManager.getValidAccessToken()
            ?: return@withContext ApiResult.Error("Not authenticated")

        val url = URL("$BASE_URL/me/tracks?ids=$trackId")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write("{}".toByteArray()) }

            if (conn.responseCode in 200..299) {
                ApiResult.Success
            } else {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "unknown error"
                ApiResult.Error("HTTP ${conn.responseCode}: $errorBody")
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unknown error")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Create a new playlist for the current user.
     * GET /v1/me -> user_id, then POST /v1/users/{user_id}/playlists
     */
    suspend fun createPlaylist(name: String): CreatePlaylistResult = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken()
            ?: return@withContext CreatePlaylistResult.Error("Not authenticated")

        try {
            // Get user ID
            val meConn = URL("$BASE_URL/me").openConnection() as HttpURLConnection
            val userId: String
            try {
                meConn.setRequestProperty("Authorization", "Bearer $token")
                if (meConn.responseCode !in 200..299) {
                    return@withContext CreatePlaylistResult.Error("Failed to get user profile")
                }
                val body = meConn.inputStream.bufferedReader().readText()
                userId = org.json.JSONObject(body).getString("id")
            } finally {
                meConn.disconnect()
            }

            // Create playlist
            val conn = URL("$BASE_URL/users/$userId/playlists").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val reqBody = org.json.JSONObject().apply {
                    put("name", name)
                    put("public", false)
                }.toString()
                conn.outputStream.use { it.write(reqBody.toByteArray()) }

                if (conn.responseCode in 200..299) {
                    val respBody = conn.inputStream.bufferedReader().readText()
                    val playlistUri = org.json.JSONObject(respBody).getString("uri")
                    CreatePlaylistResult.Success(playlistUri)
                } else {
                    val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "unknown error"
                    CreatePlaylistResult.Error("HTTP ${conn.responseCode}: $errorBody")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            CreatePlaylistResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Add a track to a playlist.
     * POST /v1/playlists/{playlist_id}/tracks
     */
    suspend fun addToPlaylist(playlistUri: String, trackUri: String): ApiResult =
        withContext(Dispatchers.IO) {
            val playlistId = playlistUri.substringAfterLast(":")
            val token = authManager.getValidAccessToken()
                ?: return@withContext ApiResult.Error("Not authenticated")

            val url = URL("$BASE_URL/playlists/$playlistId/tracks")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = """{"uris":["$trackUri"]}"""
                conn.outputStream.use { it.write(body.toByteArray()) }

                if (conn.responseCode in 200..299) {
                    ApiResult.Success
                } else {
                    val errorBody =
                        conn.errorStream?.bufferedReader()?.readText() ?: "unknown error"
                    ApiResult.Error("HTTP ${conn.responseCode}: $errorBody")
                }
            } catch (e: Exception) {
                ApiResult.Error(e.message ?: "Unknown error")
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Get the user's saved albums.
     * GET /v1/me/albums?limit=50
     */
    suspend fun getUserSavedAlbums(): List<AlbumSummary> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext emptyList()
        val conn = URL("$BASE_URL/me/albums?limit=50").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader().readText()
            val items = JSONObject(body).getJSONArray("items")
            (0 until items.length()).map { i ->
                val album = items.getJSONObject(i).getJSONObject("album")
                val artists = album.getJSONArray("artists")
                val artistName = (0 until artists.length())
                    .joinToString(", ") { artists.getJSONObject(it).getString("name") }
                val images = album.getJSONArray("images")
                val imageUrl = if (images.length() > 0) images.getJSONObject(0).getString("url") else null
                AlbumSummary(
                    uri = album.getString("uri"),
                    name = album.getString("name"),
                    artistName = artistName,
                    imageUrl = imageUrl,
                )
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Get the user's saved shows (podcasts).
     * GET /v1/me/shows?limit=50
     */
    suspend fun getUserSavedShows(): List<ShowSummary> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext emptyList()
        val conn = URL("$BASE_URL/me/shows?limit=50").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader().readText()
            val items = JSONObject(body).getJSONArray("items")
            (0 until items.length()).map { i ->
                val show = items.getJSONObject(i).getJSONObject("show")
                val images = show.getJSONArray("images")
                val imageUrl = if (images.length() > 0) images.getJSONObject(0).getString("url") else null
                ShowSummary(
                    uri = show.getString("uri"),
                    name = show.getString("name"),
                    publisher = show.getString("publisher"),
                    imageUrl = imageUrl,
                )
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Get episodes for a show.
     * GET /v1/shows/{id}/episodes?limit=50
     */
    suspend fun getShowEpisodes(showUri: String): List<EpisodeSummary> = withContext(Dispatchers.IO) {
        val showId = showUri.substringAfterLast(":")
        val token = authManager.getValidAccessToken() ?: return@withContext emptyList()
        val conn = URL("$BASE_URL/shows/$showId/episodes?limit=50").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader().readText()
            val items = JSONObject(body).getJSONArray("items")
            (0 until items.length()).map { i ->
                val ep = items.getJSONObject(i)
                val images = ep.getJSONArray("images")
                val imageUrl = if (images.length() > 0) images.getJSONObject(0).getString("url") else null
                EpisodeSummary(
                    uri = ep.getString("uri"),
                    name = ep.getString("name"),
                    description = ep.optString("description", ""),
                    durationMs = ep.getInt("duration_ms"),
                    releaseDate = ep.optString("release_date", ""),
                    imageUrl = imageUrl,
                )
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Save an album to the user's library.
     * PUT /v1/me/albums?ids={id}
     */
    suspend fun saveAlbum(albumUri: String): ApiResult = withContext(Dispatchers.IO) {
        val albumId = albumUri.substringAfterLast(":")
        val token = authManager.getValidAccessToken()
            ?: return@withContext ApiResult.Error("Not authenticated")

        val url = URL("$BASE_URL/me/albums")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write("""{"ids":["$albumId"]}""".toByteArray()) }

            if (conn.responseCode in 200..299) {
                ApiResult.Success
            } else {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "unknown error"
                ApiResult.Error("HTTP ${conn.responseCode}: $errorBody")
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unknown error")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Save a show (podcast) to the user's library.
     * PUT /v1/me/shows?ids={id}
     */
    suspend fun saveShow(showUri: String): ApiResult = withContext(Dispatchers.IO) {
        val showId = showUri.substringAfterLast(":")
        val token = authManager.getValidAccessToken()
            ?: return@withContext ApiResult.Error("Not authenticated")

        val url = URL("$BASE_URL/me/shows")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write("""{"ids":["$showId"]}""".toByteArray()) }

            if (conn.responseCode in 200..299) {
                ApiResult.Success
            } else {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "unknown error"
                ApiResult.Error("HTTP ${conn.responseCode}: $errorBody")
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unknown error")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Search for shows (podcasts).
     * GET /v1/search?type=show&q={query}&limit=10
     */
    suspend fun searchShows(query: String, offset: Int = 0): SearchPage<ShowSummary> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext SearchPage(emptyList(), 0)
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val conn = URL("$BASE_URL/search?type=show&q=$encoded&limit=10&offset=$offset").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText()
                android.util.Log.w("SpotifyWebApi", "searchShows HTTP ${conn.responseCode}: $err")
                return@withContext SearchPage(emptyList(), 0)
            }
            val body = conn.inputStream.bufferedReader().readText()
            val showsObj = JSONObject(body).optJSONObject("shows")
                ?: return@withContext SearchPage(emptyList(), 0)
            val items = showsObj.getJSONArray("items")
            val total = showsObj.optInt("total", 0)
            val results = (0 until items.length()).mapNotNull { i ->
                val show = items.optJSONObject(i) ?: return@mapNotNull null
                val images = show.optJSONArray("images")
                val imageUrl = if (images != null && images.length() > 0)
                    images.getJSONObject(0).getString("url") else null
                ShowSummary(
                    uri = show.getString("uri"),
                    name = show.getString("name"),
                    publisher = show.optString("publisher", ""),
                    imageUrl = imageUrl,
                )
            }
            SearchPage(results, total)
        } catch (e: Exception) {
            android.util.Log.e("SpotifyWebApi", "searchShows failed", e)
            SearchPage(emptyList(), 0)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Search for tracks.
     * GET /v1/search?type=track&q={query}&limit=10
     */
    suspend fun searchTracks(query: String, offset: Int = 0): SearchPage<TrackInfo> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext SearchPage(emptyList(), 0)
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val conn = URL("$BASE_URL/search?type=track&q=$encoded&limit=10&offset=$offset").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText()
                android.util.Log.w("SpotifyWebApi", "searchTracks HTTP ${conn.responseCode}: $err")
                return@withContext SearchPage(emptyList(), 0)
            }
            val body = conn.inputStream.bufferedReader().readText()
            val tracksObj = JSONObject(body).optJSONObject("tracks")
                ?: run {
                    android.util.Log.w("SpotifyWebApi", "searchTracks: no 'tracks' key in response")
                    return@withContext SearchPage(emptyList(), 0)
                }
            val items = tracksObj.getJSONArray("items")
            val total = tracksObj.optInt("total", 0)
            android.util.Log.d("SpotifyWebApi", "searchTracks: got ${items.length()} items, total=$total")
            val results = (0 until items.length()).mapNotNull { i ->
                try {
                    val track = items.optJSONObject(i) ?: return@mapNotNull null
                    val artistsArr = track.optJSONArray("artists")
                    val artists = if (artistsArr != null) {
                        (0 until artistsArr.length()).mapNotNull { j ->
                            val a = artistsArr.optJSONObject(j) ?: return@mapNotNull null
                            ArtistSummary(
                                uri = a.optString("uri", ""),
                                name = a.optString("name", ""),
                            )
                        }
                    } else emptyList()
                    val album = track.optJSONObject("album")
                    val albumImages = album?.optJSONArray("images")
                    val albumArtUrl = if (albumImages != null && albumImages.length() > 0)
                        albumImages.getJSONObject(0).getString("url") else null
                    TrackInfo(
                        uri = track.optString("uri", "") .takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null,
                        name = track.optString("name", ""),
                        artists = artists,
                        albumName = album?.optString("name", "") ?: "",
                        albumUri = album?.optString("uri", "") ?: "",
                        albumArtUrl = albumArtUrl,
                        durationMs = track.optInt("duration_ms", 0),
                        trackNumber = track.optInt("track_number", 0),
                        discNumber = track.optInt("disc_number", 0),
                        isExplicit = track.optBoolean("explicit", false),
                    )
                } catch (e: Exception) {
                    android.util.Log.w("SpotifyWebApi", "Failed to parse track item $i", e)
                    null
                }
            }
            SearchPage(results, total)
        } catch (e: Exception) {
            android.util.Log.e("SpotifyWebApi", "searchTracks failed", e)
            SearchPage(emptyList(), 0)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Search for albums.
     * GET /v1/search?type=album&q={query}&limit=10
     */
    suspend fun searchAlbums(query: String, offset: Int = 0): SearchPage<AlbumResult> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext SearchPage(emptyList(), 0)
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val conn = URL("$BASE_URL/search?type=album&q=$encoded&limit=10&offset=$offset").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText()
                android.util.Log.w("SpotifyWebApi", "searchAlbums HTTP ${conn.responseCode}: $err")
                return@withContext SearchPage(emptyList(), 0)
            }
            val body = conn.inputStream.bufferedReader().readText()
            val albumsObj = JSONObject(body).optJSONObject("albums")
                ?: return@withContext SearchPage(emptyList(), 0)
            val items = albumsObj.getJSONArray("items")
            val total = albumsObj.optInt("total", 0)
            val results = (0 until items.length()).mapNotNull { i ->
                try {
                    val album = items.optJSONObject(i) ?: return@mapNotNull null
                    val artistsArr = album.optJSONArray("artists")
                    val artistName = if (artistsArr != null) {
                        (0 until artistsArr.length()).mapNotNull { j ->
                            artistsArr.optJSONObject(j)?.optString("name")
                        }.joinToString(", ")
                    } else ""
                    val images = album.optJSONArray("images")
                    val imageUrl = if (images != null && images.length() > 0)
                        images.getJSONObject(0).getString("url") else null
                    AlbumResult(
                        uri = album.optString("uri", "").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null,
                        name = album.optString("name", ""),
                        artistName = artistName,
                        albumArtUrl = imageUrl,
                    )
                } catch (e: Exception) {
                    android.util.Log.w("SpotifyWebApi", "Failed to parse album item $i", e)
                    null
                }
            }
            SearchPage(results, total)
        } catch (e: Exception) {
            android.util.Log.e("SpotifyWebApi", "searchAlbums failed", e)
            SearchPage(emptyList(), 0)
        } finally {
            conn.disconnect()
        }
    }
}
