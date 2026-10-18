package com.pushbound.game.ui

import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

/**
 * Loads a raster game asset once per composition. Assets live in
 * res/drawable-nodpi, so the renderer controls all scaling and the
 * approved artwork is never density-resampled behind our back.
 */
@Composable
fun rememberGameBitmap(@DrawableRes resId: Int): ImageBitmap {
    val context = LocalContext.current
    return remember(context, resId) {
        BitmapFactory.decodeResource(context.resources, resId).asImageBitmap()
    }
}
