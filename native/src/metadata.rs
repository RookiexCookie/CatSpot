//! Metadata retrieval module.
//!
//! Wraps librespot's `Metadata` trait and `SpClient` to fetch track, album,
//! playlist, and search information, returning JSON to the Kotlin layer.

use librespot_core::SpotifyUri;
use librespot_metadata::image::ImageSize;
use librespot_metadata::{Album, Metadata, Playlist, Track};
use serde::Serialize;

use crate::error::{Result, SidespotError};
use crate::session;

// ---------------------------------------------------------------------------
// Serde structs returned as JSON to Kotlin
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
pub struct TrackInfo {
    pub uri: String,
    pub name: String,
    pub artists: Vec<ArtistSummary>,
    pub album_name: String,
    pub album_uri: String,
    pub album_art_url: Option<String>,
    pub duration_ms: i32,
    pub track_number: i32,
    pub disc_number: i32,
    pub is_explicit: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct ArtistSummary {
    pub uri: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct AlbumInfo {
    pub uri: String,
    pub name: String,
    pub artists: Vec<ArtistSummary>,
    pub album_art_url: Option<String>,
    pub tracks: Vec<TrackSummary>,
    pub album_type: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct TrackSummary {
    pub uri: String,
    pub name: String,
    pub artists: Vec<ArtistSummary>,
    pub duration_ms: i32,
    pub track_number: i32,
    pub disc_number: i32,
    pub is_explicit: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct PlaylistInfo {
    pub uri: String,
    pub name: String,
    pub track_uris: Vec<String>,
    pub track_count: i32,
}

#[derive(Debug, Clone, Serialize)]
pub struct PlaylistSummary {
    pub uri: String,
    pub name: String,
    pub is_writable: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct SearchResults {
    pub track_uris: Vec<String>,
}

// ---------------------------------------------------------------------------
// Image URL helper
// ---------------------------------------------------------------------------

/// Construct a Spotify CDN image URL from a librespot FileId.
/// Prefers the largest available image.
fn image_url_from_images(images: &librespot_metadata::image::Images) -> Option<String> {
    // Prefer larger images: Large > Medium > Small > XLarge (XLarge is sometimes a different format)
    let preferred_order = [ImageSize::LARGE, ImageSize::DEFAULT, ImageSize::SMALL];

    for &size in &preferred_order {
        if let Some(img) = images.iter().find(|i| i.size == size) {
            return Some(format!("https://i.scdn.co/image/{}", img.id.to_base16()));
        }
    }
    // Fall back to first available
    images
        .first()
        .map(|img| format!("https://i.scdn.co/image/{}", img.id.to_base16()))
}

// ---------------------------------------------------------------------------
// Public async functions
// ---------------------------------------------------------------------------

/// Fetch full track metadata.
pub async fn get_track_info(uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let spotify_uri = SpotifyUri::from_uri(uri)
        .map_err(|e| SidespotError::Player(format!("invalid URI '{uri}': {e}")))?;

    let track = Track::get(&session, &spotify_uri)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get track metadata: {e}")))?;

    let artists: Vec<ArtistSummary> = track
        .artists
        .iter()
        .map(|a| ArtistSummary {
            uri: a.id.to_uri(),
            name: a.name.clone(),
        })
        .collect();

    let album_art_url = image_url_from_images(&track.album.covers);

    let info = TrackInfo {
        uri: track.id.to_uri(),
        name: track.name.clone(),
        artists,
        album_name: track.album.name.clone(),
        album_uri: track.album.id.to_uri(),
        album_art_url,
        duration_ms: track.duration,
        track_number: track.number,
        disc_number: track.disc_number,
        is_explicit: track.is_explicit,
    };

    Ok(serde_json::to_string(&info)?)
}

/// Fetch album metadata with all track details.
pub async fn get_album_info(uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let spotify_uri = SpotifyUri::from_uri(uri)
        .map_err(|e| SidespotError::Player(format!("invalid URI '{uri}': {e}")))?;

    let album = Album::get(&session, &spotify_uri)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get album metadata: {e}")))?;

    let album_art_url = image_url_from_images(&album.covers);
    let album_artists: Vec<ArtistSummary> = album
        .artists
        .iter()
        .map(|a| ArtistSummary {
            uri: a.id.to_uri(),
            name: a.name.clone(),
        })
        .collect();

    // Fetch individual track metadata concurrently (capped to avoid overload)
    let track_uris: Vec<SpotifyUri> = album.tracks().cloned().collect();
    let mut tracks = Vec::with_capacity(track_uris.len());

    // Fetch in batches of 10
    for chunk in track_uris.chunks(10) {
        let mut handles = Vec::new();
        for track_uri in chunk {
            let sess = session.clone();
            let tu = track_uri.clone();
            handles.push(tokio::spawn(async move { Track::get(&sess, &tu).await }));
        }
        for handle in handles {
            match handle.await {
                Ok(Ok(track)) => {
                    let track_artists: Vec<ArtistSummary> = track
                        .artists
                        .iter()
                        .map(|a| ArtistSummary {
                            uri: a.id.to_uri(),
                            name: a.name.clone(),
                        })
                        .collect();
                    tracks.push(TrackSummary {
                        uri: track.id.to_uri(),
                        name: track.name.clone(),
                        artists: track_artists,
                        duration_ms: track.duration,
                        track_number: track.number,
                        disc_number: track.disc_number,
                        is_explicit: track.is_explicit,
                    });
                }
                Ok(Err(e)) => {
                    log::warn!("Failed to fetch track in album: {e}");
                }
                Err(e) => {
                    log::warn!("Task join error fetching track: {e}");
                }
            }
        }
    }

    let info = AlbumInfo {
        uri: album.id.to_uri(),
        name: album.name.clone(),
        artists: album_artists,
        album_art_url,
        tracks,
        album_type: album.type_str.clone(),
        label: album.label.clone(),
    };

    Ok(serde_json::to_string(&info)?)
}

/// Fetch playlist metadata (track URIs only, metadata fetched lazily).
pub async fn get_playlist_info(uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let spotify_uri = SpotifyUri::from_uri(uri)
        .map_err(|e| SidespotError::Player(format!("invalid URI '{uri}': {e}")))?;

    let playlist = Playlist::get(&session, &spotify_uri)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get playlist metadata: {e}")))?;

    let track_uris: Vec<String> = playlist.tracks().map(|u| u.to_uri()).collect();

    let info = PlaylistInfo {
        uri: spotify_uri.to_uri(),
        name: playlist.name().to_string(),
        track_count: playlist.length,
        track_uris,
    };

    Ok(serde_json::to_string(&info)?)
}

/// Fetch the user's root playlist list.
pub async fn get_user_playlists() -> Result<String> {
    let session = session::get_session().await?;

    let response = session
        .spclient()
        .get_rootlist(0, None)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get rootlist: {e}")))?;

    // The rootlist returns a protobuf SelectedListContent.
    // Parse it to extract playlist URIs and names.
    use librespot_protocol::playlist4_external::SelectedListContent;
    use protobuf::Message;

    let content = SelectedListContent::parse_from_bytes(&response)
        .map_err(|e| SidespotError::Player(format!("failed to parse rootlist: {e}")))?;

    let items = &content.contents.items;
    let meta_items = &content.contents.meta_items;

    let username = session.username();

    let mut playlists = Vec::new();
    for (i, item) in items.iter().enumerate() {
        let uri = item.uri();
        // Only include playlists (skip folders, etc.)
        if uri.starts_with("spotify:playlist:") {
            let name = meta_items
                .get(i)
                .and_then(|m| m.attributes.as_ref())
                .map(|a| a.name().to_string())
                .unwrap_or_default();

            let owner = meta_items
                .get(i)
                .map(|m| m.owner_username().to_string())
                .unwrap_or_default();

            let collaborative = meta_items
                .get(i)
                .and_then(|m| m.attributes.as_ref())
                .map(|a| a.collaborative())
                .unwrap_or(false);

            let is_writable = owner.is_empty() || owner == username || collaborative;

            playlists.push(PlaylistSummary {
                uri: uri.to_string(),
                name,
                is_writable,
            });
        }
    }

    Ok(serde_json::to_string(&playlists)?)
}

/// Fetch the user's liked songs via context resolve.
pub async fn get_liked_songs() -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();
    let context_uri = format!("spotify:user:{username}:collection");

    let context = session
        .spclient()
        .get_context(&context_uri)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get liked songs: {e}")))?;

    let mut track_uris = Vec::new();
    for page in context.pages.iter() {
        for track in page.tracks.iter() {
            let uri = track.uri();
            if !uri.is_empty() {
                track_uris.push(uri.to_string());
            }
        }
    }

    // Return as PlaylistInfo with a fixed name
    let info = PlaylistInfo {
        uri: context_uri,
        name: "Liked Songs".to_string(),
        track_count: track_uris.len() as i32,
        track_uris,
    };

    Ok(serde_json::to_string(&info)?)
}

/// Fetch autoplay (recommended) tracks based on a context URI and recent tracks.
pub async fn get_autoplay_tracks(context_uri: &str, recent_track_uris: &[String]) -> Result<String> {
    use librespot_protocol::autoplay_context_request::AutoplayContextRequest;

    let session = session::get_session().await?;

    let request = AutoplayContextRequest {
        context_uri: Some(context_uri.to_string()),
        recent_track_uri: recent_track_uris.to_vec(),
        ..Default::default()
    };

    let context = session
        .spclient()
        .get_autoplay_context(&request)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get autoplay context: {e}")))?;

    let mut track_uris = Vec::new();
    for page in context.pages.iter() {
        for track in page.tracks.iter() {
            let uri = track.uri();
            if !uri.is_empty() && uri.starts_with("spotify:track:") {
                track_uris.push(uri.to_string());
            }
        }
    }

    Ok(serde_json::to_string(&track_uris)?)
}

/// Search Spotify and return track URIs.
pub async fn search(query: &str) -> Result<String> {
    let session = session::get_session().await?;
    let encoded_query = query.replace(' ', "+");
    let context_uri = format!("spotify:search:{encoded_query}");

    let context = session
        .spclient()
        .get_context(&context_uri)
        .await
        .map_err(|e| SidespotError::Player(format!("search failed: {e}")))?;

    let mut track_uris = Vec::new();
    for page in context.pages.iter() {
        for track in page.tracks.iter() {
            let uri = track.uri();
            if !uri.is_empty() && uri.starts_with("spotify:track:") {
                track_uris.push(uri.to_string());
            }
        }
    }

    let results = SearchResults { track_uris };
    Ok(serde_json::to_string(&results)?)
}
