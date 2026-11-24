package com.scanpdf.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Small in-memory cache for page thumbnails (keyed by path; thumbnail names change on every edit). */
private object ThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(file: File): Bitmap? =
        cache.get(file.path) ?: BitmapFactory.decodeFile(file.path)?.also { cache.put(file.path, it) }
}

/** Shows an image file, decoded off the main thread; a grey box while loading or if missing. */
@Composable
fun FileImage(
    file: File?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val bitmap by produceState<Bitmap?>(null, file) {
        value = file?.let { withContext(Dispatchers.IO) { ThumbnailCache.get(it) } }
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription, modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(Color(0x22888888)))
    }
}
