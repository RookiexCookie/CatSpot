package com.sidespot.ui

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * Blurs a bitmap by downscaling then upscaling.
 * Works on all API levels without RenderScript or RenderEffect.
 */
class BitmapBlurTransformation(
    private val scale: Float = 0.04f,
) : Transformation {
    override val cacheKey = "bitmap_blur_$scale"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = (input.width * scale).toInt().coerceAtLeast(1)
        val h = (input.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(input, w, h, true)
        return Bitmap.createScaledBitmap(small, input.width, input.height, true)
    }
}
