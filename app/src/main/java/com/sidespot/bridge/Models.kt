package com.sidespot.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class TrackInfo(
    val uri: String,
    val name: String,
    val artists: List<ArtistSummary>,
    @SerialName("album_name") val albumName: String,
    @SerialName("album_uri") val albumUri: String,
    @SerialName("album_art_url") val albumArtUrl: String? = null,
    @SerialName("duration_ms") val durationMs: Int,
    @SerialName("track_number") val trackNumber: Int,
    @SerialName("disc_number") val discNumber: Int,
    @SerialName("is_explicit") val isExplicit: Boolean,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name }

    companion object {
        fun fromJson(jsonString: String): TrackInfo? = try {
            json.decodeFromString<TrackInfo>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class ArtistSummary(
    val uri: String,
    val name: String,
)

@Serializable
data class AlbumInfo(
    val uri: String,
    val name: String,
    val artists: List<ArtistSummary>,
    @SerialName("album_art_url") val albumArtUrl: String? = null,
    val tracks: List<TrackSummary>,
    @SerialName("album_type") val albumType: String,
    val label: String,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name }

    companion object {
        fun fromJson(jsonString: String): AlbumInfo? = try {
            json.decodeFromString<AlbumInfo>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class TrackSummary(
    val uri: String,
    val name: String,
    val artists: List<ArtistSummary>,
    @SerialName("duration_ms") val durationMs: Int,
    @SerialName("track_number") val trackNumber: Int,
    @SerialName("disc_number") val discNumber: Int,
    @SerialName("is_explicit") val isExplicit: Boolean,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name }
}

@Serializable
data class PlaylistInfo(
    val uri: String,
    val name: String,
    @SerialName("track_uris") val trackUris: List<String>,
    @SerialName("track_count") val trackCount: Int,
) {
    companion object {
        fun fromJson(jsonString: String): PlaylistInfo? = try {
            json.decodeFromString<PlaylistInfo>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class PlaylistSummary(
    val uri: String,
    val name: String,
) {
    companion object {
        fun listFromJson(jsonString: String): List<PlaylistSummary>? = try {
            json.decodeFromString<List<PlaylistSummary>>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class SearchResults(
    @SerialName("track_uris") val trackUris: List<String>,
) {
    companion object {
        fun fromJson(jsonString: String): SearchResults? = try {
            json.decodeFromString<SearchResults>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

data class AlbumSummary(
    val uri: String,
    val name: String,
    val artistName: String,
    val imageUrl: String? = null,
)

data class ShowSummary(
    val uri: String,
    val name: String,
    val publisher: String,
    val imageUrl: String? = null,
)

data class EpisodeSummary(
    val uri: String,
    val name: String,
    val description: String,
    val durationMs: Int,
    val releaseDate: String,
    val imageUrl: String? = null,
)
