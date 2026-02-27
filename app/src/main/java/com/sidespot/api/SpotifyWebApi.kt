package com.sidespot.api

import com.sidespot.auth.AuthManager
import com.sidespot.bridge.AlbumSummary
import com.sidespot.bridge.EpisodeSummary
import com.sidespot.bridge.ShowSummary
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
    suspend fun searchShows(query: String): List<ShowSummary> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val conn = URL("$BASE_URL/search?type=show&q=$encoded&limit=10").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText()
                android.util.Log.w("SpotifyWebApi", "searchShows HTTP ${conn.responseCode}: $err")
                return@withContext emptyList()
            }
            val body = conn.inputStream.bufferedReader().readText()
            val showsObj = JSONObject(body).optJSONObject("shows")
                ?: return@withContext emptyList()
            val items = showsObj.getJSONArray("items")
            (0 until items.length()).mapNotNull { i ->
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
        } catch (e: Exception) {
            android.util.Log.e("SpotifyWebApi", "searchShows failed", e)
            emptyList()
        } finally {
            conn.disconnect()
        }
    }
}
