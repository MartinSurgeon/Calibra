package com.example.ui.history

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.data.SnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Measurement photo decoded off the main thread and downsampled to [maxDimension].
 * Renders [fallback] while loading or when the file is missing.
 */
@Composable
fun SnapshotImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxDimension: Int = 720,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit = {}
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, maxDimension) {
        value = withContext(Dispatchers.IO) {
            SnapshotStore.decodeSampled(path, maxDimension)?.asImageBitmap()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        fallback()
    }
}
