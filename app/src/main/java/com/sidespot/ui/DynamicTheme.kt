package com.sidespot.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size

data class AlbumColors(
    val primary: Color = SpotifyGreen,
    val primaryContainer: Color = SpotifyGreenDark,
    val surface: Color = DefaultSurface,
    val surfaceVariant: Color = DefaultSurfaceVariant,
    val background: Color = DefaultBackground,
)

private val SpotifyGreen = Color(0xFF1DB954)
private val SpotifyGreenDark = Color(0xFF1AA34A)
private val DefaultSurface = Color(0xFF1E1E1E)
private val DefaultSurfaceVariant = Color(0xFF282828)
private val DefaultBackground = Color(0xFF121212)

@Composable
fun rememberAlbumColors(albumArtUrl: String?): AlbumColors {
    var colors by remember { mutableStateOf(AlbumColors()) }
    val context = LocalContext.current

    LaunchedEffect(albumArtUrl) {
        if (albumArtUrl == null) {
            colors = AlbumColors()
            return@LaunchedEffect
        }

        val bitmap = loadBitmap(context, albumArtUrl) ?: return@LaunchedEffect
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        colors = extractColors(palette)
    }

    return colors
}

private suspend fun loadBitmap(context: android.content.Context, url: String): Bitmap? {
    val loader = context.imageLoader
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(Size(128, 128))
        .allowHardware(false)
        .build()
    val result = loader.execute(request)
    return (result as? SuccessResult)?.drawable?.let { drawable ->
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap
    }
}

private fun extractColors(palette: Palette): AlbumColors {
    val vibrant = palette.vibrantSwatch
    val darkVibrant = palette.darkVibrantSwatch
    val muted = palette.mutedSwatch
    val darkMuted = palette.darkMutedSwatch
    val lightVibrant = palette.lightVibrantSwatch

    val primarySwatch = vibrant ?: lightVibrant ?: muted
    val primaryColor = primarySwatch?.let { Color(it.rgb) } ?: SpotifyGreen

    val containerSwatch = darkVibrant ?: darkMuted
    val primaryContainer = containerSwatch?.let { Color(it.rgb) } ?: primaryColor.darken(0.3f)

    val surfaceSwatch = darkMuted ?: muted
    val surfaceColor = surfaceSwatch?.let { Color(it.rgb).darken(0.6f) } ?: DefaultSurface
    val surfaceVariantColor = surfaceSwatch?.let { Color(it.rgb).darken(0.5f) } ?: DefaultSurfaceVariant
    val backgroundColor = surfaceSwatch?.let { Color(it.rgb).darken(0.75f) } ?: DefaultBackground

    return AlbumColors(
        primary = primaryColor.ensureReadableOnDark(),
        primaryContainer = primaryContainer,
        surface = surfaceColor,
        surfaceVariant = surfaceVariantColor,
        background = backgroundColor,
    )
}

private fun Color.darken(factor: Float): Color {
    return Color(
        red = red * (1f - factor),
        green = green * (1f - factor),
        blue = blue * (1f - factor),
        alpha = alpha,
    )
}

private fun Color.ensureReadableOnDark(): Color {
    val lum = this.luminance()
    if (lum >= 0.15f) return this
    // Boost brightness to ensure readability on dark surfaces
    val boost = 0.15f / lum.coerceAtLeast(0.01f)
    return Color(
        red = (red * boost).coerceIn(0f, 1f),
        green = (green * boost).coerceIn(0f, 1f),
        blue = (blue * boost).coerceIn(0f, 1f),
        alpha = alpha,
    )
}

private const val TRANSITION_MS = 800

@Composable
fun DynamicSidespotTheme(
    albumColors: AlbumColors,
    content: @Composable () -> Unit,
) {
    val primary by animateColorAsState(
        targetValue = albumColors.primary,
        animationSpec = tween(TRANSITION_MS),
        label = "primary",
    )
    val primaryContainer by animateColorAsState(
        targetValue = albumColors.primaryContainer,
        animationSpec = tween(TRANSITION_MS),
        label = "primaryContainer",
    )
    val surface by animateColorAsState(
        targetValue = albumColors.surface,
        animationSpec = tween(TRANSITION_MS),
        label = "surface",
    )
    val surfaceVariant by animateColorAsState(
        targetValue = albumColors.surfaceVariant,
        animationSpec = tween(TRANSITION_MS),
        label = "surfaceVariant",
    )
    val background by animateColorAsState(
        targetValue = albumColors.background,
        animationSpec = tween(TRANSITION_MS),
        label = "background",
    )

    val colorScheme = remember(primary, primaryContainer, surface, surfaceVariant, background) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color.Black,
            primaryContainer = primaryContainer,
            secondary = Color(0xFFB3B3B3),
            onSecondary = Color.Black,
            background = background,
            onBackground = Color.White,
            surface = surface,
            onSurface = Color.White,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = Color(0xFFB3B3B3),
            error = Color(0xFFCF6679),
            onError = Color.Black,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SidespotTypography,
        content = content,
    )
}
